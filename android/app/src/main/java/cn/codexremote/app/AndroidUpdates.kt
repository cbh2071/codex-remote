package cn.codexremote.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.Xml
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
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit

private const val RELEASES = "https://api.github.com/repos/cbh2071/codex-remote/releases"
private const val PUBLIC_RELEASES = "https://github.com/cbh2071/codex-remote/releases"
private const val MAX_APK_BYTES = 128L * 1024 * 1024
internal const val APK_TYPE = "application/vnd.android.package-archive"
internal data class AndroidRelease(val code: Int, val version: String, val size: Long, val fileName: String)
private val github = OkHttpClient.Builder().followRedirects(false)
    .callTimeout(5, TimeUnit.MINUTES).readTimeout(30, TimeUnit.SECONDS).build()

internal fun cachedUpdate(context: Context) = File(context.cacheDir, "android-updates/codex-suixing.apk")

internal suspend fun fetchAndroidUpdate(
    context: Context,
    progress: (Float) -> Unit
): String? = withContext(Dispatchers.IO) {
    val channel = if (context.packageName.endsWith(".preview")) "preview" else "stable"
    try {
        var page = 1
        var newest: AndroidRelease? = null
        do {
            currentCoroutineContext().ensureActive()
            val req = Request.Builder().url("$RELEASES?per_page=100&page=$page").header("Accept", "application/vnd.github+json").build()
            val more = github.newCall(req).execute().use { response ->
                if (response.code in setOf(403, 429)) {
                    response.close()
                    val candidate = publicFeedRelease(channel)
                    if (candidate != null && candidate.code > (newest?.code ?: 0)) newest = candidate
                    return@use false
                }
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
        downloadUpdate(context, release, progress)
        release.version
    } catch (e: IOException) {
        throw IllegalStateException("无法完成 GitHub 更新，请检查网络和手机可用空间后重试。", e)
    }
}

// The public feed and release assets are accessible independently of the REST API quota.
private fun publicFeedRelease(channel: String): AndroidRelease? {
    val versions = github.newCall(Request.Builder().url("$PUBLIC_RELEASES.atom").build()).execute().use { response ->
        checkGithub(response)
        val xml = Xml.newPullParser()
        xml.setInput(checkNotNull(response.body).byteStream(), null)
        val found = mutableSetOf<String>()
        var entry = false
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.name == "entry") entry = xml.eventType == XmlPullParser.START_TAG
            if (entry && xml.eventType == XmlPullParser.START_TAG && xml.name == "id") {
                val tag = xml.nextText().substringAfterLast('/')
                if (tag.matches(Regex("""v\d+\.\d+\.\d+"""))) found += tag.drop(1)
            }
        }
        found
    }
    var newest: AndroidRelease? = null
    for (version in versions) {
        openPublicFile(version, "SHA256SUMS.txt").use { response ->
            if (response.code == 404) return@use
            checkGithub(response)
            val match = publicManifestRelease(checkNotNull(response.body).string(), channel, version)
            if (match != null && match.code > (newest?.code ?: 0)) newest = match
        }
    }
    return newest
}

internal fun publicManifestRelease(manifest: String, channel: String, version: String): AndroidRelease? {
    val entry = Regex("""[0-9a-fA-F]{64}\s+(codex-suixing-${Regex.escape(channel)}-(\d+)-${Regex.escape(version)}\.apk)""")
    return manifest.lineSequence().mapNotNull { line ->
        val m = entry.matchEntire(line.trim()) ?: return@mapNotNull null
        val code = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        AndroidRelease(code, version, -1, m.groupValues[1])
    }.maxByOrNull { it.code }
}

internal fun checkGithub(response: Response) {
    when (response.code) {
        200 -> return
        404 -> error("GitHub 上没有找到此版本或安装包。")
        403, 429 -> error("GitHub 暂时限制了请求，请稍后重试。")
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
                release.optString("tag_name") == "v$version") AndroidRelease(code, version, size, match.value) else null
        }
    }.maxByOrNull { it.code }
}

internal fun openPublicFile(version: String, fileName: String, client: OkHttpClient = github): Response {
    val request = Request.Builder().url("$PUBLIC_RELEASES/download/v$version/$fileName").build()
    val response = client.newCall(request).execute()
    if (!response.isRedirect) return response
    val url = response.use { it.header("Location")?.let(request.url::resolve) }
    check(url != null && url.isHttps && url.host.endsWith(".githubusercontent.com")) { "GitHub 返回了未知下载地址。" }
    return client.newCall(Request.Builder().url(url).build()).execute()
}

private suspend fun downloadUpdate(context: Context, release: AndroidRelease, progress: (Float) -> Unit) {
    val destination = cachedUpdate(context)
    val folder = checkNotNull(destination.parentFile)
    check(folder.mkdirs() || folder.isDirectory) { "无法创建更新缓存。" }
    val temp = File.createTempFile("download-", ".apk", folder)
    try {
        progress(0f)
        val job = currentCoroutineContext()
        openPublicFile(release.version, release.fileName).use { response ->
            checkGithub(response)
            val body = checkNotNull(response.body) { "GitHub 返回了空的更新包。" }
            val size = if (release.size > 0) release.size else body.contentLength()
            check(size in 1..MAX_APK_BYTES && body.contentLength() in listOf(-1L, size)) { "更新包大小不匹配，请重新检查更新。" }
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
                    check(total <= size) { "更新包超过预期大小。" }
                    output.write(buffer, 0, count)
                    val percent = (total * 100 / size).toInt()
                    if (percent != lastPercent) { progress(percent / 100f); lastPercent = percent }
                }
            }
            check(total == size) { "更新包下载不完整。" }
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
