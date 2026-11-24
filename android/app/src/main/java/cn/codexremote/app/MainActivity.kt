package cn.codexremote.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Forest = Color(0xFF173D31)
private val Lime = Color(0xFFD2F56D)
private val Paper = Color(0xFFF3F5EF)
private val Ink = Color(0xFF1D2A23)

class MainActivity : ComponentActivity() {
    private val repository get() = (application as RemoteApplication).repository
    private val incoming = mutableStateOf<ThreadKey?>(null)
    private var scanned by mutableStateOf<String?>(null)
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let { scanned = it } }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) beginMonitor() else repository.message.value = "通知权限未开启，后台事件可能无法提醒你" }
    private fun beginMonitor() { startForegroundService(Intent(this, MonitorService::class.java)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); route(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White, secondary = Forest, secondaryContainer = Lime, onSecondaryContainer = Forest, background = Paper, surface = Paper, onSurface = Ink, outline = Color(0xFF819084)), typography = Typography(titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold), headlineLarge = Typography().headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold))) {
                Console(repository, incoming.value, { incoming.value = null }, scanned, { scanned = null }, { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描电脑桥接窗口的配对码").setBeepEnabled(false).setOrientationLocked(false)) }, {
                    if (repository.monitoring.value) { stopService(Intent(this, MonitorService::class.java)); repository.monitor(false) }
                    else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else beginMonitor()
                })
            }
        }
    }
    private fun route(intent: Intent?) { val host = intent?.getStringExtra("hostId"); val thread = intent?.getStringExtra("threadId"); if (!host.isNullOrEmpty() && !thread.isNullOrEmpty()) incoming.value = ThreadKey(host, thread) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); route(intent) }
    override fun onStart() { super.onStart(); repository.foreground(true) }
    override fun onStop() { repository.foreground(false); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Console(repo: Repository, incoming: ThreadKey?, consumeIncoming: () -> Unit, scanned: String?, consumeScan: () -> Unit, scan: () -> Unit, monitor: () -> Unit) {
    val hosts by repo.states.collectAsState(); val background by repo.monitoring.collectAsState(); val message by repo.message.collectAsState()
    val histories by repo.histories.collectAsState()
    var screen by rememberSaveable { mutableStateOf("home") }; var selected by remember { mutableStateOf<ThreadKey?>(null) }
    var createHost by rememberSaveable { mutableStateOf("") }
    var forkKey by remember { mutableStateOf<ThreadKey?>(null) }
    var sidebar by remember(selected) { mutableStateOf(false) }
    val parents = remember { mutableStateListOf<ThreadKey>() }
    fun back() {
        if (screen == "thread" && parents.isNotEmpty()) selected = parents.removeAt(parents.lastIndex)
        else { parents.clear(); screen = "home"; selected = null }
    }
    var pairingText by remember { mutableStateOf("") }; var pairing by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    var manualPair by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { if (message.isNotEmpty()) { snackbar.showSnackbar(message); repo.message.value = "" } }
    LaunchedEffect(incoming) { if (incoming != null && hosts.containsKey(incoming.hostId)) { selected = incoming; screen = "thread"; consumeIncoming() } }
    LaunchedEffect(scanned) { if (scanned != null) { pairingText = scanned; manualPair = true; screen = "pair"; consumeScan() } }
    BackHandler(screen != "home") { back() }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = Paper, topBar = {
        TopAppBar(expandedHeight = 56.dp, title = {
            if (screen == "thread") Column {
                Text(selected?.let { histories[it]?.optString("title") }?.ifBlank { "会话" } ?: "会话", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hosts[selected?.hostId]?.host?.name ?: "电脑已移除", fontSize = 11.sp, color = Forest, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(if (screen == "home") "codex随行" else if (screen == "hosts") "我的电脑" else if (screen == "create") "新建会话" else "连接电脑", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
            navigationIcon = { if (screen != "home") IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = {
                if (screen == "thread" && selected != null) {
                    IconButton(onClick = { sidebar = true }) { Icon(Icons.Outlined.Folder, "项目文件与关联会话") }
                    IconButton(onClick = { forkKey = selected }) { Icon(Icons.AutoMirrored.Outlined.CallSplit, "Fork 当前会话") }
                    IconButton(onClick = { createHost = selected?.hostId.orEmpty(); screen = "create" }) { Icon(Icons.Outlined.AddComment, "新建会话") }
                }
                if (hosts.isNotEmpty() && screen in listOf("home", "thread", "hosts")) IconButton(onClick = { repo.refreshAll() }) { Icon(Icons.Outlined.Refresh, "刷新") }
                if (screen != "hosts") IconButton(onClick = { screen = "hosts" }) { Icon(Icons.Outlined.Dns, "电脑管理") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper))
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (if (screen == "home" && hosts.isEmpty()) "pair" else screen) {
                "pair" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (screen == "home") Text("连接电脑", style = MaterialTheme.typography.titleLarge)
                    TailnetSettings(repo) { screen = "home" }
                    HorizontalDivider()
                    OutlinedButton(onClick = scan, enabled = !pairing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp)) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("扫描电脑配对码") }
                    TextButton(onClick = { manualPair = !manualPair }, enabled = !pairing) { Text(if (manualPair) "收起配对码" else "粘贴配对码"); Icon(if (manualPair) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }
                    if (manualPair) {
                        OutlinedTextField(value = pairingText, onValueChange = { pairingText = it }, label = { Text("配对码") }, maxLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !pairing)
                        Button(onClick = { scope.launch { pairing = true; try { val name = repo.pair(pairingText); pairingText = ""; manualPair = false; repo.message.value = "已连接 $name"; screen = "home" } catch (e: Exception) { repo.message.value = connectionMessage(e) } finally { pairing = false } } }, enabled = !pairing && pairingText.isNotBlank()) { Text(if (pairing) "等待电脑确认…" else "核对并配对") }
                    }
                }
                "hosts" -> HostsScreen(hosts.values.toList(), repo, background, monitor, { screen = "pair" })
                "create" -> NewThreadScreen(hosts.values.filter { it.host.followed }, repo, createHost, { key -> selected = key; screen = "thread" }, { screen = "home" })
                "thread" -> selected?.let { key -> ProjectFileScope(key, repo, hosts[key.hostId]?.instance.orEmpty(), sidebar, { sidebar = false }, { child ->
                    parents.add(key); selected = ThreadKey(key.hostId, child)
                }) { ThreadScreen(key, repo, hosts[key.hostId]) } }
                else -> HomeScreen(hosts.values.toList(), repo, { screen = "hosts" }, { id -> createHost = id; screen = "create" }, { key -> selected = key; screen = "thread" })
            }
        }
    }
    forkKey?.let { key -> key(key) { ForkDialog(key, hosts[key.hostId], histories[key]?.optString("title").orEmpty(), repo, { child -> forkKey = null; selected = child; screen = "thread" }, { forkKey = null; screen = "home" }, { forkKey = null }) } }
}

@Composable
private fun HomeScreen(hosts: List<HostState>, repo: Repository, manage: () -> Unit, create: (String) -> Unit, open: (ThreadKey) -> Unit) {
    val selection by repo.homeSelection.collectAsState(); val unread by repo.unreadCompletions.collectAsState()
    val filter = selection.hostId; val attention = selection.attention
    val followed = hosts.filter { it.host.followed }
    val visibleHosts = followed.filter { filter.isEmpty() || filter == it.host.id }
    var retry by remember { mutableIntStateOf(0) }
    val refreshed by repo.catalogRefresh.collectAsState()
    val indexes = visibleHosts.associate { h -> h.host.id to key(h.host.id) { projectIndex(repo, h, retry + refreshed) } }
    val groups = visibleHosts.flatMap { h ->
        (h.threads + indexes[h.host.id]?.threads.orEmpty()).distinctBy { it.getString("id") }.map { h to it }
    }.groupBy { (h, thread) -> h.host.id to threadProject(thread) }
    fun projectName(id: Pair<String, String>): String {
        if (id.second == "unassigned") return "未归入项目"
        return indexes[id.first]?.names?.get(id.second)
            ?: groups[id]?.firstOrNull()?.second?.optString("cwd")?.trimEnd('/', '\\')?.substringAfterLast('\\')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: id.second.removePrefix("id/")
    }
    val inProject = selection.project.isNotEmpty()
    val tasks = groups[selection.projectHost to selection.project].orEmpty()
        .filter { !attention || it.second.optString("status") in listOf("active", "needsInput") }
        .sortedByDescending { it.second.optLong("updatedAt") }
    fun backToProjects() { repo.selectHome(selection.host(filter)) }
    BackHandler(inProject) { backToProjects() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
        item { Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (inProject) IconButton(onClick = ::backToProjects) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回项目列表") }
            Column(Modifier.weight(1f)) {
                Text(if (inProject) selection.projectName else "项目", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val projectRows = groups[selection.projectHost to selection.project].orEmpty()
                val subtitle = if (inProject) "${projectRows.size} 个会话" else "${visibleHosts.count { it.online }} / ${visibleHosts.size} 台电脑在线"
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Forest)
            }
            if (followed.isNotEmpty()) FilledTonalIconButton(onClick = { create(selection.projectHost.ifEmpty { filter }) }) { Icon(Icons.Outlined.AddComment, "新建会话") }
        } }
        if (followed.size > 1 && !inProject) item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter.isEmpty(), onClick = { repo.selectHome(selection.host("")) }, label = { Text("全部关注") })
                followed.forEach { h -> FilterChip(selected = filter == h.host.id, onClick = { repo.selectHome(selection.host(h.host.id)) }, label = { Text((if (h.online) "● " else "○ ") + h.host.name) }) }
            }
        }
        if (!inProject) items(groups.keys.sortedByDescending { k -> groups[k].orEmpty().maxOfOrNull { it.second.optLong("updatedAt") } ?: 0L }, key = { "project/${it.first}/${it.second}" }) { id ->
            val rows = groups.getValue(id)
            val name = projectName(id)
            Row(Modifier.fillMaxWidth().clickable { repo.selectHome(selection.copy(projectHost = id.first, project = id.second, projectName = name)) }.heightIn(min = 60.dp).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).background(Color(0xFFE0EBE4), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp), tint = Forest) }
                Column(Modifier.weight(1f)) {
                    Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    if (visibleHosts.size > 1) Text(rows.first().first.host.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${rows.size} 个会话", style = MaterialTheme.typography.bodySmall, color = Forest)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
            }
            HorizontalDivider(color = Color(0xFFDDE3DE))
        }
        if (indexes.values.any { it.loading }) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        indexes.filterValues { it.error.isNotEmpty() }.forEach { (host, _) ->
            item { TextButton(onClick = { retry++ }) { Text("${visibleHosts.first { it.host.id == host }.host.name} · 历史加载失败，重试") } }
        }
        if (inProject && (tasks.isNotEmpty() || attention)) item { FilterChip(selected = attention, onClick = { repo.selectHome(selection.copy(attention = !attention)) }, label = { Text("只看运行 / 待处理") }) }
        visibleHosts.filter { !it.online && (!inProject || selection.projectHost == it.host.id) }.forEach { h -> item(key = "offline-${h.host.id}") {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Column { Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp), tint = Color(0xFF896321))
                Text("${h.host.name} · 暂未连接", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起详情" else "连接详情")
            }; if (expanded) Text(h.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
        } }
        visibleHosts.filter { it.online && it.catalogError != null }.forEach { h ->
            item(key = "catalog-error-${h.host.id}") {
                Column(Modifier.padding(12.dp)) {
                    Text("${h.host.name} · 会话列表加载失败", fontWeight = FontWeight.Bold)
                    Text(h.catalogError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = repo::refreshAll) { Text("重试") }
                }
            }
        }
        if (followed.isEmpty()) item { TextButton(onClick = manage) { Text("选择要关注的电脑") } }
        else if ((if (inProject) tasks.isEmpty() else groups.isEmpty()) && indexes.values.all { !it.loading && it.error.isEmpty() } && visibleHosts.isNotEmpty() && visibleHosts.all { it.online && it.catalogLoaded && it.catalogError == null }) item { Text(if (attention && inProject) "暂时没有运行或待处理任务" else "暂无会话", modifier = Modifier.padding(12.dp)) }
        items(tasks, key = { "${it.first.host.id}/${it.second.optString("id")}" }) { (h, t) ->
            Column(Modifier.fillMaxWidth().clickable { open(ThreadKey(h.host.id, t.optString("id"))) }.heightIn(min = 52.dp).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (unread.containsKey(ThreadKey(h.host.id, t.optString("id")))) CompletionDot("有新的已完成回复")
                        Text(t.optString("title", "未命名会话"), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        StatusPill(t.optString("status"))
                    }
                    val summary = t.optString("summary"); if (summary.isNotEmpty()) Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF57665C), style = MaterialTheme.typography.bodySmall)
                }
            HorizontalDivider(color = Color(0xFFDDE3DE))
        }
    }
}

@Composable private fun StatusPill(status: String) { val text = when(status) { "active", "inProgress" -> "运行中"; "needsInput" -> "待处理"; "failed" -> "失败"; "completed" -> "已完成"; "notLoaded" -> "历史"; "interrupted" -> "已中断"; else -> "空闲" }; Surface(color = if (status == "needsInput") Color(0xFFFFD8A5) else if (status == "active") Lime else Color(0xFFEDF0E8), shape = RoundedCornerShape(30.dp)) { Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp, color = Forest) } }

@Composable
private fun HostsScreen(hosts: List<HostState>, repo: Repository, background: Boolean, monitor: () -> Unit, add: () -> Unit) {
    var editing by remember { mutableStateOf<HostState?>(null) }; var name by remember { mutableStateOf("") }; var removing by remember { mutableStateOf<HostState?>(null) }; var unlock by remember { mutableStateOf<HostState?>(null) }
    var licenses by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf<Host?>(null) }
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Button(onClick = add, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Outlined.Add, null); Text("连接电脑") } }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("后台监控", modifier = Modifier.weight(1f)); Switch(checked = background, onCheckedChange = { monitor() }) } }
        item { TailnetSettings(repo) }
        items(hosts, key = { it.host.id }) { h -> Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(h.host.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); Switch(checked = h.host.followed, onCheckedChange = { repo.follow(h.host.id, it) }) }
            Text(if (h.online) "在线 · ${h.threads.size} 个最近会话" else h.error, color = Forest); Text("${connectionKind(h.host.url)} · ${h.host.url}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("证书 ${h.host.fingerprint.take(16)}…", fontSize = 11.sp)
            TextButton(onClick = { connection = h.host }) { Icon(Icons.Outlined.Lan, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("修改连接地址") }
            Row { TextButton(onClick = { editing = h; name = h.host.name }) { Text("改名") }; TextButton(onClick = { removing = h }) { Text("移除") }; TextButton(onClick = { unlock = h }) { Text("核对未确认操作") } }
        } } }
        item { Text("取消关注只停止这台电脑的连接；移除会删除手机信任。要永久撤销手机权限，请同时在那台电脑桥接窗口撤销配对。", fontSize = 13.sp) }
        item { TextButton(onClick = { licenses = true }) { Text("开源许可 · ${BuildConfig.VERSION_NAME}", fontSize = 12.sp) } }
    }
    connection?.let { host -> ConnectionAddressDialog(host, repo) { connection = null } }
    if (licenses) {
        val text = remember { listOf("THIRD-PARTY-NOTICES.md", "CommonMark-LICENSE.txt", "Apache-2.0.txt").joinToString("\n\n") { file -> context.assets.open("licenses/$file").bufferedReader().use { it.readText() } } }
        TextDetail("开源许可", text, monospace = false) { licenses = false }
    }
    editing?.let { h -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("电脑显示名称") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { repo.rename(h.host.id, name); editing = null }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    removing?.let { h -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除 ${h.host.name}？") }, text = { Text("不会影响其他电脑。电脑上仍应撤销这台手机的配对权限。") }, confirmButton = { TextButton(onClick = { repo.remove(h.host.id); removing = null }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
    unlock?.let { h -> AlertDialog(onDismissRequest = { unlock = null }, title = { Text("先核对电脑上的会话") }, text = { Text("如果发送结果不确定，请先检查该指令是否已经执行。只有你已核对后才解除锁定；旧指令不会自动重发。") }, confirmButton = { TextButton(onClick = { repo.clearUncertain(h.host.id); unlock = null }) { Text("我已核对，解除锁定") } }, dismissButton = { TextButton(onClick = { unlock = null }) { Text("暂不解除") } }) }
}

@Composable
private fun ThreadScreen(key: ThreadKey, repo: Repository, host: HostState?) {
    val histories by repo.histories.collectAsState(); val view = histories[key]; val scope = rememberCoroutineScope()
    var draft by remember(key) { mutableStateOf(repo.draft(key)) }; var busy by remember(key) { mutableStateOf(false) }; var stopConfirm by remember { mutableStateOf(false) }
    var latestRequest by remember(key) { mutableIntStateOf(0) }
    var modelDialog by remember(key) { mutableStateOf(false) }; var editTarget by remember(key) { mutableStateOf<JSONObject?>(null) }
    var selectedImages by remember(key) { mutableStateOf(repo.imageDrafts.list(key)) }
    var pickerTarget by remember { mutableStateOf<ThreadKey?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        val target = pickerTarget; pickerTarget = null
        if (target == null || uris.isEmpty()) busy = false
        else scope.launch {
            try { val added = repo.imageDrafts.add(target, uris); if (target == key) selectedImages = added }
            catch (e: Exception) { repo.message.value = e.message ?: "无法添加图片" }
            finally { busy = false }
        }
    }
    DisposableEffect(key) { repo.watch(key); onDispose { repo.unwatch(key) } }
    val active = view?.optString("activeTurnId").orEmpty(); val live = view?.optBoolean("live", true) == true
    val enabled = host?.online == true && host.compatible && view != null && live && !busy
    val desktopTools = view?.optBoolean("desktopTools") == true
    val textOnlySend = desktopTools && view?.optBoolean("canSendText") == true && host?.online == true && host.compatible && !busy
    var nextModel by remember(key, host?.instance) { mutableStateOf<JSONObject?>(null) }
    var resend by remember(key) { mutableStateOf<String?>(null) }
    val modelEnabled = enabled || desktopTools && host?.online == true && host.compatible && !busy
    fun operate(type: String, fields: JSONObject = JSONObject(), success: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (desktopTools && type in setOf("send", "steer")) nextModel?.let { choice ->
                    for (field in listOf("model", "effort", "expectedProvider")) fields.put(field, choice.getString(field))
                }
                repo.message.value = repo.operate(key, type, fields)
                if (type == "send" || type == "steer") { nextModel = null; if (draft == fields.optString("text")) { draft = ""; repo.draft(key, "") }; selectedImages = repo.imageDrafts.list(key); latestRequest++ }
                if (type == "edit") latestRequest++
                success()
            }
            catch (e: Exception) { repo.message.value = e.message ?: "未能发送；请刷新核对" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (host?.online != true || view != null && !live && !textOnlySend) Column(Modifier.fillMaxWidth().background(Color(0xFFFFE9CF)).padding(horizontal = 12.dp, vertical = 5.dp)) {
            if (host?.online == true && view != null && !live && !busy) {
                if (desktopTools) Text("当前会话暂不能接收文字，请刷新查看状态", fontSize = 12.sp)
                else TextButton(onClick = { operate("activate") }, enabled = host.compatible) { Text("请求桌面接入会话") }
            }
            if (host?.online != true) Text("电脑离线：仅保存草稿，不会排队发送", color = Color(0xFF9A4F17), fontSize = 11.sp)
        }
        ConversationTimeline(key, view, Modifier.weight(1f), latestRequest, { turn -> repo.markRead(key, turn) }, images = { message ->
            val instance = view?.optString("instanceId").orEmpty()
            ChatImages(message, "${key.hostId}/${key.threadId}/$instance") { id -> repo.image(key, instance, id) }
        }, edit = if (enabled) ({ item -> editTarget = JSONObject(item.toString()).put("instanceId", view?.optString("instanceId")) }) else null,
            reuse = if (textOnlySend) ({ text -> resend = text }) else null) { request -> RequestCard(request, enabled, { fields -> operate("decision", fields) }, { fields -> operate("answer", fields) }) }
        Column(Modifier.fillMaxWidth().background(Paper).imePadding().padding(horizontal = 10.dp, vertical = 5.dp)) {
            LatestFilesButton(view)
            LatestThought(view, active.isNotEmpty(), enabled) { stopConfirm = true }
            DraftImages(selectedImages, "${key.hostId}/${key.threadId}/draft", !busy, { id -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.imageDrafts.bytes(key, id) } }) { id -> repo.imageDrafts.remove(key, setOf(id)); selectedImages = repo.imageDrafts.list(key) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { modelDialog = true }, enabled = modelEnabled, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 32.dp).weight(1f)) { Text(nextModel?.optString("model") ?: view?.optString("model")?.substringAfterLast('/')?.ifBlank { "选择模型" } ?: "选择模型", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Outlined.ExpandMore, "切换模型", Modifier.size(15.dp)) }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { pickerTarget = key; busy = true; imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !desktopTools && !busy && selectedImages.size < MAX_IMAGES, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.AddPhotoAlternate, "添加图片") }
                OutlinedTextField(draft, { draft = it; repo.draft(key, it) }, placeholder = { Text(if (active.isNotEmpty()) "追加指示…" else "发送指令…", fontSize = 14.sp) }, textStyle = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).heightIn(max = 120.dp), shape = RoundedCornerShape(14.dp), maxLines = 4)
                FilledIconButton(onClick = { operate(if (active.isEmpty()) "send" else "steer", JSONObject().put("text", draft).put("draftImageIds", JSONArray(selectedImages.map { it.id }))) }, enabled = enabled && (draft.isNotBlank() || selectedImages.isNotEmpty()) || textOnlySend && draft.isNotBlank() && selectedImages.isEmpty(), modifier = Modifier.size(48.dp)) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Outlined.Send, "发送") }
            }
        }
    }
    if (modelDialog) ModelDialog(key, host, repo, modelEnabled, { modelDialog = false }) { fields ->
        if (desktopTools) { nextModel = fields; modelDialog = false } else operate("model", fields) { modelDialog = false }
    }
    resend?.let { original ->
        var revised by remember(original) { mutableStateOf(original) }
        AlertDialog(onDismissRequest = { if (!busy) resend = null }, title = { Text("编辑后作为新消息发送") }, text = {
            Column { Text("新消息将追加到当前会话，保留之前的对话。"); OutlinedTextField(revised, { revised = it }, enabled = !busy, maxLines = 9) }
        }, confirmButton = { TextButton(enabled = textOnlySend && revised.isNotBlank() && revised.length <= 32000, onClick = {
            operate(if (active.isEmpty()) "send" else "steer", JSONObject().put("text", revised)) { resend = null }
        }) { Text("发送新消息") } }, dismissButton = { TextButton(enabled = !busy, onClick = { resend = null }) { Text("取消") } })
    }
    editTarget?.let { target -> EditMessageDialog(key, target, view, repo, enabled, { editTarget = null }) { fields, clear -> operate("edit", fields) { clear(); editTarget = null } } }
    if (stopConfirm) AlertDialog(onDismissRequest = { stopConfirm = false }, title = { Text("中断 ${host?.host?.name} 上的任务？") }, text = { Text("只中断当前会话的这一轮，不影响其他电脑。") }, confirmButton = { TextButton(onClick = { stopConfirm = false; operate("interrupt") }) { Text("确认中断") } }, dismissButton = { TextButton(onClick = { stopConfirm = false }) { Text("继续运行") } })
}

@Composable
private fun RequestCard(request: JSONObject, enabled: Boolean, decision: (JSONObject) -> Unit, answer: (JSONObject) -> Unit) {
    val method = request.optString("method"); val params = request.optJSONObject("params") ?: JSONObject(); val requestId = request.optString("id")
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE6BF))) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("需要你处理", style = MaterialTheme.typography.titleLarge)
        when(method) {
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                SelectionContainer { Text(params.optString("command", params.optString("reason", "请检查本次修改")), fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                if (params.optString("reason").isNotEmpty()) Text(params.optString("reason"), fontSize = 13.sp)
                Text("仅对本次请求有效。若电脑已经处理，这里会自动更新。", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { decision(JSONObject().put("requestId", requestId).put("decision", "accept")) }, enabled = enabled) { Text("允许本次") }; OutlinedButton(onClick = { decision(JSONObject().put("requestId", requestId).put("decision", "decline")) }, enabled = enabled) { Text("拒绝") } }
            }
            "item/tool/requestUserInput" -> {
                val questions = params.optJSONArray("questions")?.objects().orEmpty(); val answers = remember(requestId) { mutableStateMapOf<String, String>() }
                questions.forEach { question ->
                    val id = question.optString("id"); Text(question.optString("question"), fontWeight = FontWeight.SemiBold)
                    question.optJSONArray("options")?.objects()?.forEach { option ->
                        OutlinedButton(onClick = { answers[id] = option.optString("label") }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text((if (answers[id] == option.optString("label")) "✓ " else "") + option.optString("label")); if (option.optString("description").isNotEmpty()) Text(option.optString("description"), fontSize = 11.sp) } }
                    }
                    OutlinedTextField(answers[id].orEmpty(), { answers[id] = it }, label = { Text("回答 / 其他") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), visualTransformation = if (question.optBoolean("isSecret")) PasswordVisualTransformation() else VisualTransformation.None, keyboardOptions = KeyboardOptions(keyboardType = if (question.optBoolean("isSecret")) KeyboardType.Password else KeyboardType.Text))
                }
                Button(onClick = { val values = JSONObject(); questions.forEach { q -> values.put(q.optString("id"), JSONArray().put(answers[q.optString("id")])) }; answer(JSONObject().put("requestId", requestId).put("answers", values)) }, enabled = enabled && questions.isNotEmpty() && questions.all { !answers[it.optString("id")].isNullOrBlank() }) { Text("提交回答") }
            }
            else -> { Text("这类工具或权限请求暂不在手机处理，请回电脑完成。不会自动批准。"); Text(method, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    } }
}
