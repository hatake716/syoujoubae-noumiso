package io.github.hatake716.syoujoubae

import android.app.Application
import android.app.ActivityManager
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

class AtlasViewModel(app:Application):AndroidViewModel(app) {
    var repo by mutableStateOf<AtlasRepository?>(null);private set
    var startupError by mutableStateOf<String?>(null);private set
    var tab by mutableIntStateOf(0)
    var selectedMesh by mutableStateOf<Int?>(null)
    var group by mutableStateOf("すべて")
    var regionQuery by mutableStateOf("")
    var preview by mutableStateOf(false)
    var isolate by mutableStateOf(false)
    var opacity by mutableFloatStateOf(.82f)
    var pose by mutableIntStateOf(0)
    var focus by mutableIntStateOf(0)
    var query by mutableStateOf("");private set
    var brainOnly by mutableStateOf(true);private set
    var results by mutableStateOf<List<Neuron>>(emptyList());private set
    var searchLoading by mutableStateOf(false);private set
    var hasMore by mutableStateOf(false);private set
    var neuron by mutableStateOf<Neuron?>(null);private set
    var connections by mutableStateOf<Connections?>(null);private set
    var skeleton by mutableStateOf<Skeleton?>(null);private set
    var neuronLoading by mutableStateOf(false);private set
    var neuronError by mutableStateOf<String?>(null);private set
    var connectionError by mutableStateOf<String?>(null);private set
    var brainCount by mutableIntStateOf(0);private set
    var allCount by mutableIntStateOf(0);private set
    var cacheBytes by mutableLongStateOf(0);private set
    var history by mutableStateOf<List<Neuron>>(emptyList());private set
    private val prefs=app.getSharedPreferences("atlas-preferences",0)
    val camera = CameraState()
    var portraitRatio by mutableFloatStateOf(PaneRatio.clamp(prefs.getFloat("portrait-ratio", .60f))); private set
    var landscapeRatio by mutableFloatStateOf(PaneRatio.clamp(prefs.getFloat("landscape-ratio", .64f))); private set
    var viewerExpanded by mutableStateOf(false)
    private val capableDevice = app.getSystemService(ActivityManager::class.java).let { manager ->
        val info = ActivityManager.MemoryInfo(); manager.getMemoryInfo(info)
        !manager.isLowRamDevice && info.totalMem >= 6L * 1024 * 1024 * 1024
    }
    var highDetail by mutableStateOf(prefs.getBoolean("high-detail", capableDevice)); private set
    var resizing by mutableStateOf(false)
    var bookmarks by mutableStateOf(prefs.getStringSet("regions",emptySet())?.toSet() ?: emptySet());private set
    var favoriteNeurons by mutableStateOf(prefs.getStringSet("neurons",emptySet())?.toSet() ?: emptySet());private set
    private var searchJob:Job?=null
    private var neuronJob:Job?=null
    private var startupJob:Job?=null
    init { start() }
    fun paneRatio(wide: Boolean) = if (wide) landscapeRatio else portraitRatio
    fun resizePane(wide: Boolean, ratio: Float) {
        if (wide) landscapeRatio = PaneRatio.clamp(ratio) else portraitRatio = PaneRatio.clamp(ratio)
    }
    fun savePaneRatio(wide: Boolean) { prefs.edit().putFloat(if (wide) "landscape-ratio" else "portrait-ratio", paneRatio(wide)).apply() }
    fun changeDetail(value: Boolean) { highDetail = value; prefs.edit().putBoolean("high-detail", value).apply() }
    fun start() {
        if(startupJob?.isActive==true || repo!=null) return
        startupError=null
        startupJob=viewModelScope.launch {
            try {
                val r=withContext(Dispatchers.IO) {AtlasRepository(getApplication())}
                val counts=withContext(Dispatchers.IO) {Triple(r.count(true),r.count(false),r.cacheSize())}
                brainCount=counts.first;allCount=counts.second;cacheBytes=counts.third;repo=r
                search("")
            } catch(e:Exception) { if(e is CancellationException) throw e;startupError="データを準備できませんでした。空き容量を確認して再試行してください。\n${e.localizedMessage}" }
        }
    }
    fun selectRegion(id:Int?) { selectedMesh=id;isolate=false;pose++;clearNeuron();tab=0 }
    fun bookmark(key:String) {
        bookmarks=if(key in bookmarks) bookmarks-key else bookmarks+key
        prefs.edit().putStringSet("regions",bookmarks).apply()
    }
    fun favorite(id:Long) {
        val key=id.toString();favoriteNeurons=if(key in favoriteNeurons)favoriteNeurons-key else favoriteNeurons+key
        prefs.edit().putStringSet("neurons",favoriteNeurons).apply()
    }
    fun changeBrainFilter(value:Boolean) {brainOnly=value;search(query)}
    fun search(text:String,more:Boolean=false) {
        query=text;searchJob?.cancel()
        searchJob=viewModelScope.launch {
            searchLoading=true
            try {
                if(!more) delay(180)
                val offset=if(more) results.size else 0
                val page=withContext(Dispatchers.IO) {repo?.search(text,brainOnly,offset) ?: emptyList()}
                results=if(more) results+page else page;hasMore=page.size==80
            } finally {searchLoading=false}
        }
    }
    fun openById(id:Long) { viewModelScope.launch {withContext(Dispatchers.IO) {repo?.neuron(id)}?.let {openNeuron(it)} } }
    fun openNeuron(n:Neuron) {
        neuronJob?.cancel();neuron=n;connections=null;skeleton=null;neuronError=null;connectionError=null
        neuronLoading=true;tab=1;selectedMesh=null;isolate=false;pose++
        history=(listOf(n)+history.filter { it.id!=n.id }).take(8)
        neuronJob=viewModelScope.launch {
            supervisorScope {
                val c=launch {
                    try {connections=withContext(Dispatchers.IO) {repo?.connections(n.id)}}
                    catch(e:Exception) {if(e is CancellationException) throw e;connectionError="接続情報を読めませんでした。もう一度開いてください。"}
                }
                try {
                    skeleton=withContext(Dispatchers.IO) {repo?.skeleton(n.id)}
                    focus++
                    cacheBytes=withContext(Dispatchers.IO) {repo?.cacheSize() ?: 0}
                } catch(e:Exception) {if(e is CancellationException) throw e;neuronError=e.localizedMessage ?: "神経形態を読み込めませんでした。通信状態を確認してください。"}
                finally {neuronLoading=false}
                c.join()
            }
        }
    }
    fun clearNeuron() {neuronJob?.cancel();neuron=null;skeleton=null;connections=null;neuronError=null;connectionError=null;neuronLoading=false}
    fun clearCache() {viewModelScope.launch {withContext(Dispatchers.IO) {repo?.clearCache()};cacheBytes=0}}
    override fun onCleared() {repo?.close();super.onCleared()}
}
