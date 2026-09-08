package io.github.hatake716.syoujoubae

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.geometry.Offset
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtlasInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun ready() {compose.waitUntil(90000) {compose.onAllNodesWithTag("region-search").fetchSemanticsNodes().isNotEmpty()}}
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
