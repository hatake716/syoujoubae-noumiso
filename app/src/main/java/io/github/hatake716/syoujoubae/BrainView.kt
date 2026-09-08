package io.github.hatake716.syoujoubae

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

fun groupColor(group:String):FloatArray = when(group) {
    "視覚" -> floatArrayOf(.39f,.72f,.77f)
    "嗅覚" -> floatArrayOf(.92f,.71f,.36f)
    "学習・記憶" -> floatArrayOf(.90f,.60f,.51f)
    "方向定位" -> floatArrayOf(.72f,.63f,.90f)
    "感覚・運動" -> floatArrayOf(.51f,.75f,.61f)
    "解剖区画" -> floatArrayOf(.45f,.53f,.61f)
    else -> floatArrayOf(.57f,.67f,.73f)
}

/** Native, offline OpenGL ES renderer. Gestures change only camera, never specimen geometry. */
class BrainView(context:Context, val atlas:AtlasRepository):GLSurfaceView(context) {
    var onPick:(Int)->Unit = {}
    var onReady:()->Unit = {}
    var onFailure:(String)->Unit = {}
    val brain = BrainRenderer(context, atlas, { id -> post { onPick(id) } }, { post { onReady() } }, { message -> post { onFailure(message) } })
    private var lastX=0f;private var lastY=0f;private var downX=0f;private var downY=0f
    private var downTime=0L;private var moved=false
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val scale=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector:ScaleGestureDetector):Boolean {
            brain.zoom=(brain.zoom*detector.scaleFactor).coerceIn(.35f,18f);moved=true;requestRender();return true
        }
    })
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8,8,8,8,16,0)
        preserveEGLContextOnPause=true
        setRenderer(brain)
        renderMode=RENDERMODE_WHEN_DIRTY
        contentDescription="MaleCNSの脳の3Dモデル。1本指で回転、2本指で拡大と移動。部位は下の一覧からも選べます。"
        isFocusable=true
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        scale.onTouchEvent(e)
        val x=if(e.pointerCount>1) (e.getX(0)+e.getX(1))/2 else e.x
        val y=if(e.pointerCount>1) (e.getY(0)+e.getY(1))/2 else e.y
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {downX=x;downY=y;lastX=x;lastY=y;downTime=e.eventTime;moved=false;parent.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_POINTER_DOWN -> {lastX=x;lastY=y;moved=true}
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining=if(e.actionIndex==0) 1 else 0
                lastX=e.getX(remaining);lastY=e.getY(remaining);moved=true
            }
            MotionEvent.ACTION_MOVE -> {
                if(abs(x-downX)+abs(y-downY)>slop) moved=true
                if(e.pointerCount==1 && !scale.isInProgress) {brain.yaw+=(x-lastX)*.35f;brain.pitch=(brain.pitch+(y-lastY)*.35f).coerceIn(-180f,180f)}
                else if(e.pointerCount==2) {brain.panX+=(x-lastX)/width*2.5f/brain.zoom;brain.panY-=(y-lastY)/height*2.5f/brain.zoom}
                lastX=x;lastY=y;requestRender()
            }
            MotionEvent.ACTION_UP -> {
                if(!moved && e.eventTime-downTime<450) {brain.pick=Pair(e.x.toInt(),e.y.toInt());requestRender();performClick()}
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    fun update(selected:Int?, isolate:Boolean, preview:Boolean, opacity:Float, skeleton:Skeleton?, pose:Int, focus:Int) {
        brain.selected=selected;brain.isolate=isolate;brain.previewVisible=preview;brain.opacity=opacity
        queueEvent { brain.setSkeleton(skeleton);brain.setPose(pose,focus);requestRender() }
    }
}

class BrainRenderer(private val context:Context,private val atlas:AtlasRepository,private val picked:(Int)->Unit,
                    private val ready:()->Unit,private val failed:(String)->Unit):GLSurfaceView.Renderer {
    @Volatile var yaw=-6f;@Volatile var pitch=85f;@Volatile var zoom=1.25f
    @Volatile var panX=0f;@Volatile var panY=0f
    @Volatile var selected:Int?=null;@Volatile var isolate=false
    @Volatile var previewVisible=false;@Volatile var opacity=.82f
    @Volatile var pick:Pair<Int,Int>?=null
    private var program=0;private var width=1;private var height=1
    private var position=0;private var normal=0;private var colorAttribute=0
    private var mvpUniform=0;private var modelUniform=0;private var colorUniform=0;private var modeUniform=0
    private val projection=FloatArray(16);private val model=FloatArray(16);private val mvp=FloatArray(16)
    private val meshes=mutableListOf<Mesh>()
    private var preview:FloatBuffer?=null;private var previewVertices=0
    private var skeleton:FloatBuffer?=null;private var skeletonVertices=0;private var skeletonId:Long?=null
    private var neuronBounds:Pair<FloatArray,Float>?=null
    private var poseVersion=-1;private var focusVersion=-1
    private var failedOnce=false
    private data class Mesh(val info:MeshInfo,val vertices:FloatBuffer,val count:Int,val color:FloatArray)
    private fun buffer(values:FloatArray):FloatBuffer = ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values);position(0) }

    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) {
        try {
            program=createProgram()
            position=glGetAttribLocation(program,"aPosition");normal=glGetAttribLocation(program,"aNormal");colorAttribute=glGetAttribLocation(program,"aColor")
            mvpUniform=glGetUniformLocation(program,"uMvp");modelUniform=glGetUniformLocation(program,"uModel")
            colorUniform=glGetUniformLocation(program,"uColor");modeUniform=glGetUniformLocation(program,"uMode")
            if(meshes.isEmpty()) {
                atlas.meshes.forEach { info ->
                    val b=ByteBuffer.wrap(context.assets.open("atlas/meshes/${info.id}.bin").use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
                    val count=b.int;val a=FloatArray(count*6)
                    for(i in a.indices) a[i]=b.float
                    for(i in a.indices step 6) for(axis in 0..2) a[i+axis]=(a[i+axis]-Geometry.origin[axis])/Geometry.unit
                    val group=atlas.regions.firstOrNull { it.key==info.key }?.group ?: "統合領域"
                    meshes.add(Mesh(info,buffer(a),count,groupColor(group)))
                }
                val b=ByteBuffer.wrap(context.assets.open("atlas/preview.bin").use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
                previewVertices=b.int;val a=FloatArray(previewVertices*6)
                for(i in a.indices) a[i]=b.float
                for(i in a.indices step 6) for(axis in 0..2) a[i+axis]=(a[i+axis]-Geometry.origin[axis])/Geometry.unit
                preview=buffer(a)
            }
            glClearColor(.045f,.080f,.109f,1f)
            glDisable(GL_CULL_FACE)
            ready()
        } catch(e:Exception) { failedOnce=true;failed("3Dモデルを読み込めませんでした：${e.localizedMessage}") }
    }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int) {width=w.coerceAtLeast(1);height=h.coerceAtLeast(1);glViewport(0,0,width,height)}
    override fun onDrawFrame(gl:GL10?) {
        if(failedOnce || program==0) return
        val aspect=width.toFloat()/height
        Matrix.orthoM(projection,0,-1.2f*max(1f,aspect)/zoom,1.2f*max(1f,aspect)/zoom,-1.2f*max(1f,1/aspect)/zoom,1.2f*max(1f,1/aspect)/zoom,-30f,30f)
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,panX,panY,0f)
        Matrix.rotateM(model,0,pitch,1f,0f,0f);Matrix.rotateM(model,0,yaw,0f,1f,0f)
        if(focusCenter!=null) {val c=focusCenter!!;Matrix.translateM(model,0,-c[0],-c[1],-c[2])}
        Matrix.multiplyMM(mvp,0,projection,0,model,0)
        glUseProgram(program);glUniformMatrix4fv(mvpUniform,1,false,mvp,0);glUniformMatrix4fv(modelUniform,1,false,model,0)
        val tap=pick;pick=null
        if(tap!=null) {
            glClearColor(0f,0f,0f,1f);glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glEnable(GL_DEPTH_TEST);glDepthMask(true);glDisable(GL_BLEND);glDisable(GL_DITHER)
            meshes.filter { !isolate || selected==null || it.info.id==selected }.forEach { drawMesh(it,true) }
            val pixel=ByteBuffer.allocateDirect(4)
            glReadPixels(tap.first.coerceIn(0,width-1),(height-1-tap.second).coerceIn(0,height-1),1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel)
            val id=pixel.get(0).toInt() and 255
            if(atlas.meshes.any { it.id==id }) picked(id)
            glEnable(GL_DITHER)
        }
        glClearColor(.045f,.080f,.109f,1f);glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST);glDepthMask(true)
        glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA)
        val visible=meshes.filter { !isolate || selected==null || it.info.id==selected }
        // Back-to-front order approximates transparency at the level of whole compartments.
        val sorted=visible.sortedBy { mesh ->
            val c=mesh.info.center
            model[2]*c[0]+model[6]*c[1]+model[10]*c[2]
        }
        glDepthMask(false)
        sorted.filter { it.info.id!=selected }.forEach { drawMesh(it,false) }
        if(previewVisible && !isolate) drawLines(preview,previewVertices,true,floatArrayOf(.7f,.8f,.8f, .52f))
        sorted.firstOrNull { it.info.id==selected }?.let { drawMesh(it,false) }
        // Individual neuron is shown over translucent context, with real branches only.
        glDisable(GL_DEPTH_TEST)
        drawLines(skeleton,skeletonVertices,false,floatArrayOf(1f,.87f,.51f,1f))
        glDepthMask(true)
    }
    private fun drawMesh(mesh:Mesh,picking:Boolean) {
        val chosen=mesh.info.id==selected
        val alpha=if(chosen) .98f else if(selected!=null) opacity*.21f else if(previewVisible) opacity*.10f else opacity
        if(picking) glUniform4f(colorUniform,mesh.info.id/255f,0f,0f,1f)
        else glUniform4f(colorUniform,mesh.color[0],mesh.color[1],mesh.color[2],alpha)
        glUniform1i(modeUniform,if(picking) 2 else 0)
        mesh.vertices.position(0);glEnableVertexAttribArray(position);glVertexAttribPointer(position,3,GL_FLOAT,false,24,mesh.vertices)
        mesh.vertices.position(3);glEnableVertexAttribArray(normal);glVertexAttribPointer(normal,3,GL_FLOAT,false,24,mesh.vertices)
        glDisableVertexAttribArray(colorAttribute);glVertexAttrib3f(colorAttribute,1f,1f,1f)
        glDrawArrays(GL_TRIANGLES,0,mesh.count)
    }
    private fun drawLines(vertices:FloatBuffer?,count:Int,colors:Boolean,color:FloatArray) {
        if(vertices==null || count==0) return
        glUniform1i(modeUniform,if(colors) 1 else 2);glUniform4fv(colorUniform,1,color,0)
        vertices.position(0);glEnableVertexAttribArray(position);glVertexAttribPointer(position,3,GL_FLOAT,false,if(colors)24 else 12,vertices)
        glDisableVertexAttribArray(normal);glVertexAttrib3f(normal,0f,0f,1f)
        if(colors) {vertices.position(3);glEnableVertexAttribArray(colorAttribute);glVertexAttribPointer(colorAttribute,3,GL_FLOAT,false,24,vertices)}
        else glDisableVertexAttribArray(colorAttribute)
        glLineWidth(if(colors)1f else 2f);glDrawArrays(GL_LINES,0,count)
    }
    fun setSkeleton(value:Skeleton?) {
        if(skeletonId==value?.id) return
        skeletonId=value?.id;skeleton=value?.let { buffer(it.lines) };skeletonVertices=(value?.lines?.size ?: 0)/3
        neuronBounds=value?.let { Geometry.bounds(it.lines) }
    }
    private var focusCenter:FloatArray?=null
    fun setPose(version:Int,focus:Int) {
        if(poseVersion!=version) { poseVersion=version;yaw=-6f;pitch=85f;zoom=1.25f;panX=0f;panY=0f;focusCenter=null }
        if(focusVersion!=focus) {
            focusVersion=focus
            if(focus>0) {
                val chosen=atlas.meshes.firstOrNull { it.id==selected }
                val b=if(neuronBounds!=null) neuronBounds else chosen?.let {
                    val c=FloatArray(3) { axis -> ((it.min[axis]+it.max[axis])/2-Geometry.origin[axis])/Geometry.unit }
                    val radius=sqrt((0..2).sumOf { axis -> ((it.max[axis]-it.min[axis])/2/Geometry.unit).toDouble().pow(2) }).toFloat()
                    c to radius
                }
                b?.let { focusCenter=it.first;zoom=(.9f/it.second).coerceIn(.35f,18f);panX=0f;panY=0f }
            }
        }
    }
    private fun createProgram():Int {
        fun shader(type:Int,source:String):Int { val id=glCreateShader(type);glShaderSource(id,source);glCompileShader(id)
            val status=IntArray(1);glGetShaderiv(id,GL_COMPILE_STATUS,status,0)
            check(status[0]!=0) { glGetShaderInfoLog(id) };return id }
        val vertex=shader(GL_VERTEX_SHADER,"""
            uniform mat4 uMvp; uniform mat4 uModel;
            attribute vec3 aPosition; attribute vec3 aNormal; attribute vec3 aColor;
            varying vec3 vNormal; varying vec3 vColor;
            void main(){ gl_Position=uMvp*vec4(aPosition,1.0);vNormal=mat3(uModel)*aNormal;vColor=aColor; }
        """.trimIndent())
        val fragment=shader(GL_FRAGMENT_SHADER,"""
            precision mediump float;
            uniform vec4 uColor; uniform int uMode;
            varying vec3 vNormal; varying vec3 vColor;
            void main(){
                if(uMode==2) {gl_FragColor=uColor;}
                else if(uMode==1) {gl_FragColor=vec4(vColor,uColor.a);}
                else {vec3 n=normalize(vNormal);float light=0.43+0.57*abs(dot(n,normalize(vec3(-0.4,0.7,1.0))));
                    gl_FragColor=vec4(uColor.rgb*light,uColor.a);}
            }
        """.trimIndent())
        val p=glCreateProgram();glAttachShader(p,vertex);glAttachShader(p,fragment);glLinkProgram(p)
        val status=IntArray(1);glGetProgramiv(p,GL_LINK_STATUS,status,0);check(status[0]!=0) {glGetProgramInfoLog(p)}
        glDeleteShader(vertex);glDeleteShader(fragment);return p
    }
}
