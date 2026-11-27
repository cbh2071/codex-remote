package cn.codexremote.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TailnetSettings(repo: Repository, connected: () -> Unit = {}) {
    var enabled by remember { mutableStateOf(repo.tailnet.enabled) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var loginUrl by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var computers by remember { mutableStateOf<List<TailnetPeer>?>(null) }
    var connecting by remember { mutableStateOf<TailnetPeer?>(null) }
    var feedback by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val foreground by repo.inForeground.collectAsState()
    LaunchedEffect(enabled, retry, foreground) {
        if (!foreground) return@LaunchedEffect
        loginUrl = ""
        computers = null
        if (!enabled) return@LaunchedEffect
        if (status.isEmpty()) status = "正在连接"
        var requested = false
        while (true) {
            try {
                val result = withContext(Dispatchers.IO) { repo.tailnet.status(peers = true) }
                val state = result.optString("BackendState")
                computers = if (state == "Running") tailnetComputers(result) else null
                val url = result.optString("AuthURL")
                loginUrl = url.takeIf {
                    val uri = Uri.parse(it)
                    uri.scheme == "https" && uri.host == "login.tailscale.com" && uri.userInfo == null
                }.orEmpty()
                status = when (state) {
                    "Running" -> "已连接 ${result.optJSONArray("TailscaleIPs")?.optString(0).orEmpty()}"
                    "NeedsMachineAuth" -> "等待管理员批准设备"
                    "NeedsLogin" -> "等待登录 Tailscale"
                    else -> "正在连接 · $state"
                }
                if (state == "NeedsLogin" && loginUrl.isEmpty() && !requested) {
                    withContext(Dispatchers.IO) { repo.tailnet.status(login = true) }
                    requested = true
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { computers = null; status = e.message ?: "无法连接内置网络"; break }
            delay(if (computers == null) 2000 else 10000)
        }
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("内置 Tailscale", style = MaterialTheme.typography.titleMedium)
                if (!enabled) Text("查找同一账号下的电脑", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, enabled = !busy && connecting == null, onCheckedChange = { value ->
                busy = true
                scope.launch {
                    try { repo.toggleTailnet(value); enabled = value }
                    finally { busy = false }
                }
            })
        }
        if (enabled) {
            Text(status, style = MaterialTheme.typography.bodySmall)
            Row {
                if (loginUrl.isNotEmpty()) TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl))) }
                    catch (_: android.content.ActivityNotFoundException) { status = "手机需要安装浏览器以完成登录" }
                }) { Text("登录 Tailscale") }
                TextButton(onClick = { retry++ }) { Text("刷新状态") }
            }
            computers?.let { peers ->
                Text("我的 Tailscale 电脑", style = MaterialTheme.typography.titleMedium)
                if (peers.isEmpty()) Text("当前账号没有可见的 Windows 电脑", style = MaterialTheme.typography.bodySmall)
                peers.forEach { peer ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(peer.name)
                            Text("${peer.ip} · " + if (peer.online) "在线" else "离线", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled = connecting == null && peer.online, onClick = {
                            connecting = peer; feedback = ""
                            scope.launch {
                                try {
                                    val name = repo.connectPeer(peer)
                                    repo.message.value = "已连接 $name"; connected()
                                } catch (e: CancellationException) { throw e }
                                catch (e: java.io.IOException) { feedback = "连接失败，请确认 ${peer.name} 的随行桥接正在运行，且 Tailscale 允许访问 48761 端口。" }
                                catch (e: Exception) { feedback = e.message ?: "连接失败" }
                                finally { connecting = null }
                            }
                        }) { Text(if (connecting == peer) "连接中…" else "连接") }
                    }
                }
                if (feedback.isNotEmpty()) Text(feedback, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
