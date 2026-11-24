package cn.codexremote.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TailnetPeersTest {
    @Test fun discoverySeparatesSameNamedComputersAndFiltersOtherOwners() {
        val status = JSONObject("""{"BackendState":"Running","Self":{"ID":"self","UserID":23},"Peer":{}}""")
        val peers = status.getJSONObject("Peer")
        fun add(id: String, ip: String, online: Boolean) = JSONObject()
            .put("ID", id).put("OS", "windows").put("UserID", 23).put("HostName", "PC")
            .put("Online", online).put("TailscaleIPs", org.json.JSONArray(listOf(ip))).also { peers.put(id, it) }
        add("offline", "100.64.1.2", false)
        add("online", "100.64.1.3", true)
        add("other", "100.64.1.4", true).put("UserID", 55)
        add("tagged", "100.64.1.5", true).put("Tags", org.json.JSONArray(listOf("tag:service")))
        add("lan", "192.168.1.1", true)
        add("self", "100.64.1.1", true)
        assertEquals(listOf("online","offline"), tailnetComputers(status).map { it.id })
    }
}
