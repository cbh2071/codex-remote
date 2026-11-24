package cn.codexremote.app

import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Composable
internal fun ProjectFileScope(thread: ThreadKey, repo: Repository, instance: String, sidebar: Boolean, closeSidebar: () -> Unit, openChild: (String) -> Unit, content: @Composable () -> Unit) {
    var target by remember(thread, instance) { mutableStateOf<Pair<String?, String>?>(null) }
    CompositionLocalProvider(LocalMessageFileClick provides { id, link -> target = id to link }, content = content)
    if (sidebar) key(thread, instance) { WorkspaceSidebar(thread, repo, instance, closeSidebar, { target = null to it }, { id -> closeSidebar(); openChild(id) }) }
    target?.let { (id, link) -> key(thread, instance, id, link) {
        var file by remember { mutableStateOf<JSONObject?>(null) }
        var error by remember { mutableStateOf("") }
        val close = { target = null }
        LaunchedEffect(Unit) {
            try { file = if (id == null) repo.sidebar(thread, instance, "open", link) else repo.preview(thread, instance, id, link) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "读取失败" }
        }
        val data = file
        val mime = data?.optString("mime").orEmpty()
        val name = data?.optString("name").orEmpty()
        val context = LocalContext.current
        when {
            error.isNotEmpty() -> AlertDialog(onDismissRequest = close, title = { Text("文件预览") },
                text = { Text(error) }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
            data == null -> AlertDialog(onDismissRequest = close, title = { Text("正在读取文件") }, text = { LinearProgressIndicator() },
                confirmButton = { TextButton(onClick = close) { Text("取消") } })
            mime.startsWith("image/") -> ImageViewer("$thread/$instance/$link", name, { Base64.decode(data.getString("data"), Base64.DEFAULT) }, close)
            mime == "text/plain" -> {
                val lines = remember { data.optString("text").lines() }
                val list = rememberLazyListState((data.optInt("line") - 1).coerceIn(lines.indices))
                val markdown = name.endsWith(".md", true) && data.optInt("line") == 0
                AlertDialog(onDismissRequest = close, title = { Text(name) }, text = {
                    LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                        if (markdown) item { MarkdownText(data.optString("text")) }
                        else items(lines.size) { index -> SelectionContainer { Text("${index + 1}  ${lines[index]}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
                    }
                }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
            }
            else -> LaunchedEffect(data) {
                try {
                    val local = withContext(Dispatchers.IO) {
                        val root = File(context.cacheDir, "project-previews").apply { mkdirs() }
                        root.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                        File(root, "${UUID.randomUUID()}-${File(name).name}").apply { writeBytes(Base64.decode(data.getString("data"), Base64.DEFAULT)) }
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.previewfiles", local)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    close()
                } catch (e: CancellationException) { throw e }
                catch (_: android.content.ActivityNotFoundException) { error = "手机上没有可打开此文档的阅读器" }
                catch (e: Exception) { error = e.message ?: "文档打开失败" }
            }
        }
    } }
}

@Composable
private fun WorkspaceSidebar(thread: ThreadKey, repo: Repository, instance: String, close: () -> Unit, openFile: (String) -> Unit, openChild: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var path by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var data by remember(tab, path, retry) { mutableStateOf<JSONObject?>(null) }
    var error by remember(tab, path, retry) { mutableStateOf("") }
    LaunchedEffect(tab, path, retry) {
        try { data = repo.sidebar(thread, instance, if (tab == 0) "list" else "children", path) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "读取失败" }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.88f)) { Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("会话侧栏", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭侧栏") }
            }
            TabRow(selectedTabIndex = tab) { listOf("文件", "侧边聊天", "子智能体").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            } }
            if (tab == 0) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = path.isNotEmpty(), onClick = { path = path.substringBeforeLast('/', "") }) { Icon(Icons.Outlined.ArrowUpward, "上一级目录") }
                Text(path.ifEmpty { "项目根目录" }, style = MaterialTheme.typography.bodySmall)
            }
            when {
                error.isNotEmpty() -> Column(Modifier.padding(16.dp)) { Text(error); TextButton(onClick = { retry++ }) { Text("重试") } }
                data == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {
                    val entries = data?.optJSONArray("entries")?.objects().orEmpty().filter { tab == 0 || it.optString("kind") == if (tab == 1) "sideChat" else "agent" }
                    if (entries.isEmpty()) Text(listOf("目录为空", "没有关联的侧边聊天", "没有关联的子智能体")[tab], Modifier.padding(16.dp))
                    key(tab, path) { LazyColumn { items(entries) { item ->
                        val name = item.optString("name").ifBlank { item.optString("id") }
                        val dir = item.optBoolean("directory")
                        Row(Modifier.fillMaxWidth().clickable {
                            if (tab != 0) openChild(item.getString("id"))
                            else { val child = if (path.isEmpty()) name else "$path/$name"; if (dir) path = child else openFile(child) }
                        }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (tab != 0) Icons.Outlined.ChatBubbleOutline else if (dir) Icons.Outlined.Folder else Icons.Outlined.Description, null, modifier = Modifier.size(20.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (data?.optBoolean("truncated") == true) item { Text("此目录仅显示前 2000 项", Modifier.padding(16.dp)) }
                    } }
                }
            }
        } }
    }
}
