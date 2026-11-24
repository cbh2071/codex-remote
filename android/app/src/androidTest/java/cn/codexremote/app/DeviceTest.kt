package cn.codexremote.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DeviceTest {
    @Test fun launchAndNativeKeystore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(2000)
        val repository = (context.applicationContext as RemoteApplication).repository
        repository.store.put("device-test-only", "Keystore roundtrip")
        assertEquals("Keystore roundtrip", repository.store.get("device-test-only", ""))
        assertNotNull(instrumentation.uiAutomation.rootInActiveWindow)
    }
    @Test fun twoIsolatedBridgeConnectionsOnOnePc() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val encoded = arguments.getString("pairingData")
        val path = arguments.getString("pairingFile")
        val codes = if (encoded != null) JSONArray(String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)) else if (path != null) JSONArray(File(path).readText()) else return@runBlocking
        val repository = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RemoteApplication).repository
        require(repository.states.value.isEmpty()) { "Use an empty test profile, never an app paired with production PCs" }
        val pairedIds = mutableSetOf<String>()
        repository.foreground(true)
        try {
            for (i in 0 until codes.length()) { val code = codes.getJSONObject(i); repository.pair(code.toString()); pairedIds.add(code.getString("hostId")) }
            withTimeout(45000) { while (repository.states.value.values.count { it.online && it.threads.isNotEmpty() } != 2) delay(300) }
            val hosts = repository.states.value.values.toList()
            assertNotEquals(hosts[0].host.id, hosts[1].host.id)
            val common = hosts[0].threads.map { it.getString("id") }.intersect(hosts[1].threads.map { it.getString("id") }.toSet()).first()
            repository.watch(ThreadKey(hosts[0].host.id, common)); repository.watch(ThreadKey(hosts[1].host.id, common))
            withTimeout(20000) { while (repository.histories.value.keys.count { it.threadId == common } != 2) delay(200) }
            assertNotEquals(repository.states.value[hosts[0].host.id]!!.instance, repository.states.value[hosts[1].host.id]!!.instance)
            arguments.getString("sidebarThread")?.let { id ->
                val host = hosts[0]
                val key = ThreadKey(host.host.id, id)
                repository.watch(key)
                try {
                    withTimeout(30000) { while (repository.histories.value[key] == null) delay(200) }
                    assertTrue(repository.histories.value.getValue(key).optBoolean("canSendText"))
                    assertTrue(repository.models(key).getJSONArray("models").length() > 0)
                    val directory = arguments.getString("sidebarDir").orEmpty()
                    val file = arguments.getString("sidebarFile")!!
                    val listing = repository.sidebar(key, host.instance, "list", directory)
                    assertTrue(listing.getJSONArray("entries").objects().any { it.getString("name") == file })
                    val path = listOf(directory, file).filter(String::isNotEmpty).joinToString("/")
                    assertTrue(repository.sidebar(key, host.instance, "open", path).getString("text").isNotBlank())
                    assertNotNull(repository.sidebar(key, host.instance, "children").getJSONArray("entries"))
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val context = instrumentation.targetContext
                    context.startActivity(android.content.Intent(context, MainActivity::class.java)
                        .putExtra("hostId", key.hostId).putExtra("threadId", key.threadId).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    fun find(node: android.view.accessibility.AccessibilityNodeInfo?, label: String): android.view.accessibility.AccessibilityNodeInfo? {
                        if (node == null) return null
                        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
                        return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), label) }
                    }
                    suspend fun visible(label: String): android.view.accessibility.AccessibilityNodeInfo {
                        repeat(75) { find(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return it }; delay(200) }
                        error("Missing visible control: " + label)
                    }
                    var button: android.view.accessibility.AccessibilityNodeInfo? = visible("项目文件与关联会话")
                    while (button != null && !button.isClickable) button = button.parent
                    assertTrue(button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
                    visible(directory)
                    visible("侧边聊天")
                    visible("子智能体")
                } finally { repository.unwatch(key) }
            }
        } finally { pairedIds.forEach(repository::remove); repository.foreground(false) }
    }
}
