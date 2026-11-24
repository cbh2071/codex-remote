package cn.codexremote.app

import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

internal data class TailnetPeer(val id: String, val name: String, val ip: String, val online: Boolean) {
    val url get() = bridgeUrl("https://$ip:48761")
}

internal fun tailnetComputers(status: JSONObject): List<TailnetPeer> {
    require(status.optString("BackendState") == "Running") { "请先登录内置 Tailscale" }
    val self = status.getJSONObject("Self")
    val owner = self.optLong("UserID")
    require(owner > 0 && self.optJSONArray("Tags").let { it == null || it.length() == 0 }) { "请使用个人 Tailscale 账号登录" }
    val peers = status.optJSONObject("Peer") ?: return emptyList()
    return peers.keys().asSequence().mapNotNull { key ->
        val p = peers.getJSONObject(key)
        if (p.optString("OS") != "windows" || p.optLong("UserID") != owner || p.optBoolean("ShareeNode") ||
            p.optBoolean("Expired") || (p.optJSONArray("Tags")?.length() ?: 0) > 0) return@mapNotNull null
        val ips = p.optJSONArray("TailscaleIPs") ?: return@mapNotNull null
        val ip = (0 until ips.length()).map { ips.optString(it) }.firstOrNull {
            it.matches(Regex("[0-9.]+")) && runCatching { connectionKind("https://$it:48761") == "Tailscale" }.getOrDefault(false)
        } ?: return@mapNotNull null
        val id = p.optString("ID")
        if (id.isBlank() || id == self.optString("ID")) return@mapNotNull null
        TailnetPeer(id, p.optString("HostName").ifBlank { ip }, ip, p.optBoolean("Online"))
    }.sortedWith(compareByDescending<TailnetPeer> { it.online }.thenBy { it.name }.thenBy { it.ip }).toList()
}

// Bootstrap the application pin inside an already authenticated tsnet connection to the selected node.
internal fun discoverBridge(peer: TailnetPeer, sockets: SocketFactory): Host {
    var bridge: Host? = null
    val verifier = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val cert = chain.singleOrNull() ?: throw java.security.cert.CertificateException("无法识别这台电脑的随行桥接证书")
            cert.checkValidity()
            cert.verify(cert.publicKey)
            val match = Regex("CN=CodexRemote-([a-fA-F0-9-]{36})").matchEntire(cert.subjectX500Principal.name)
                ?: throw java.security.cert.CertificateException("该设备没有提供随行桥接证书")
            val id = UUID.fromString(match.groupValues[1]).toString()
            val pin = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
            bridge = Host(id, peer.name, peer.url, pin, "", "")
        }
    }
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(verifier), null) }
    sockets.createSocket().use { socket ->
        socket.connect(InetSocketAddress(peer.ip, 48761), 8000)
        socket.soTimeout = 8000
        (tls.socketFactory.createSocket(socket, peer.ip, 48761, true) as SSLSocket).use { it.startHandshake() }
    }
    return requireNotNull(bridge)
}
