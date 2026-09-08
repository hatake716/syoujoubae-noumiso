package io.github.hatake716.syoujoubae

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.geometry.Offset
import android.view.View
import android.view.ViewGroup
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.opengl.GLES20
import android.os.Debug
import org.json.JSONObject
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtlasInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun ready() {compose.waitUntil(90000) {compose.onAllNodesWithTag("region-search").fetchSemanticsNodes().isNotEmpty()}}
    private fun native():BrainView {
        fun find(view:View):BrainView? {
            if(view is BrainView)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
            return null
        }
        return compose.runOnIdle {find(compose.activity.window.decorView)!!}
    }
    private fun model()=ViewModelProvider(compose.activity)[AtlasViewModel::class.java]
    private fun rotate(orientation:Int) {
        compose.runOnIdle {compose.activity.requestedOrientation=orientation}
        val expected=if(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.waitUntil(30000){compose.activity.resources.configuration.orientation==expected && compose.onAllNodesWithTag("pane-divider").fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
    }
    @Test fun panesResizeInBothOrientationsAndRestoreSavedRatiosAndCamera() {
        ready();rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        compose.runOnIdle {model().resizePane(false,.5f);model().viewerExpanded=false}
        val before=compose.onNodeWithTag("brain-view").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("pane-divider").performTouchInput {swipe(center,center+Offset(0f,190f),550)}
        compose.waitForIdle()
        val resized=compose.onNodeWithTag("brain-view").fetchSemanticsNode().boundsInRoot.height
        assertTrue(resized>before+100f)
        val portrait=compose.runOnIdle {model().portraitRatio}
        compose.onNodeWithContentDescription("3Dを最大化").performClick()
        assertTrue(compose.onNodeWithTag("brain-view").fetchSemanticsNode().boundsInRoot.height>resized+100f)
        compose.onNodeWithContentDescription("解説との分割に戻す").performClick()
        assertEquals(resized,compose.onNodeWithTag("brain-view").fetchSemanticsNode().boundsInRoot.height,3f)
        compose.onNodeWithTag("brain-view").performTouchInput {swipe(center,center+Offset(65f,10f),400)}
        val yaw=native().brain.yaw
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        compose.runOnIdle {model().resizePane(true,.5f)}
        val oldWidth=compose.onNodeWithTag("brain-view").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("pane-divider").performTouchInput {swipe(center,center+Offset(230f,0f),500)}
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("brain-view").fetchSemanticsNode().boundsInRoot.width>oldWidth+140f)
        val landscape=compose.runOnIdle {model().landscapeRatio}
        assertEquals(yaw,native().brain.yaw,.01f)
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertEquals(portrait,compose.runOnIdle {model().portraitRatio},.001f)
        assertEquals(yaw,native().brain.yaw,.01f)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        val prefs=compose.activity.getSharedPreferences("atlas-preferences",0)
        assertEquals(portrait,prefs.getFloat("portrait-ratio",0f),.001f)
        assertEquals(landscape,prefs.getFloat("landscape-ratio",0f),.001f)
    }
    @Test fun fullSourceMeshesAndAllOverviewBranchesFitTheGeometryBudget() {
        ready()
        compose.runOnIdle {model().changeDetail(true);model().preview=false;model().viewerExpanded=true}
        val view=native();val renderer=view.brain
        compose.waitUntil(120000){renderer.fullDetailReady && renderer.lastTriangles==7712880}
        fun frameTimes():List<Double> {
            val done=CountDownLatch(1);val samples=mutableListOf<Double>()
            view.queueEvent {
                repeat(6) {
                    GLES20.glFinish();val begin=System.nanoTime()
                    renderer.onDrawFrame(null);GLES20.glFinish()
                    samples.add((System.nanoTime()-begin)/1_000_000.0)
                }
                done.countDown()
            }
            assertTrue(done.await(90,TimeUnit.SECONDS));return samples
        }
        val fullTimes=frameTimes()
        val fullPss=Debug.getPss();val fullBytes=renderer.gpuBytes
        assertTrue(fullBytes<256L*1024*1024)
        compose.runOnIdle {model().preview=true}
        compose.waitUntil(120000){renderer.overviewEdges==2237417}
        val overviewTimes=frameTimes()
        assertTrue(renderer.gpuBytes<256L*1024*1024)
        val report=JSONObject().put("fullTriangles",7712880).put("overviewEdges",renderer.overviewEdges)
            .put("fullGpuBytes",fullBytes).put("overviewGpuBytes",renderer.gpuBytes)
            .put("fullPssKiB",fullPss).put("overviewPssKiB",Debug.getPss())
            .put("fullFrameMillis",org.json.JSONArray(fullTimes)).put("overviewFrameMillis",org.json.JSONArray(overviewTimes))
            .put("heapLimitBytes",Runtime.getRuntime().maxMemory()).put("measurement","Emulator debug, glFinish-inclusive frame time; not physical GPU FPS")
        File(compose.activity.filesDir,"render-validation.json").writeText(report.toString(2))
        compose.runOnIdle {model().preview=false;model().viewerExpanded=false}
    }
    @Test fun nativeCameraRespondsToRotationAndTwoFingerZoom() {
        ready()
        fun find(view:View):BrainView? {
            if(view is BrainView)return view
            if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let {return it}
            return null
        }
        val native=compose.runOnIdle {find(compose.activity.window.decorView)!!}
        val beforeYaw=native.brain.yaw
        compose.onNodeWithTag("brain-view").performTouchInput {swipe(center,center+Offset(95f,10f),500)}
        assertNotEquals(beforeYaw,native.brain.yaw)
        val beforeZoom=native.brain.zoom
        compose.onNodeWithTag("brain-view").performTouchInput {
            pinch(start0=center-Offset(180f,0f),end0=center-Offset(350f,0f),
                start1=center+Offset(180f,0f),end1=center+Offset(350f,0f),durationMillis=600)
        }
        assertTrue(native.brain.zoom>beforeZoom)
        compose.onNodeWithContentDescription("視点をリセット").performClick()
        compose.waitUntil(5000) {kotlin.math.abs(native.brain.zoom-1.25f)<.001f}
    }
    @Test fun regionDetailAndBookmarkSurviveActivityRecreation() {
        ready()
        compose.onNodeWithTag("region-search").performTextInput("触角葉")
        compose.onNodeWithTag("region-AL").performClick()
        compose.onNodeWithTag("region-detail").assertExists()
        val wasSaved=compose.activity.getSharedPreferences("atlas-preferences",0).getStringSet("regions",emptySet())!!.contains("AL")
        compose.onNodeWithContentDescription("部位を保存").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30000) {compose.onAllNodesWithTag("region-search").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("region-detail").fetchSemanticsNodes().isNotEmpty()}
        val prefs=compose.activity.getSharedPreferences("atlas-preferences",0)
        assertEquals(!wasSaved,prefs.getStringSet("regions", emptySet())!!.contains("AL"))
    }
    @Test fun neuronSearchDisplaysRealOfflineGeometryAndDirectedConnections() {
        ready()
        compose.onNodeWithTag("tab-1").performClick()
        compose.onNodeWithTag("neuron-search").performTextReplacement("10001")
        compose.waitUntil(30000) {compose.onAllNodesWithTag("neuron-10001").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("neuron-10001").performClick()
        compose.waitUntil(30000) {compose.onAllNodesWithText("形態をアプリに同梱 · オフラインで表示").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("neuron-detail").performScrollToNode(hasTestTag("inputs"))
        compose.onNodeWithTag("inputs").performClick()
        compose.onNodeWithText("入力",substring=true).assertExists()
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        AtlasRepository(ctx).use {r ->
            assertEquals(165122,r.count(false))
            assertEquals("DNp01",r.neuron(10001)?.type)
            val c=r.connections(10001)
            assertTrue(c.outputs.isNotEmpty());assertTrue(c.inputs.isNotEmpty())
            val edge=c.outputs.first()
            assertTrue(r.connections(edge.id).inputs.any {it.id==10001L && it.weight==edge.weight})
            val skeleton=r.skeleton(10001)
            assertTrue(skeleton.lines.size>100);assertTrue(skeleton.fromCache)
        }
    }

    @Test fun publicSkeletonDownloadAndCacheMatchTheOfficialByteContract() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        AtlasRepository(ctx).use {r ->
            val id=11503L
            assertFalse(id in r.offlineIds)
            java.io.File(ctx.cacheDir,"skeletons-v1/$id.bin").delete()
            val downloaded=r.skeleton(id)
            assertFalse(downloaded.fromCache)
            assertTrue(downloaded.lines.size>100)
            val cached=r.skeleton(id)
            assertTrue(cached.fromCache)
            assertArrayEquals(downloaded.lines,cached.lines,0f)
        }
    }
}
