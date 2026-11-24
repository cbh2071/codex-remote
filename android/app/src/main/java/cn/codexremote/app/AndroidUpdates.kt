package cn.codexremote.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val RELEASES = "https://api.github.com/repos/cbh2071/codex-remote/releases"
internal const val UPDATE_TOKEN = "github-release-read-token"
private const val MAX_APK_BYTES = 128L * 1024 * 1024
internal const val APK_TYPE = "application/vnd.android.package-archive"
internal data class AndroidRelease(val code: Int, val version: String, val size: Long, val id: Long)
private val github = OkHttpClient.Builder().followRedirects(false)
    .callTimeout(5, TimeUnit.MINUTES).readTimeout(30, TimeUnit.SECONDS).build()

internal fun cachedUpdate(context: Context) = File(context.cacheDir, "android-updates/codex-suixing.apk")

internal suspend fun fetchAndroidUpdate(
    context: Context,
    store: SecureStore,
    progress: (Float) -> Unit
): String? = withContext(Dispatchers.IO) {
    val token = store.get(UPDATE_TOKEN, "")
    val channel = if (context.packageName.endsWith(".preview")) "preview" else "stable"
    try {
        var page = 1
        var newest: AndroidRelease? = null
        do {
            currentCoroutineContext().ensureActive()
            val req = githubRequest("?per_page=100&page=$page", token, "application/vnd.github+json")
            val more = github.newCall(req).execute().use { response ->
                checkGithub(response)
                val entries = JSONArray(checkNotNull(response.body).string())
                val candidate = newestRelease(entries, channel)
                if (candidate != null && candidate.code > (newest?.code ?: 0)) newest = candidate
                response.header("Link").orEmpty().contains("rel=\"next\"")
            }
            page++
        } while (more)
        val release = newest ?: error("仓库尚未发布适用于此安装渠道的 APK。")
        if (release.code <= BuildConfig.VERSION_CODE) return@withContext null
        downloadUpdate(context, token, release, progress)
        release.version
    } catch (e: IOException) {
        throw IllegalStateException("无法完成 GitHub 更新，请检查网络和手机可用空间后重试。", e)
    }
}

internal fun githubRequest(path: String, token: String, accept: String) =
    Request.Builder().url(RELEASES + path).header("Accept", accept).apply {
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
    }.build()

internal fun checkGithub(response: Response) {
    when (response.code) {
        200 -> return
        401, 404 -> error("无法读取仓库或安装包。私有仓库需要对此仓库有 Contents 只读权限的 GitHub 令牌。")
        403, 429 -> error("GitHub 拒绝了请求，请检查令牌权限或稍后重试。")
        else -> error("GitHub 返回 ${response.code}，请稍后重试。")
    }
}

internal fun newestRelease(entries: JSONArray, channel: String): AndroidRelease? {
    val name = Regex("""codex-suixing-$channel-(\d+)-(\d+\.\d+\.\d+)\.apk""")
    return entries.objects().filterNot { it.optBoolean("draft") }.flatMap { release ->
        release.optJSONArray("assets")?.objects().orEmpty().mapNotNull { asset ->
            val match = name.matchEntire(asset.optString("name")) ?: return@mapNotNull null
            val code = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val version = match.groupValues[2]
            val id = asset.optLong("id")
            val size = asset.optLong("size")
            if (code > 0 && size in 1..MAX_APK_BYTES && id > 0 && asset.optString("state") == "uploaded" &&
                release.optString("tag_name") == "v$version") AndroidRelease(code, version, size, id) else null
        }
    }.maxByOrNull { it.code }
}

internal fun openAsset(id: Long, token: String, client: OkHttpClient = github): Response {
    val request = githubRequest("/assets/$id", token, "application/octet-stream")
    val response = client.newCall(request).execute()
    if (!response.isRedirect) return response
    val url = response.use { it.header("Location")?.let(request.url::resolve) }
    check(url != null && url.isHttps && url.host.endsWith(".githubusercontent.com")) { "GitHub 返回了未知下载地址。" }
    return client.newCall(Request.Builder().url(url).build()).execute()
}

private suspend fun downloadUpdate(context: Context, token: String, release: AndroidRelease, progress: (Float) -> Unit) {
    val destination = cachedUpdate(context)
    val folder = checkNotNull(destination.parentFile)
    check(folder.mkdirs() || folder.isDirectory) { "无法创建更新缓存。" }
    val temp = File.createTempFile("download-", ".apk", folder)
    try {
        progress(0f)
        val job = currentCoroutineContext()
        openAsset(release.id, token).use { response ->
            checkGithub(response)
            val body = checkNotNull(response.body) { "GitHub 返回了空的更新包。" }
            check(body.contentLength() in listOf(-1L, release.size)) { "更新包大小不匹配，请重新检查更新。" }
            var total = 0L
            var lastPercent = 0
            temp.outputStream().buffered().use { output ->
                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    job.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= release.size) { "更新包超过预期大小。" }
                    output.write(buffer, 0, count)
                    val percent = (total * 100 / release.size).toInt()
                    if (percent != lastPercent) { progress(percent / 100f); lastPercent = percent }
                }
            }
            check(total == release.size) { "更新包下载不完整。" }
        }
        validateUpdate(context, temp, release)
        job.ensureActive()
        check(temp.renameTo(destination)) { "无法保存更新包。" }
    } finally {
        temp.delete()
    }
}

@Suppress("DEPRECATION")
private fun validateUpdate(context: Context, file: File, release: AndroidRelease) {
    val pm = context.packageManager
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    val archive = checkNotNull(pm.getPackageArchiveInfo(file.path, flags)) { "无法读取更新包，安装已取消。" }
    val installed = pm.getPackageInfo(context.packageName, flags)
    check(archive.packageName == installed.packageName &&
        PackageInfoCompat.getLongVersionCode(archive) == release.code.toLong() &&
        PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed) &&
        archive.versionName == release.version) { "更新包的应用或版本不匹配，安装已取消。" }
    val signers = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
    check(signers.isNotEmpty() && signers == installed.signingInfo?.apkContentsSigners?.toSet()) {
        "更新包签名与已安装版本不一致，安装已取消。"
    }
}
