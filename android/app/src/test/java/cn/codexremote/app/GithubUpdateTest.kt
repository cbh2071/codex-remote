package cn.codexremote.app

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GithubUpdateTest {
    private fun release(code: Int, channel: String = "preview", state: String = "uploaded"): JSONObject {
        val asset = JSONObject().put("name", "codex-suixing-$channel-$code-1.0.$code.apk")
            .put("state", state).put("size", 500).put("id", code)
        return JSONObject().put("tag_name", "v1.0.$code").put("prerelease", true)
            .put("assets", JSONArray().put(asset))
    }

    @Test fun selectsHighestCompatiblePublishedAssetIncludingPrereleases() {
        val entries = JSONArray().put(release(14)).put(release(19).put("draft", true))
            .put(release(20, "stable")).put(release(15, state = "starter"))
            .put(release(17).put("tag_name", "v1.0.2")).put(release(13))
        assertEquals(14, newestRelease(entries, "preview")?.code)
        assertNull(newestRelease(JSONArray(), "preview"))
    }

    @Test fun privateDownloadDoesNotForwardTokenToAssetHost() {
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val req = chain.request()
            hosts += req.url.host
            val response = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .message("test").body("test".toResponseBody())
            if (req.url.host == "api.github.com") {
                assertEquals("Bearer test", req.header("Authorization"))
                assertEquals("application/octet-stream", req.header("Accept"))
                response.code(302).header("Location", "https://release-assets.githubusercontent.com/file").build()
            } else {
                assertNull(req.header("Authorization"))
                response.code(200).build()
            }
        }.build()
        openAsset(7, "test", client).use { checkGithub(it) }
        assertEquals(listOf("api.github.com", "release-assets.githubusercontent.com"), hosts)
    }

    @Test fun anonymousRequestAndDeniedRepository() {
        val request = githubRequest("?page=1", "", "application/vnd.github+json")
        assertNull(request.header("Authorization"))
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(404).message("Not Found").build()
        assertTrue(runCatching { checkGithub(response) }.exceptionOrNull()?.message.orEmpty().contains("只读权限"))
    }
}
