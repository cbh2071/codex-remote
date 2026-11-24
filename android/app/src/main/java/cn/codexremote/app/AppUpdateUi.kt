package cn.codexremote.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun AppUpdateDialog(repo: Repository, dismiss: () -> Unit) {
    val context = LocalContext.current
    val downloadProgress = remember { MutableStateFlow(-1f) }
    val progress by downloadProgress.collectAsState()
    var version by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var editingToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }

    fun openInstaller() {
        try {
            val apk = cachedUpdate(context)
            check(apk.isFile) { "更新包已被清理，请重新检查更新。" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.previewfiles", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            message = "找不到 Android 安装程序。"
        } catch (_: SecurityException) {
            message = "Android 未允许此应用发起安装。"
        } catch (e: IllegalStateException) {
            message = e.message
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) openInstaller()
        else message = "请允许 codex随行安装未知应用。"
    }

    fun install() {
        message = null
        if (context.packageManager.canRequestPackageInstalls()) openInstaller()
        else try {
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        } catch (_: ActivityNotFoundException) {
            message = "请在 Android 设置中允许 codex随行安装未知应用。"
        }
    }

    LaunchedEffect(attempt) {
        if (version != null || message != null) return@LaunchedEffect
        try {
            version = repo.fetchAppUpdate { downloadProgress.value = it }
            if (version == null) message = "已是 GitHub Releases 上的最新版本。"
            else install()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "检查更新失败，请稍后重试。"
        }
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("应用更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (editingToken) {
                    OutlinedTextField(value = token, onValueChange = { token = it }, singleLine = true,
                        label = { Text("GitHub 只读令牌") }, visualTransformation = PasswordVisualTransformation())
                    Text("仅需 cbh2071/codex-remote 的 Contents 读取权限。留空保存可清除已存令牌。")
                } else Text(message ?: when {
                    version != null -> "已下载 ${version}，请在 Android 安装界面确认。"
                    progress >= 0f -> "正在下载更新包 · ${(progress * 100).toInt()}%"
                    else -> "正在检查更新…"
                })
                if (message == null && version == null) {
                    if (progress < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (editingToken) TextButton(onClick = {
                repo.store.put(UPDATE_TOKEN, token.trim())
                token = ""
                editingToken = false
                message = null
                downloadProgress.value = -1f
                attempt++
            }) { Text("保存并检查") }
            else if (version != null) TextButton(onClick = { install() }) { Text("安装更新") }
            else TextButton(onClick = dismiss) { Text(if (message == null) "取消" else "关闭") }
        },
        dismissButton = {
            if (version != null) TextButton(onClick = dismiss) { Text("关闭") }
            else if (!editingToken && message != null) TextButton(onClick = { editingToken = true }) { Text("配置仓库令牌") }
            else if (editingToken) TextButton(onClick = { token = ""; editingToken = false }) { Text("取消") }
        }
    )
}
