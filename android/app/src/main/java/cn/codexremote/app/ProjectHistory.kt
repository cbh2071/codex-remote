package cn.codexremote.app

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal data class ProjectIndex(
    val names: Map<String, String> = emptyMap(),
    val threads: List<JSONObject> = emptyList(),
    val loading: Boolean = true,
    val error: String = ""
)

@Composable
internal fun projectIndex(repo: Repository, host: HostState, retry: Int): ProjectIndex {
    var index by remember(host.host.id, host.instance) { mutableStateOf(ProjectIndex()) }
    LaunchedEffect(host.host.id, host.instance, host.online, retry) {
        if (!host.online) { index = index.copy(loading = false); return@LaunchedEffect }
        index = index.copy(loading = true, error = "")
        try {
            val names = try {
                repo.projects(host.host.id).projects.associate { "id/${it.getString("id")}" to it.getString("name") }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
            val rows = linkedMapOf<String, JSONObject>()
            val seen = mutableSetOf<String>()
            var cursor = ""
            do {
                check(seen.add(cursor)) { "电脑返回了重复的历史页" }
                val page = repo.historyPage(host.host.id, host.instance, cursor)
                for (thread in page.getJSONArray("threads").objects()) rows[thread.getString("id")] = thread
                cursor = if (page.isNull("nextCursor")) "" else page.getString("nextCursor")
                index = ProjectIndex(names, rows.values.toList(), loading = cursor.isNotEmpty())
            } while (cursor.isNotEmpty())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { index = index.copy(loading = false, error = e.message ?: "读取历史失败") }
    }
    return index
}
