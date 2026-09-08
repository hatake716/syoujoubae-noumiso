package io.github.hatake716.syoujoubae

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import java.nio.ByteBuffer
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
class BrainView(context:Context, val atlas:AtlasRepository, camera:CameraState = CameraState()):GLSurfaceView(context) {
    var onPick:(Int)->Unit = {}
    var onReady:()->Unit = {}
    var onFailure:(String)->Unit = {}
    val brain = BrainRenderer(context, atlas, camera, { id -> post { onPick(id) } }, { post { onReady() } }, { message -> post { onFailure(message) } })
    private val refine = Runnable {brain.interacting=false;requestRender()}
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
            MotionEvent.ACTION_DOWN -> {removeCallbacks(refine);brain.interacting=true;downX=x;downY=y;lastX=x;lastY=y;downTime=e.eventTime;moved=false;parent.requestDisallowInterceptTouchEvent(true)}
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
                postDelayed(refine,160)
            }
            MotionEvent.ACTION_CANCEL -> {parent.requestDisallowInterceptTouchEvent(false);postDelayed(refine,160)}
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    fun update(selected:Int?, isolate:Boolean, preview:Boolean, opacity:Float, skeleton:Skeleton?, pose:Int, focus:Int, highDetail:Boolean, resizing:Boolean) {
        brain.highDetail=highDetail;brain.resizing=resizing
        brain.selected=selected;brain.isolate=isolate;brain.previewVisible=preview;brain.opacity=opacity
        queueEvent { brain.setSkeleton(skeleton);brain.setPose(pose,focus);requestRender() }
    }
}

class BrainRenderer(private val context:Context,private val atlas:AtlasRepository,private val camera:CameraState,
                    private val picked:(Int)->Unit,private val ready:()->Unit,private val failed:(String)->Unit):GLSurfaceView.Renderer {
    var yaw:Float get()=camera.yaw;set(value){camera.yaw=value}
    var pitch:Float get()=camera.pitch;set(value){camera.pitch=value}
    var zoom:Float get()=camera.zoom;set(value){camera.zoom=value}
    var panX:Float get()=camera.panX;set(value){camera.panX=value}
    var panY:Float get()=camera.panY;set(value){camera.panY=value}
    @Volatile var selected:Int?=null;@Volatile var isolate=false
    @Volatile var previewVisible=false;@Volatile var opacity=.82f
    @Volatile var highDetail=false;@Volatile var interacting=false;@Volatile var resizing=false
    @Volatile var pick:Pair<Int,Int>?=null
    @Volatile var fullDetailReady=false;private set
    @Volatile var overviewEdges=0;private set
    @Volatile var gpuBytes=0L;private set
    @Volatile var lastTriangles=0;private set
    @Volatile var lastFrameMillis=0f;private set
    @Volatile var frameCount=0L;private set
    private var program=0;private var width=1;private var height=1;private var viewportHeight=1
    private var position=0;private var normal=0
    private var mvpUniform=0;private var modelUniform=0;private var colorUniform=0;private var modeUniform=0
    private val projection=FloatArray(16);private val model=FloatArray(16);private val mvp=FloatArray(16)
    private val meshes=mutableListOf<Mesh>()
    private var gpu:GpuGeometry?=null
    private val overview=mutableListOf<Pair<List<GpuBatch>,FloatArray>>()
    private var neuronGpu=emptyList<GpuBatch>();private var skeletonId:Long?=null
    private var currentSkeleton:Skeleton?=null
    private var blockedDetail=false
    private var reportedScene=""
    private data class Mesh(val info:MeshInfo,val interactive:List<GpuBatch>,val color:FloatArray,var full:List<GpuBatch>?=null)

    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) {
        // GL context loss invalidates names; reload from assets, without keeping CPU copies.
        meshes.clear();overview.clear();neuronGpu=emptyList();skeletonId=null
        fullDetailReady=false;overviewEdges=0;gpuBytes=0;blockedDetail=false
        try {
            program=createProgram();gpu=GpuGeometry(context.assets)
            position=glGetAttribLocation(program,"aPosition");normal=glGetAttribLocation(program,"aNormal")
            mvpUniform=glGetUniformLocation(program,"uMvp");modelUniform=glGetUniformLocation(program,"uModel")
            colorUniform=glGetUniformLocation(program,"uColor");modeUniform=glGetUniformLocation(program,"uMode")
            for(info in atlas.meshes) {
                val batches=gpu!!.mesh("atlas/meshes/interactive/${info.id}.bin")
                gpuBytes+=batches.sumOf {it.bytes.toLong()}
                val group=atlas.regions.firstOrNull {it.key==info.key}?.group ?: "統合領域"
                meshes.add(Mesh(info,batches,groupColor(group)))
            }
            currentSkeleton?.let {setSkeleton(it)}
            glDisable(GL_CULL_FACE)
            ready()
        } catch(e:Exception) {program=0;failed("3Dモデルを読み込めませんでした：${e.localizedMessage}")}
    }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int) {
        width=w.coerceAtLeast(1);height=h.coerceAtLeast(1)
        val density=context.resources.displayMetrics.density
        val top=minOf((58*density).toInt(),height/4)
        val bottom=minOf((24*density).toInt(),height/8)
        viewportHeight=(height-top-bottom).coerceAtLeast(1)
        glViewport(0,bottom,width,viewportHeight)
    }
    private fun full(mesh:Mesh):List<GpuBatch> {
        mesh.full?.let {return it}
        if(blockedDetail)return mesh.interactive
        try {
            val data=gpu!!.mesh("atlas/meshes/${mesh.info.id}.bin")
            val bytes=data.sumOf {it.bytes.toLong()}
            // Geometry budget, separate from Java heap / database / driver allocations.
            if(gpuBytes+bytes>256L*1024*1024) {gpu!!.delete(data);blockedDetail=true;return mesh.interactive}
            mesh.full=data;gpuBytes+=bytes;return data
        } catch(e:Exception) {blockedDetail=true;android.util.Log.w("BrainAtlas","Using interaction geometry after full-detail upload failure",e);return mesh.interactive}
    }
    private fun loadOverview() {
        if(overview.isNotEmpty())return
        val staging=gpu ?: return
        try {
            for(cell in atlas.overviewCells) {
                val values=Geometry.skeleton(context.assets.open("atlas/skeletons/${cell.id}.bin").use {it.readBytes()})
                val batches=staging.lines(values)
                overview.add(batches to when(cell.group) {
                    "Kenyon_Cell" -> floatArrayOf(.9f,.65f,.55f)
                    "CX" -> floatArrayOf(.69f,.62f,.88f)
                    "olfactory","ALPN" -> floatArrayOf(.89f,.7f,.34f)
                    "ol_intrinsic" -> floatArrayOf(.35f,.68f,.68f)
                    else -> floatArrayOf(.52f,.77f,.64f)
                })
                gpuBytes+=batches.sumOf {it.bytes.toLong()};overviewEdges+=values.size/6
            }
        } catch(e:Exception) {
            for((batches,_) in overview) {staging.delete(batches);gpuBytes-=batches.sumOf {it.bytes.toLong()}}
            overview.clear();overviewEdges=0;previewVisible=false
            failed("神経の概観を読み込めませんでした。表示設定から再試行できます。")
        }
    }
    override fun onDrawFrame(gl:GL10?) {
        if(program==0)return
        val started=System.nanoTime()
        val moving=interacting || resizing
        val visible=meshes.filter {!isolate || selected==null || it.info.id==selected}
        // Preserve every original ROI face at rest. Context for a neuron uses the companion mesh.
        if(!moving && highDetail && !previewVisible && currentSkeleton==null) visible.forEach {full(it)}
        if(!moving) visible.firstOrNull {it.info.id==selected}?.let {full(it)}
        fullDetailReady=meshes.all {it.full!=null}
        if(previewVisible && !isolate)loadOverview()
        val aspect=width.toFloat()/viewportHeight
        val halfHeight=max(camera.fitHalfHeight*1.10f,camera.fitHalfWidth*1.08f/aspect)*1.25f/zoom
        Matrix.orthoM(projection,0,-halfHeight*aspect,halfHeight*aspect,-halfHeight,halfHeight,-30f,30f)
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,panX,panY,0f)
        Matrix.rotateM(model,0,pitch,1f,0f,0f);Matrix.rotateM(model,0,yaw,0f,1f,0f)
        Matrix.translateM(model,0,-camera.center[0],-camera.center[1],-camera.center[2])
        Matrix.multiplyMM(mvp,0,projection,0,model,0)
        glUseProgram(program);glUniformMatrix4fv(mvpUniform,1,false,mvp,0);glUniformMatrix4fv(modelUniform,1,false,model,0)
        lastTriangles=0
        val tap=pick;pick=null
        if(tap!=null) {
            glClearColor(0f,0f,0f,1f);glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glEnable(GL_DEPTH_TEST);glDepthMask(true);glDisable(GL_BLEND);glDisable(GL_DITHER)
            visible.forEach {drawMesh(it,true,moving)}
            val pixel=ByteBuffer.allocateDirect(4)
            glReadPixels(tap.first.coerceIn(0,width-1),(height-1-tap.second).coerceIn(0,height-1),1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel)
            val id=pixel.get(0).toInt() and 255
            if(atlas.meshes.any {it.id==id})picked(id)
            glEnable(GL_DITHER)
        }
        glClearColor(.045f,.080f,.109f,1f);glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST);glDepthMask(false)
        glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA)
        val sorted=visible.sortedBy {mesh -> val c=mesh.info.center;model[2]*c[0]+model[6]*c[1]+model[10]*c[2]}
        sorted.filter {it.info.id!=selected}.forEach {drawMesh(it,false,moving)}
        if(previewVisible && !isolate) for((i,entry) in overview.withIndex()) {
            // During the gesture only: skip whole cells, never sever branches within a cell.
            if(!moving || i%4==0)drawLines(entry.first,entry.second,if(moving).55f else .32f,1f)
        }
        sorted.firstOrNull {it.info.id==selected}?.let {drawMesh(it,false,moving)}
        glDisable(GL_DEPTH_TEST)
        drawLines(neuronGpu,floatArrayOf(1f,.87f,.51f),1f,2f)
        glDepthMask(true);glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0)
        lastFrameMillis=(System.nanoTime()-started)/1_000_000f;frameCount++
        if(!moving) {
            val scene="triangles=$lastTriangles overviewEdges=${if(previewVisible && !isolate)overviewEdges else 0} gpuBytes=$gpuBytes"
            if(scene!=reportedScene) {android.util.Log.i("BrainAtlas",scene);reportedScene=scene}
        }
    }
    private fun drawMesh(mesh:Mesh,picking:Boolean,moving:Boolean) {
        val chosen=mesh.info.id==selected
        val alpha=if(chosen).96f else if(selected!=null || currentSkeleton!=null)opacity*.15f else if(previewVisible)opacity*.08f else opacity
        if(picking)glUniform4f(colorUniform,mesh.info.id/255f,0f,0f,1f)
        else glUniform4f(colorUniform,mesh.color[0],mesh.color[1],mesh.color[2],alpha)
        glUniform1i(modeUniform,if(picking)1 else 0)
        val original=!moving && (chosen || (highDetail && !previewVisible && currentSkeleton==null))
        val batches=if(original)mesh.full ?: mesh.interactive else mesh.interactive
        for(batch in batches) {
            glBindBuffer(GL_ARRAY_BUFFER,batch.vertex);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,batch.index)
            glEnableVertexAttribArray(position);glVertexAttribPointer(position,3,GL_FLOAT,false,16,0)
            glEnableVertexAttribArray(normal);glVertexAttribPointer(normal,3,GL_BYTE,true,16,12)
            glDrawElements(GL_TRIANGLES,batch.count,GL_UNSIGNED_SHORT,0)
            if(!picking)lastTriangles+=batch.count/3
        }
    }
    private fun drawLines(batches:List<GpuBatch>,color:FloatArray,alpha:Float,width:Float) {
        glUniform1i(modeUniform,1);glUniform4f(colorUniform,color[0],color[1],color[2],alpha)
        glDisableVertexAttribArray(normal);glVertexAttrib3f(normal,0f,0f,1f)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);glLineWidth(width)
        for(batch in batches) {
            glBindBuffer(GL_ARRAY_BUFFER,batch.vertex)
            glEnableVertexAttribArray(position);glVertexAttribPointer(position,3,GL_FLOAT,false,12,0)
            glDrawArrays(GL_LINES,0,batch.count)
        }
    }
    fun setSkeleton(value:Skeleton?) {
        currentSkeleton=value
        if(skeletonId==value?.id || gpu==null)return
        val upload=gpu!!;upload.delete(neuronGpu);gpuBytes-=neuronGpu.sumOf {it.bytes.toLong()}
        neuronGpu=emptyList();skeletonId=null
        if(value!=null) {
            neuronGpu=upload.lines(value.lines);gpuBytes+=neuronGpu.sumOf {it.bytes.toLong()};skeletonId=value.id
        }
    }
    private fun fit(boxes:List<Pair<FloatArray,FloatArray>>) {
        val lo=FloatArray(3){a->boxes.minOf {it.first[a]}}
        val hi=FloatArray(3){a->boxes.maxOf {it.second[a]}}
        camera.center=FloatArray(3){(lo[it]+hi[it])/2}
        val rotation=FloatArray(16);Matrix.setIdentityM(rotation,0)
        Matrix.rotateM(rotation,0,pitch,1f,0f,0f);Matrix.rotateM(rotation,0,yaw,0f,1f,0f)
        var halfX=.02f;var halfY=.02f
        for((min,max) in boxes)for(corner in 0..7) {
            val p=FloatArray(3){a->(if(corner and (1 shl a)==0)min[a] else max[a])-camera.center[a]}
            halfX=maxOf(halfX,abs(rotation[0]*p[0]+rotation[4]*p[1]+rotation[8]*p[2]))
            halfY=maxOf(halfY,abs(rotation[1]*p[0]+rotation[5]*p[1]+rotation[9]*p[2]))
        }
        camera.fitHalfWidth=halfX;camera.fitHalfHeight=halfY
        zoom=1.25f;panX=0f;panY=0f
    }
    private fun box(info:MeshInfo)=FloatArray(3){(info.min[it]-Geometry.origin[it])/Geometry.unit} to FloatArray(3){(info.max[it]-Geometry.origin[it])/Geometry.unit}
    fun setPose(version:Int,focus:Int) {
        if(camera.poseVersion!=version) {
            camera.poseVersion=version;yaw=-6f;pitch=85f;fit(atlas.meshes.map {box(it)})
        }
        if(camera.focusVersion!=focus) {
            camera.focusVersion=focus
            if(focus>0) {
                val lines=currentSkeleton?.lines
                if(lines!=null && lines.isNotEmpty()) {
                    val lo=FloatArray(3){Float.POSITIVE_INFINITY};val hi=FloatArray(3){Float.NEGATIVE_INFINITY}
                    for(i in lines.indices step 3)for(a in 0..2){lo[a]=minOf(lo[a],lines[i+a]);hi[a]=maxOf(hi[a],lines[i+a])}
                    fit(listOf(lo to hi))
                } else atlas.meshes.firstOrNull {it.id==selected}?.let {fit(listOf(box(it)))}
            }
        }
    }
    private fun createProgram():Int {
        fun shader(type:Int,source:String):Int {
            val id=glCreateShader(type);glShaderSource(id,source);glCompileShader(id)
            val status=IntArray(1);glGetShaderiv(id,GL_COMPILE_STATUS,status,0)
            check(status[0]!=0){glGetShaderInfoLog(id)};return id
        }
        val vertex=shader(GL_VERTEX_SHADER,"""
            uniform mat4 uMvp;uniform mat4 uModel;
            attribute vec3 aPosition;attribute vec3 aNormal;varying vec3 vNormal;
            void main(){gl_Position=uMvp*vec4(aPosition,1.0);vNormal=mat3(uModel)*aNormal;}
        """.trimIndent())
        val fragment=shader(GL_FRAGMENT_SHADER,"""
            precision mediump float;uniform vec4 uColor;uniform int uMode;varying vec3 vNormal;
            void main(){
                if(uMode==1){gl_FragColor=uColor;}
                else{vec3 n=normalize(vNormal);float light=0.43+0.57*abs(dot(n,normalize(vec3(-0.4,0.7,1.0))));gl_FragColor=vec4(uColor.rgb*light,uColor.a);}
            }
        """.trimIndent())
        val p=glCreateProgram();glAttachShader(p,vertex);glAttachShader(p,fragment);glLinkProgram(p)
        val status=IntArray(1);glGetProgramiv(p,GL_LINK_STATUS,status,0);check(status[0]!=0){glGetProgramInfoLog(p)}
        glDeleteShader(vertex);glDeleteShader(fragment);return p
    }
}
