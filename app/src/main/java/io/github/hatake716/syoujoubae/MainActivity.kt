package io.github.hatake716.syoujoubae

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

private val Ink=Color(0xFF101A22)
private val Panel=Color(0xFF192630)
private val Mint=Color(0xFFA7E0CB)
private val Paper=Color(0xFFE9EDE9)
private val Muted=Color(0xFFA3B4BD)
private val Warm=Color(0xFFE8B694)
private fun color(group:String)=groupColor(group).let {Color(it[0],it[1],it[2])}
private fun number(n:Long)=String.format(Locale.JAPAN,"%,d",n)

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Mint,onPrimary=Ink,secondary=Warm,background=Ink,surface=Panel,onSurface=Paper,onBackground=Paper,surfaceVariant=Color(0xFF233440),outline=Color(0xFF455660))) {
                AtlasApp(viewModel())
            }
        }
    }
}

@Composable private fun AtlasApp(vm:AtlasViewModel) {
    val r=vm.repo
    val wide=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
    val tabs=listOf("探索" to Icons.Outlined.Public,"神経" to Icons.Outlined.Hub,"ガイド" to Icons.AutoMirrored.Outlined.MenuBook,"情報" to Icons.Outlined.Info)
    BackHandler(vm.viewerExpanded || vm.neuron!=null || vm.selectedMesh!=null || vm.tab!=0) {
        when {vm.viewerExpanded -> vm.viewerExpanded=false;vm.neuron!=null -> {vm.clearNeuron();vm.pose++};vm.selectedMesh!=null -> vm.selectRegion(null);else -> vm.tab=0}
    }
    Scaffold(containerColor=Ink,bottomBar={
        if(!wide) NavigationBar(containerColor=Ink,tonalElevation=0.dp) {
            tabs.forEachIndexed { i,(title,icon) ->
                NavigationBarItem(selected=vm.tab==i,onClick={vm.tab=i},icon={Icon(icon,title)},label={Text(title)},modifier=Modifier.testTag("tab-$i"),
                    colors=NavigationBarItemDefaults.colors(selectedIconColor=Mint,selectedTextColor=Mint,indicatorColor=Panel))
            }
        }
    }) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if(wide) NavigationRail(containerColor=Ink,windowInsets=WindowInsets(0,0,0,0),modifier=Modifier.fillMaxHeight().width(72.dp)) {
                tabs.forEachIndexed { i,(title,icon) ->
                    NavigationRailItem(selected=vm.tab==i,onClick={vm.tab=i},icon={Icon(icon,title,Modifier.size(22.dp))},label={Text(title,fontSize=10.sp,maxLines=1,softWrap=false)},modifier=Modifier.testTag("tab-$i"),
                        colors=NavigationRailItemDefaults.colors(selectedIconColor=Mint,selectedTextColor=Mint,indicatorColor=Panel))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if(!vm.viewerExpanded || vm.tab>=2) Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=if(wide)4.dp else 9.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if(!wide) Text("DROSOPHILA  /  BRAIN ATLAS",color=Mint,fontSize=10.sp,letterSpacing=2.sp,fontWeight=FontWeight.Medium)
                        Text("ショウジョウバエの脳",fontSize=if(wide)18.sp else 23.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.7).sp)
                    }
                    Text("♂  v${BuildConfig.VERSION_NAME}",color=Muted,fontFamily=FontFamily.Monospace,fontSize=11.sp)
                }
                if(r==null) {
                    Box(Modifier.fillMaxSize().padding(28.dp),contentAlignment=Alignment.Center) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
                            if(vm.startupError==null) {CircularProgressIndicator(color=Mint);Text("MaleCNSの地図を準備しています",fontSize=18.sp);Text("初回は全神経の接続データを端末に展開します。",color=Muted)}
                            else {Text(vm.startupError!!);Button(onClick=vm::start) {Text("再試行")}}
                        }
                    }
                } else if(vm.tab<2) ResizableAtlas(vm,r,wide,Modifier.weight(1f))
                else if(vm.tab==2) Guide(vm,r) else About(vm,r)
            }
        }
    }
}

@Composable private fun ResizableAtlas(vm:AtlasViewModel,r:AtlasRepository,wide:Boolean,modifier:Modifier) {
    DisposableEffect(wide) {
        onDispose { vm.resizing=false; vm.savePaneRatio(wide) }
    }
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        val density=LocalDensity.current
        val keyboardOpen=WindowInsets.ime.getBottom(density)>0
        val thickness=if(wide)32.dp else 36.dp
        val dividerPx=with(density){thickness.roundToPx()}
        val available=with(density){(if(wide)maxWidth else maxHeight).toPx()}-dividerPx
        val ratio=vm.paneRatio(wide)
        fun change(value:Float) {vm.resizePane(wide,value);vm.savePaneRatio(wide)}
        Layout(modifier=Modifier.fillMaxSize(),content={
            Viewer(vm,r,Modifier.fillMaxSize(),wide)
            Box(Modifier.fillMaxSize().background(Panel).testTag("pane-divider")
                .semantics {
                    contentDescription=if(wide)"3Dと解説の境界。左右にドラッグして調整" else "3Dと解説の境界。上下にドラッグして調整"
                    stateDescription="3D ${(ratio*100).toInt()}パーセント"
                    progressBarRangeInfo=ProgressBarRangeInfo(ratio,PaneRatio.MIN..PaneRatio.MAX)
                    setProgress {change(it);true}
                    customActions=listOf(CustomAccessibilityAction("3Dを広げる"){change(ratio+.1f);true},CustomAccessibilityAction("解説を広げる"){change(ratio-.1f);true},CustomAccessibilityAction("均等にする"){change(.5f);true})
                }
                .draggable(rememberDraggableState {delta->vm.resizePane(wide,PaneRatio.afterDrag(vm.paneRatio(wide),delta,available))},
                    orientation=if(wide)Orientation.Horizontal else Orientation.Vertical,
                    onDragStarted={vm.resizing=true},onDragStopped={vm.resizing=false;vm.savePaneRatio(wide)}),
                contentAlignment=Alignment.Center) {
                if(wide) Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.DragIndicator,null,tint=Mint,modifier=Modifier.size(20.dp))
                    Box(Modifier.width(4.dp).height(38.dp).background(Mint.copy(alpha=.5f),CircleShape))
                } else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(34.dp).height(4.dp).background(Mint.copy(alpha=.65f),CircleShape))
                    Text("ドラッグして広さを調整",fontSize=10.sp,color=Mint)
                    Text("3D ${(ratio*100).toInt()}%",fontSize=10.sp,color=Muted)
                }
            }
            Box(Modifier.fillMaxSize().clipToBounds().testTag("explanation-pane")) {if(vm.tab==0)Regions(vm,r) else Neurons(vm,r)}
        }) {children,constraints ->
            val w=constraints.maxWidth;val h=constraints.maxHeight
            val hidden=keyboardOpen && !vm.viewerExpanded
            val divider=if(hidden || vm.viewerExpanded)0 else dividerPx
            val size=if(hidden)0 else if(vm.viewerExpanded)(if(wide)w else h) else (((if(wide)w else h)-divider)*ratio).toInt()
            val viewer=children[0].measure(Constraints.fixed(if(wide)size else w,if(wide)h else size))
            val handle=children[1].measure(Constraints.fixed(if(wide)divider else w,if(wide)h else divider))
            val text=children[2].measure(Constraints.fixed(if(wide)(w-size-divider).coerceAtLeast(0) else w,if(wide)h else (h-size-divider).coerceAtLeast(0)))
            layout(w,h) {
                viewer.placeRelative(0,0)
                handle.placeRelative(if(wide)size else 0,if(wide)0 else size)
                text.placeRelative(if(wide)size+divider else 0,if(wide)0 else size+divider)
            }
        }
    }
}

@Composable private fun Viewer(vm:AtlasViewModel,r:AtlasRepository,modifier:Modifier,wide:Boolean) {
    var view by remember(r) {mutableStateOf<BrainView?>(null)}
    var loaded by remember(r) {mutableStateOf(false)}
    var error by remember(r) {mutableStateOf<String?>(null)}
    var controls by remember {mutableStateOf(false)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,view) {
        val currentView=view
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) currentView?.onResume()
        val observer=LifecycleEventObserver {_,event -> when(event) {Lifecycle.Event.ON_RESUME->currentView?.onResume();Lifecycle.Event.ON_PAUSE->currentView?.onPause();else->Unit}}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);currentView?.onPause()}
    }
    Box(modifier.clipToBounds().background(Color(0xFF0B141C))) {
        AndroidView(factory={ctx -> BrainView(ctx,r,vm.camera).also { v ->
            v.onPick={vm.selectRegion(it)};v.onReady={loaded=true};v.onFailure={error=it};view=v
        }}, update={it.update(vm.selectedMesh,vm.isolate,vm.preview,vm.opacity,vm.skeleton,vm.pose,vm.focus,vm.highDetail,vm.resizing)},modifier=Modifier.fillMaxSize().testTag("brain-view"))
        Column(Modifier.align(Alignment.TopStart).padding(start=12.dp,top=10.dp,end=180.dp)) {
            Label(if(vm.neuron!=null) "NEURON  /  ${vm.neuron!!.id}" else if(vm.selectedMesh!=null) r.meshes.first {it.id==vm.selectedMesh}.name else "MALE CNS  /  全脳")
            Text(if(vm.neuron!=null) "実測の神経形態" else if(vm.preview && !vm.isolate) "${r.previewCount}細胞 · 全ての枝を表示" else "${r.meshes.size} 領域 · 実測モデル",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=4.dp))
        }
        Row(Modifier.align(Alignment.TopEnd).padding(3.dp)) {
            RoundIcon(Icons.Outlined.RestartAlt,"視点をリセット") {vm.pose++}
            RoundIcon(Icons.Outlined.CenterFocusStrong,"選択を拡大") {vm.focus++}
            RoundIcon(Icons.Outlined.Tune,"表示設定") {controls=!controls}
            RoundIcon(if(vm.viewerExpanded)Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,if(vm.viewerExpanded)"解説との分割に戻す" else "3Dを最大化") {vm.viewerExpanded=!vm.viewerExpanded}
        }
        Row(Modifier.align(Alignment.BottomStart).padding(14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("ドラッグで回転",fontSize=10.sp,color=Muted)
            Text("ピンチで拡大",fontSize=10.sp,color=Muted)
            Text("タップで選択",fontSize=10.sp,color=Muted)
        }
        if(!loaded && error==null) CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.Center),color=Mint,strokeWidth=2.dp)
        error?.let {Text(it,color=Warm,modifier=Modifier.align(Alignment.Center).padding(26.dp))}
        if(controls) AlertDialog(onDismissRequest={controls=false},title={Text("表示設定")},confirmButton={TextButton(onClick={controls=false}){Text("閉じる")}},text={
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("3Dと解説の広さ",fontWeight=FontWeight.Bold)
                Slider(vm.paneRatio(wide),{vm.resizePane(wide,it)},onValueChangeFinished={vm.savePaneRatio(wide)},valueRange=PaneRatio.MIN..PaneRatio.MAX,modifier=Modifier.testTag("pane-ratio-slider"))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("3D ${(vm.paneRatio(wide)*100).toInt()}%",fontSize=12.sp);TextButton(onClick={vm.resizePane(wide,.5f);vm.savePaneRatio(wide)}){Text("半分ずつ")}}
                Row(verticalAlignment=Alignment.CenterVertically) {Text("元の領域形状で表示",Modifier.weight(1f),fontSize=13.sp);Switch(vm.highDetail,{vm.changeDetail(it)},modifier=Modifier.testTag("high-detail-switch"))}
                Text("8GB端末では初期設定で有効です。操作中は軽く、指を離すと細部まで表示します。選択した部位は、この設定にかかわらず元の形状で表示します。",fontSize=11.sp,color=Muted)
                Row(verticalAlignment=Alignment.CenterVertically) {Text("神経の概観（523細胞）",Modifier.weight(1f),fontSize=13.sp);Switch(vm.preview,{vm.preview=it},modifier=Modifier.testTag("overview-switch"))}
                Text("静止時は同梱523細胞の全ての枝を表示します。概観の背景となる領域は軽い形状を使います。",fontSize=11.sp,color=Muted)
                Row(verticalAlignment=Alignment.CenterVertically) {Text("選択した領域だけ",Modifier.weight(1f),fontSize=13.sp);Switch(vm.isolate,{vm.isolate=it},enabled=vm.selectedMesh!=null)}
                Text("領域の不透明度",fontSize=12.sp,color=Muted)
                Slider(vm.opacity,{vm.opacity=it},valueRange=.05f..1f)
                Text("透過は領域単位の近似です。重なりは回転して確認できます。",fontSize=10.sp,color=Muted)
            }
        })
    }
}

@Composable private fun Regions(vm:AtlasViewModel,r:AtlasRepository) {
    val mesh=r.meshes.firstOrNull {it.id==vm.selectedMesh}
    val selected=r.regions.firstOrNull {it.key==mesh?.key}
    if(selected!=null && mesh!=null) RegionDetail(vm,r,selected,mesh)
    else Column {
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text("小さな脳を、旅する。",fontSize=22.sp,fontWeight=FontWeight.SemiBold);Text("気になる働きから、部位をひらく",color=Muted,fontSize=12.sp)}
            IconButton(onClick={vm.group=if(vm.group=="保存済み") "すべて" else "保存済み"}) {Icon(Icons.Outlined.Bookmarks,"保存済みの部位",tint=if(vm.group=="保存済み") Mint else Muted)}
        }
        OutlinedTextField(vm.regionQuery,{vm.regionQuery=it},placeholder={Text("部位名・略称・機能で探す",fontSize=13.sp)},leadingIcon={Icon(Icons.Outlined.Search,null,Modifier.size(18.dp))},singleLine=true,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp).testTag("region-search"))
        LazyRow(contentPadding=PaddingValues(horizontal=18.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            items(listOf("すべて","視覚","嗅覚","学習・記憶","方向定位","感覚・運動","統合領域","解剖区画")) {g ->
                FilterChip(vm.group==g,{vm.group=g},label={Text(g,fontSize=12.sp)})
            }
        }
        val q=vm.regionQuery.trim()
        val list=r.regions.filter { region ->
            (vm.group=="すべて" || region.group==vm.group || (vm.group=="保存済み" && region.key in vm.bookmarks)) &&
                (q.isBlank() || listOf(region.name,region.key,region.english,region.tag,region.summary).any {it.contains(q,true)})
        }
        LazyColumn(contentPadding=PaddingValues(start=18.dp,end=18.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(list.isEmpty()) item {Text("該当する部位がありません。検索語や分類を変えてください。",Modifier.padding(14.dp),color=Muted)}
            items(list,key={it.key}) {region ->
                Surface(onClick={vm.selectRegion(r.meshes.first {it.key==region.key}.id)},shape=RoundedCornerShape(15.dp),color=Panel,modifier=Modifier.fillMaxWidth().testTag("region-${region.key}")) {
                    Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(43.dp).clip(RoundedCornerShape(12.dp)).background(color(region.group).copy(alpha=.13f)),contentAlignment=Alignment.Center) {Text(region.key,color=color(region.group),fontSize=if(region.key.length>4) 10.sp else 14.sp,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold)}
                        Column(Modifier.weight(1f).padding(start=14.dp)) {Text(region.name,fontSize=16.sp,fontWeight=FontWeight.Medium);Text(region.tag,color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=3.dp))}
                        Icon(Icons.Outlined.ChevronRight,null,tint=Muted,modifier=Modifier.size(18.dp))
                    }
                }
            }
            item {Text("MaleCNS v1.0 · CC BY 4.0",fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=6.dp))}
        }
    }
}

@Composable private fun RegionDetail(vm:AtlasViewModel,r:AtlasRepository,region:Region,mesh:MeshInfo) {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.testTag("region-detail")) {
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={vm.selectRegion(null)},contentPadding=PaddingValues(0.dp)) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("部位一覧")}
                Spacer(Modifier.weight(1f));IconButton(onClick={vm.bookmark(region.key)}) {Icon(if(region.key in vm.bookmarks)Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd,"部位を保存",tint=Mint)}
            }
            Text(region.group.uppercase(),color=color(region.group),fontSize=11.sp,letterSpacing=1.sp)
            Text(region.name,fontSize=28.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=4.dp))
            Text("${region.english}  ·  ${mesh.name}",fontSize=12.sp,color=Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                r.meshes.filter {it.key==region.key}.forEach {m ->FilterChip(mesh.id==m.id,{vm.selectedMesh=m.id;vm.pose++},label={Text(m.side)})}
                FilterChip(vm.isolate,{vm.isolate=!vm.isolate},label={Text("単独表示")})
            }
        }
        item {Surface(color=color(region.group).copy(alpha=.1f),shape=RoundedCornerShape(16.dp)) {Column(Modifier.padding(18.dp)) {Text(region.tag,color=color(region.group),fontWeight=FontWeight.Bold,fontSize=17.sp);Spacer(Modifier.height(8.dp));Body(region.summary)}}}
        item {ArticleSection("どのように働く？",region.mechanism)}
        item {ArticleSection("3Dで注目したいこと",region.observe)}
        if(region.query.isNotEmpty()) item {OutlinedButton(onClick={vm.tab=1;vm.search(region.query);vm.clearNeuron()},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.Hub,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("関連する神経を探す")};Text("注釈の検索：${region.query}。この領域内の全細胞リストではありません。",fontSize=11.sp,color=Muted)}
        item {ArticleSection("分かっていることと、限界",region.limits)}
        item {Text("解説の出典",fontWeight=FontWeight.Bold,fontSize=16.sp)}
        items(region.sources) {key -> r.references[key]?.let {ReferenceLink(it)}}
        item {Text("機能は成虫の先行研究をもとに説明しています。表示した雄個体の全細胞について機能が実証されたことを意味しません。",fontSize=11.sp,color=Muted,lineHeight=18.sp)}
    }
}

@Composable private fun Neurons(vm:AtlasViewModel,r:AtlasRepository) {
    val n=vm.neuron
    if(n!=null) NeuronDetail(vm,r,n)
    else Column {
        Row(Modifier.padding(start=20.dp,end=12.dp,top=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text("ひとつの神経から。",fontSize=22.sp,fontWeight=FontWeight.SemiBold);Text("${number(if(vm.brainOnly)vm.brainCount.toLong() else vm.allCount.toLong())} 細胞を検索",fontSize=12.sp,color=Muted)}
            Text("追跡済み",fontSize=10.sp,color=Mint)
        }
        OutlinedTextField(vm.query,{vm.search(it)},placeholder={Text("細胞型・ID（例：EPG、10001）",fontSize=12.sp)},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(vm.query.isNotEmpty()) IconButton(onClick={vm.search("")}) {Icon(Icons.Outlined.Close,"検索語を消す")}},singleLine=true,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp).testTag("neuron-search"))
        Row(Modifier.padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically) {Text("脳に関係する分類",fontSize=12.sp,modifier=Modifier.weight(1f));Switch(vm.brainOnly,vm::changeBrainFilter)}
        LazyColumn(contentPadding=PaddingValues(start=18.dp,end=18.dp,bottom=18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(vm.favoriteNeurons.isNotEmpty()) item {
                Text("保存した神経",fontSize=12.sp,color=Mint)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {items(vm.favoriteNeurons.sorted()) {id -> AssistChip(onClick={vm.openById(id.toLong())},label={Text(id,fontSize=11.sp)})}}
            }
            if(vm.query.isBlank()) item {LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {items(listOf("EPG","MBON","ALPN","T4a","DNp01")) {query ->AssistChip(onClick={vm.search(query)},label={Text(query)})}}}
            if(vm.searchLoading) item {LinearProgressIndicator(Modifier.fillMaxWidth(),color=Mint)}
            if(vm.results.isEmpty() && !vm.searchLoading) item {Text("該当する神経がありません。分類の制限を外すか、別の細胞型・IDで検索してください。",color=Muted,modifier=Modifier.padding(vertical=16.dp))}
            items(vm.results,key={it.id}) {cell ->
                Surface(onClick={vm.openNeuron(cell)},color=Panel,shape=RoundedCornerShape(13.dp),modifier=Modifier.fillMaxWidth().testTag("neuron-${cell.id}")) {
                    Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {Text(cell.type,fontWeight=FontWeight.Bold,fontSize=16.sp);Text("${cell.id}  ·  ${cell.side.ifBlank {"側不明"}}  ·  ${cell.superclass}",color=Muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
                        if(cell.id in r.offlineIds) Icon(Icons.Outlined.OfflinePin,"形態を同梱",Modifier.size(18.dp),tint=Mint)
                        Icon(Icons.Outlined.ChevronRight,null,tint=Muted,modifier=Modifier.size(20.dp))
                    }
                }
            }
            if(vm.hasMore) item {TextButton(onClick={vm.search(vm.query,true)},enabled=!vm.searchLoading,modifier=Modifier.fillMaxWidth()) {Text("次の80件を表示")}}
            item {Text("形態未同梱の神経は、初回表示時に公式配布元から取得します（最大64MB）。接続一覧は通信なしで表示できます。",color=Muted,fontSize=11.sp,lineHeight=17.sp)}
        }
    }
}

@Composable private fun NeuronDetail(vm:AtlasViewModel,r:AtlasRepository,n:Neuron) {
    var outgoing by remember(n.id) {mutableStateOf(true)}
    var limit by remember(n.id,outgoing) {mutableIntStateOf(40)}
    val c=vm.connections
    val list=if(outgoing)c?.outputs else c?.inputs
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.testTag("neuron-detail")) {
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={vm.clearNeuron();vm.pose++},contentPadding=PaddingValues(0.dp)){Icon(Icons.AutoMirrored.Outlined.ArrowBack,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("検索に戻る")}
                Spacer(Modifier.weight(1f));IconButton(onClick={vm.favorite(n.id)}) {Icon(if(n.id.toString() in vm.favoriteNeurons)Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd,"神経を保存",tint=Mint)}
            }
            Text(n.type,fontSize=30.sp,fontWeight=FontWeight.SemiBold)
            Text("BODY ID  ${n.id}",color=Mint,fontSize=12.sp,fontFamily=FontFamily.Monospace)
            Text(listOf(n.instance,n.superclass,n.cellClass).filter {it.isNotBlank()}.joinToString("  ·  "),color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
            Text("標本側：${n.side.ifBlank {"不明"}}   再構築注釈：${n.status}",fontSize=11.sp,color=Muted)
            if(n.dimorphism.isNotBlank()) Text("性差の注釈：${n.dimorphism}",fontSize=11.sp,color=Warm)
        }
        if(vm.neuronLoading) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("公式の神経形態を読み込んでいます…",fontSize=12.sp,color=Muted)}
        vm.neuronError?.let {message ->item {Text(message,color=Warm,fontSize=13.sp);OutlinedButton(onClick={vm.openNeuron(n)}) {Text("形態の読み込みを再試行")}}}
        if(vm.skeleton!=null) item {Text(if(n.id in r.offlineIds) "形態をアプリに同梱 · オフラインで表示" else if(vm.skeleton!!.fromCache) "保存済みの形態を表示" else "公式配布元から取得 · 次回からキャッシュを利用",color=Mint,fontSize=11.sp)}
        item {Text("シナプスの接続をたどる",fontSize=18.sp,fontWeight=FontWeight.SemiBold)}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(outgoing,{outgoing=true},label={Text("出力 ${c?.outputs?.size ?: "…"}")},modifier=Modifier.testTag("outputs"))
            FilterChip(!outgoing,{outgoing=false},label={Text("入力 ${c?.inputs?.size ?: "…"}")},modifier=Modifier.testTag("inputs"))
        }
            Text(if(c!=null) "${if(outgoing)"この神経 → 接続先" else "接続元 → この神経"}  /  合計 ${number(if(outgoing)c.outWeight else c.inWeight)} シナプス" else "接続データを読み込んでいます…",fontSize=11.sp,color=Muted)
        }
        vm.connectionError?.let {item {Text(it,color=Warm)}}
        if(list?.isEmpty()==true) item {Text("収録対象の追跡済み細胞間に、この方向の接続はありません。",color=Muted,fontSize=13.sp)}
        items(list?.take(limit) ?: emptyList(),key={it.id}) {edge ->
            Surface(onClick={vm.openById(edge.id)},color=Panel,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().testTag("connection-${edge.id}")) {
                Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text(edge.type,fontWeight=FontWeight.Medium);Text(edge.id.toString(),fontSize=10.sp,color=Muted,fontFamily=FontFamily.Monospace)};Text(number(edge.weight.toLong()),color=Warm,fontWeight=FontWeight.Bold);Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp),tint=Muted)}
            }
        }
        if((list?.size ?: 0)>limit) item {TextButton(onClick={limit+=80},modifier=Modifier.fillMaxWidth()) {Text("接続をさらに表示（残り ${(list?.size ?: 0)-limit}件）")}}
        item {Text("重みは化学シナプスの接続数です。追跡済み細胞間の全接続を収録し、多い順に表示しています。興奮・抑制や機能的な強さを示す値ではありません。",fontSize=11.sp,color=Muted,lineHeight=18.sp)}
        if(vm.history.size>1) item {Text("最近たどった神経",fontSize=12.sp,color=Mint);LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {items(vm.history.filter {it.id!=n.id}) {recent ->AssistChip(onClick={vm.openNeuron(recent)},label={Text("${recent.type} · ${recent.id}",fontSize=11.sp)})}}}
    }
}

@Composable private fun Guide(vm:AtlasViewModel,r:AtlasRepository) {
    LazyColumn(contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {Text("脳の読みかた",fontSize=32.sp,fontWeight=FontWeight.SemiBold);Text("かたちから、つながりへ。",fontSize=16.sp,color=Mint)}
        item {ArticleSection("はじめに","この地図の一つひとつの線は、電子顕微鏡のデータから再構築された神経突起です。色のついた領域は、神経の突起と接点が集まるニューロパイルの外形です。細胞体のすべてを包む頭や脳の表面ではありません。")}
        item {ArticleSection("操作する","1本指のドラッグで回転、2本指のピンチで拡大・縮小、2本指のドラッグで移動できます。領域はモデルのタップでも一覧でも選べます。「選択を拡大」で小さい領域に近づき、「視点をリセット」で全体へ戻れます。")}
        item {ArticleSection("においを記憶する","触角葉で処理されたにおいの情報が、キノコ体の傘へ届きます。Kenyon細胞の軸索が柄と葉へ続き、経験に応じた変化が後の行動に関わります。以下は理解のための領域の順序で、一本の細胞や全経路を表す図ではありません。")
            PathButtons(vm,r,listOf("AL","CA","PED","aL"))}
        item {ArticleSection("自分の向きを知る","視覚情報はメドラから前視結節・バルブを経て、楕円体の方位回路へ届く経路を持ちます。実際には経路の途中で情報が分かれ、他の感覚や自己運動も組み合わされます。")
            PathButtons(vm,r,listOf("ME","AOTU","BU","EB","PB"))}
        item {ArticleSection("神経をたどる","神経タブでEPGなどの細胞型、または10001のような細胞IDを検索します。接続一覧の相手を選ぶと、その相手の形態と入力・出力を開けます。数値は接続された化学シナプスの数です。")
            OutlinedButton(onClick={vm.tab=1;vm.search("EPG")}) {Text("EPGの神経を探す")}}
        item {ArticleSection("全脳と概観表示の違い","90個の領域モデルは脳全体の区画です。神経の概観は、実測形態から選んだ${r.previewCount}個の細胞の全ての枝を重ねたものです。操作中のみ細胞数を減らし、指を離すと全てに戻ります。全${number(vm.allCount.toLong())}細胞を同時に描画してはいません。個別表示は配布元の中心線形態を読み込みます。")}
        item {ArticleSection("機能が未解明の領域","形が分かっても、そこで行われる計算がすべて分かるわけではありません。解説には実験で確かめられた内容と、解剖・接続の説明を分けて記載しています。小領域に根拠のない機能を割り当てることはしていません。")}
        items(r.notes) {Body(it)}
        item {Text("ガイドの出典",fontSize=18.sp,fontWeight=FontWeight.Bold)}
        items(listOf("dataset","olfactory","mb","avp","cx")) {r.references[it]?.let {ref->ReferenceLink(ref)}}
    }
}
@Composable private fun PathButtons(vm:AtlasViewModel,r:AtlasRepository,keys:List<String>) {
    LazyRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) {items(keys) {key ->AssistChip(onClick={vm.selectRegion(r.meshes.first {it.key==key}.id)},label={Text(key)})}}
}

@Composable private fun About(vm:AtlasViewModel,r:AtlasRepository) {
    var licenses by remember {mutableStateOf(false)}
    val context=LocalContext.current
    val licenseText=remember {context.assets.open("licenses/Apache-2.0.txt").bufferedReader().use {it.readText()}+"\n\n"+context.assets.open("licenses/CC-BY-4.0.txt").bufferedReader().use {it.readText()}}
    LazyColumn(contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {Text("地図の出典",fontSize=32.sp,fontWeight=FontWeight.SemiBold);Text("MaleCNS v1.0  /  2026年6月8日版",color=Mint,fontSize=13.sp)}
        item {Surface(color=Panel,shape=RoundedCornerShape(18.dp)) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Stat("領域モデル",r.meshes.size.toString());Stat("部位解説",r.regions.size.toString());Stat("追跡済み神経",number(vm.allCount.toLong()));Stat("有向接続",number(r.stats.getLong("edges")));Stat("化学シナプス数",number(r.stats.getLong("synapses")))
        }}}
        item {ArticleSection("データの制作者","FlyEM（HHMI Janelia）、University of Cambridge Department of Zoology、MRC Laboratory of Molecular Biology、Google Research、およびMale CNSプロジェクトの貢献者。アプリの開発：hatake716。研究機関による公式アプリや推奨を示すものではありません。")}
        item {ArticleSection("商用利用とライセンス","MaleCNSデータはCC BY 4.0で配布されています。クレジット、ライセンスへのリンク、変更の表示などの条件を守ることで、加工・再配布・商用利用が認められています。アプリの購入代金は閲覧体験や解説の提供に対するもので、元データの利用権を独占するものではありません。")
            ExternalLink("MaleCNS 公式配布ページ","https://male-cns.janelia.org/download/")
            ExternalLink("CC BY 4.0 の利用条件","https://creativecommons.org/licenses/by/4.0/")}
        item {ArticleSection("加えた変更","高精細表示は元の領域メッシュの全三角形と座標を保持し、法線を符号付き8bitにして頂点を索引化しました。操作中と神経概観の背景には、各領域を最大20,000三角形にした形状を使います。座標を剛体回転・単位変換しました。概観は決定的に選んだ523細胞の全ての枝を静止時に表示します。神経注釈はstatusがTracedの細胞に限定し、それらの間の全接続を圧縮して収録しています。説明文・配色・画面は本アプリ独自のものです。")
            ExternalLink("データ・加工手順・出典を取得","https://github.com/hatake716/syoujoubae-noumiso/tree/main/data")}
        item {ArticleSection("収録範囲と注意点","全CNSの追跡済み神経を検索でき、脳に関係する分類に絞れます。腹側神経索内在・感覚・運動などvnc_の分類とENSなどは脳フィルターから除外します。神経形態は脳以外へ延びる部分も含みます。グリア・孤立断片・未追跡領域は検索と接続集計に含めていません。90領域の外形は脳のモデルであり、腹側神経索の領域メッシュは収録していません。")}
        item {ArticleSection("プライバシー","アカウント、広告、分析SDKはありません。検索語や保存した部位を開発者に送信しません。未同梱の神経形態を開く際は、Google Cloud Storageの公開配布先へ細胞IDを含むHTTPSリクエストを送ります。配布先はIPアドレス等の通常の通信情報を受け取ります。外部リンクは端末のブラウザで開きます。")
            ExternalLink("プライバシーポリシー","https://hatake716.github.io/syoujoubae-noumiso/privacy.html")}
        item {Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("取得した神経形態",fontWeight=FontWeight.Bold);Text("キャッシュ ${vm.cacheBytes/1024/1024} MB / 上限192 MB",color=Muted,fontSize=12.sp)};TextButton(onClick=vm::clearCache) {Text("削除")}}}
        item {TextButton(onClick={licenses=!licenses}) {Text(if(licenses)"ソフトウェアライセンスを閉じる" else "ソフトウェアライセンス")}}
        if(licenses) item {Body("アプリの独自ソースコード・日本語解説：Copyright © 2026 hatake716. All rights reserved.\n\nAndroidX / Jetpack Compose：The Android Open Source Project, Apache License 2.0。Kotlin標準ライブラリとkotlinx.coroutines：JetBrainsおよび貢献者, Apache License 2.0。\n\nMaleCNS由来データのCC BY 4.0の権利は維持されます。アプリにデータを再利用できなくする独自DRMはありません。")
            ExternalLink("Apache License 2.0","https://www.apache.org/licenses/LICENSE-2.0")
            Text(licenseText,fontSize=11.sp,lineHeight=17.sp,color=Muted)}
        item {Text("参考文献",fontSize=20.sp,fontWeight=FontWeight.SemiBold)}
        items(r.references.values.toList()) {ReferenceLink(it)}
        item {Text("アプリ ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nデータ・利用条件の確認日：2026年9月8日",color=Muted,fontSize=11.sp)}
    }
}

@Composable private fun Stat(label:String,value:String) {Row(Modifier.fillMaxWidth()) {Text(label,Modifier.weight(1f),color=Muted,fontSize=12.sp);Text(value,color=Paper,fontSize=18.sp,fontFamily=FontFamily.Monospace)}}
@Composable private fun Label(text:String) {Surface(color=Ink.copy(alpha=.82f),shape=RoundedCornerShape(5.dp)) {Text(text,color=Mint,fontSize=10.sp,letterSpacing=1.sp,fontFamily=FontFamily.Monospace,modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp))}}
@Composable private fun RoundIcon(icon:ImageVector,label:String,onClick:()->Unit) {IconButton(onClick,modifier=Modifier.padding(2.dp).size(38.dp).background(Ink.copy(alpha=.75f),CircleShape)) {Icon(icon,label,Modifier.size(19.dp),tint=Paper)}}
@Composable private fun Body(text:String) {Text(text,fontSize=14.sp,lineHeight=24.sp,color=Paper.copy(alpha=.92f))}
@Composable private fun ArticleSection(title:String,text:String) {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {Text(title,fontSize=17.sp,fontWeight=FontWeight.SemiBold);Body(text)}}
@Composable private fun ReferenceLink(reference:Reference) {Column {Text(reference.kind,fontSize=10.sp,color=Muted);ExternalLink(reference.title,reference.url)}}
@Composable private fun ExternalLink(title:String,url:String) {
    val context=LocalContext.current
    TextButton(onClick={try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))} catch(_:Exception) {Toast.makeText(context,"リンクを開くブラウザがありません",Toast.LENGTH_LONG).show()}},contentPadding=PaddingValues(vertical=4.dp),modifier=Modifier.fillMaxWidth()) {
        Text(title,Modifier.weight(1f),fontSize=12.sp,lineHeight=18.sp);Spacer(Modifier.width(8.dp));Icon(Icons.Outlined.OpenInNew,null,Modifier.size(15.dp))
    }
}
