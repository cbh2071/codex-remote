package cn.codexremote.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class TurnControlDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun distinguishesFollowUpFromSteeringAndPinsStopToItsTurn() {
        val repo = (ui.activity.application as RemoteApplication).repository
        val key = ThreadKey("ui-turn-test", "test-thread")
        val host = HostState(Host(key.hostId, "Test PC", "", "", "", ""), online = true, compatible = true, instance = "instance")
        fun showTurn(id: String, live: Boolean) {
            repo.histories.value = repo.histories.value + (key to JSONObject().put("activeTurnId", id)
                .put("instanceId", host.instance).put("live", live).put("desktopTools", !live).put("canSendText", true))
        }
        try {
            ui.runOnUiThread {
                showTurn("first", false)
                ui.activity.setContent { ThreadScreen(key, repo, host) }
            }
            ui.onNodeWithContentDescription("发送后续消息").assertExists()
            ui.onNodeWithText("停止", substring = false).assertDoesNotExist()
            ui.runOnIdle { showTurn("first", true) }
            ui.onNodeWithContentDescription("引导本轮").assertExists()
            ui.onNodeWithText("停止").performClick()
            ui.onNodeWithText("停止本轮").assertIsEnabled()
            ui.runOnIdle { showTurn("second", true) }
            ui.onNodeWithText("停止本轮").assertIsNotEnabled()
        } finally {
            ui.runOnUiThread { repo.histories.value = repo.histories.value - key }
        }
    }
}
