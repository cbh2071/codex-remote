package cn.codexremote.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

class TailnetHandshakeTest {
    @Test fun bootstrapPinsTheActualWindowsBridgeCertificate() {
        val args = InstrumentationRegistry.getArguments()
        val lan = args.getString("bridgeLan")
        assumeTrue(lan != null)
        // Test only the Android TLS layer, using the host's LAN listener as the transport.
        val factory = object : SocketFactory() {
            override fun createSocket() = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) = super.connect(InetSocketAddress(lan, 48761), timeout)
            }
            override fun createSocket(h: String, p: Int): Socket = error("unexpected")
            override fun createSocket(h: InetAddress, p: Int): Socket = error("unexpected")
            override fun createSocket(h: String, p: Int, a: InetAddress, l: Int): Socket = error("unexpected")
            override fun createSocket(h: InetAddress, p: Int, a: InetAddress, l: Int): Socket = error("unexpected")
        }
        val candidate = discoverBridge(TailnetPeer("test", "PC", "100.64.0.1", true), factory)
        assertEquals(args.getString("expectedPin"), candidate.fingerprint)
        val api = HostApi(candidate, factory)
        try {
            val failure = runCatching { api.request("/api/v1/tailnet-pair", org.json.JSONObject().put("hostId", candidate.id), false) }.exceptionOrNull()
            assertTrue("The LAN endpoint must reject enrollment", failure?.message?.contains("仅支持 Tailscale") == true)
        } finally { api.close() }
    }
}
