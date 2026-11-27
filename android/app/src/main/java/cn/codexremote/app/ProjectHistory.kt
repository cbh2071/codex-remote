package cn.codexremote.app

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ProjectIndex(
    val names: Map<String, String> = emptyMap(),
    val threads: List<JSONObject> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
    val instance: String = "",
    val loadedAt: Long = 0,
    val revision: Int = -1
)

// Cold starts need navigation metadata only. Never retain message bodies or action state here.
internal fun ProjectIndex.snapshot(): String {
    val rows = JSONArray()
    val projects = JSONObject()
    for (thread in threads.sortedByDescending { it.optLong("updatedAt") }.take(200)) {
        val row = JSONObject().put("id", thread.getString("id")).put("title", thread.optString("title").take(200))
            .put("updatedAt", thread.optLong("updatedAt"))
        thread.optString("projectId").takeIf { it.isNotBlank() && it != "null" }?.let { id ->
            row.put("projectId", id)
            names["id/$id"]?.let { projects.put("id/$id", it.take(200)) }
        }
        rows.put(row)
    }
    return JSONObject().put("names", projects).put("rows", rows).toString()
}

internal fun restoreIndex(snapshot: String): ProjectIndex {
    val data = JSONObject(snapshot)
    val names = data.getJSONObject("names")
    return ProjectIndex(names.keys().asSequence().associateWith { names.getString(it) }, data.getJSONArray("rows").objects())
}

@Composable
internal fun projectIndex(repo: Repository, host: HostState, retry: Int): ProjectIndex {
    val foreground by repo.inForeground.collectAsState()
    var index by remember(host.host.id, host.host.fingerprint) { mutableStateOf(repo.cachedIndex(host.host.id)) }
    LaunchedEffect(host.host.id, host.instance, host.online, retry, foreground) {
        if (!foreground || !host.online) { index = index.copy(loading = false); return@LaunchedEffect }
        val cached = repo.cachedIndex(host.host.id)
        if (cached.instance == host.instance && cached.revision == retry &&
            android.os.SystemClock.elapsedRealtime() - cached.loadedAt < 30_000) {
            index = cached
            return@LaunchedEffect
        }
        index = index.copy(loading = true, error = "")
        try {
            val names = try {
                repo.projects(host.host.id).projects.associate { "id/${it.getString("id")}" to it.getString("name") }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { index.names }
            val rows = linkedMapOf<String, JSONObject>()
            val seen = mutableSetOf<String>()
            var cursor = ""
            do {
                check(seen.add(cursor)) { "电脑返回了重复的历史页" }
                val page = repo.historyPage(host.host.id, host.instance, cursor)
                for (thread in page.getJSONArray("threads").objects()) rows[thread.getString("id")] = thread
                cursor = if (page.isNull("nextCursor")) "" else page.getString("nextCursor")
                if (index.threads.isEmpty() || cursor.isEmpty()) index = ProjectIndex(names, rows.values.toList(), loading = cursor.isNotEmpty())
            } while (cursor.isNotEmpty())
            val complete = index.copy(instance = host.instance, loadedAt = android.os.SystemClock.elapsedRealtime(), revision = retry)
            withContext(Dispatchers.IO) { repo.saveIndex(host, complete) }
            index = complete
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { index = index.copy(loading = false, error = e.message ?: "读取历史失败") }
    }
    return index
}
