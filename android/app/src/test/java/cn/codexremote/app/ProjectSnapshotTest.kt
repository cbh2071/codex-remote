package cn.codexremote.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProjectSnapshotTest {
    @Test fun keepsRecentNavigationWithoutRestoringLiveState() {
        val rows = (1..220).map { n -> JSONObject().put("id", "$n").put("title", "会话 $n")
            .put("summary", "private body").put("status", "active").put("cwd", "private path")
            .put("projectId", "p").put("updatedAt", n) }
        val saved = ProjectIndex(mapOf("id/p" to "演示项目"), rows, instance = "bridge").snapshot()
        val index = restoreIndex(saved)
        assertEquals("演示项目", index.names["id/p"])
        assertEquals(200, index.threads.size)
        assertEquals("220", index.threads.first().getString("id"))
        assertFalse(saved.contains("private"))
        assertFalse(index.threads.first().has("status"))
        assertEquals("", index.instance)
    }
}
