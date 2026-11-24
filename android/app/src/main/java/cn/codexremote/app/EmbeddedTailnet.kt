package cn.codexremote.app

import android.content.Context
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal interface TailnetLibrary : Library {
    fun TsnetSetAndroidDirs(dir: String)
    fun TsnetNewServer(): Int
    fun TsnetSetDir(id: Int, dir: String): Int
    fun TsnetSetHostname(id: Int, name: String): Int
    fun TsnetSetLogFD(id: Int, fd: Int): Int
    fun TsnetLoopback(id: Int, address: ByteArray, length: Long, proxy: ByteArray, local: ByteArray): Int
    fun TsnetClose(id: Int): Int
}

internal class EmbeddedTailnet(context: Context) {
    private val prefs = context.getSharedPreferences("embedded-tailnet", Context.MODE_PRIVATE)
    private val directory = File(context.noBackupFilesDir, "tailnet")
    private val library by lazy { Native.load("tailscale", TailnetLibrary::class.java) }
    private var handle = -1
    private var endpoint: Endpoint? = null
    val enabled get() = prefs.getBoolean("enabled", false)
    private val local = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).build()
    internal class Endpoint(val port: Int, val proxyPassword: String, val localPassword: String)

    @Synchronized fun setEnabled(value: Boolean) {
        check(prefs.edit().putBoolean("enabled", value).commit())
        if (!value && handle >= 0) {
            endpoint = null
            library.TsnetClose(handle)
            handle = -1
            local.connectionPool.evictAll()
        }
    }

    @Synchronized private fun start(): Endpoint {
        if (!enabled) throw IOException("内置 Tailscale 已关闭")
        endpoint?.let { return it }
        val path = directory.apply { mkdirs() }.absolutePath
        val api = try { library } catch (e: LinkageError) { throw IOException("当前手机无法载入内置 Tailscale", e) }
        api.TsnetSetAndroidDirs(path)
        val id = api.TsnetNewServer()
        if (id < 0) throw IOException("内置 Tailscale 初始化失败")
        try {
            check(api.TsnetSetDir(id, path) == 0 && api.TsnetSetHostname(id, "codex-mobile") == 0)
            check(api.TsnetSetLogFD(id, -1) == 0)
            val addr = ByteArray(256)
            val proxy = ByteArray(33)
            val control = ByteArray(33)
            if (api.TsnetLoopback(id, addr, addr.size.toLong(), proxy, control) != 0) throw IOException("内置网络启动失败")
            val address = Native.toString(addr)
            require(address.substringBeforeLast(':') == "127.0.0.1")
            return Endpoint(address.substringAfterLast(':').toInt(), Native.toString(proxy), Native.toString(control)).also {
                handle = id
                endpoint = it
            }
        } catch (e: Exception) { api.TsnetClose(id); throw e }
    }

    @Synchronized fun status(login: Boolean = false, peers: Boolean = false): JSONObject {
        val ep = start()
        fun call(path: String, post: Boolean): String {
            val request = Request.Builder().url("http://127.0.0.1:${ep.port}/localapi/v0/$path")
                .header("Host", "local-tailscaled.sock").header("Sec-Tailscale", "localapi")
                .header("Authorization", Credentials.basic("", ep.localPassword))
            if (post) request.post(ByteArray(0).toRequestBody())
            return local.newCall(request.build()).execute().use {
                if (!it.isSuccessful) throw IOException("内置网络状态异常 (${it.code})")
                it.body?.string().orEmpty()
            }
        }
        if (login) call("login-interactive", true)
        return JSONObject(call("status?peers=$peers", false))
    }

    fun socketFactory(url: String): SocketFactory {
        val uri = java.net.URI(bridgeUrl(url))
        require(connectionKind(url) == "Tailscale")
        val peer = InetSocketAddress(InetAddress.getByName(uri.host), uri.port)
        return object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    if (endpoint != peer) throw IOException("桥接地址已变化")
                    val ep = start()
                    try {
                        super.connect(InetSocketAddress("127.0.0.1", ep.port), timeout)
                        val prior = soTimeout
                        soTimeout = if (timeout > 0) timeout else 15000
                        authenticateAndConnect(this, peer, ep.proxyPassword)
                        soTimeout = prior
                    } catch (e: Exception) { close(); throw e }
                }
            }
            override fun createSocket(h: String, p: Int) = createSocket().apply { connect(InetSocketAddress(h, p)) }
            override fun createSocket(h: InetAddress, p: Int) = createSocket().apply { connect(InetSocketAddress(h, p)) }
            override fun createSocket(h: String, p: Int, a: InetAddress, l: Int) = createSocket().apply { bind(InetSocketAddress(a,l)); connect(InetSocketAddress(h,p)) }
            override fun createSocket(h: InetAddress, p: Int, a: InetAddress, l: Int) = createSocket().apply { bind(InetSocketAddress(a,l)); connect(InetSocketAddress(h,p)) }
        }
    }
}

private fun authenticateAndConnect(socket: Socket, peer: InetSocketAddress, password: String) {
    val out = socket.getOutputStream()
    val input = DataInputStream(socket.getInputStream())
    out.write(byteArrayOf(5, 1, 2))
    if (input.readUnsignedShort() != 0x0502) throw IOException("内置网络握手失败")
    val user = "tsnet".toByteArray()
    val pass = password.toByteArray()
    out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
    if (input.readUnsignedShort() != 0x0100) throw IOException("内置网络认证失败")
    out.write(byteArrayOf(5, 1, 0, 1) + peer.address.address + byteArrayOf((peer.port ushr 8).toByte(), peer.port.toByte()))
    if (input.readUnsignedShort() != 0x0500) throw IOException("内置 Tailscale 无法连接电脑")
    input.readUnsignedByte()
    val addrSize = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> throw IOException("内置网络回复异常") }
    input.readFully(ByteArray(addrSize + 2))
}
