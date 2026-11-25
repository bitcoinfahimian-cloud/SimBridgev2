package com.simbridge.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class OpenInternetServer(
    private val context: Context,
    private val port: Int = 8118
) {
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    @Volatile var localSitesDirect: Boolean = true
    @Volatile var routeThroughVpn: Boolean = true
    @Volatile var hotspotSsid: String = ""
    @Volatile var hotspotPassword: String = ""

    val setupToken: String = loadOrCreateSecret("proxy_setup_token", 16)
    val proxyUser: String = "simbridge"
    val proxyPass: String = loadOrCreateSecret("proxy_auth_pass", 12)

    private val allowedClientIps = ConcurrentHashMap<String, Long>()

    private val directDomains = setOf(
        "shaparak.ir", "snapp.ir", "digikala.com", "divar.ir",
        "torob.com", "bale.ai", "eitaa.com", "aparat.com", "ble.ir"
    )

    private fun loadOrCreateSecret(prefKey: String, byteLen: Int): String {
        val prefs = context.getSharedPreferences("simbridge_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString(prefKey, null)
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(byteLen).also { SecureRandom().nextBytes(it) }
        val secret = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(prefKey, secret).apply()
        return secret
    }

    fun getSetupUrl(hostIp: String = detectBestLocalIp()): String =
        "http://$hostIp:$port/s/$setupToken/setup"

    fun getPacUrl(hostIp: String = detectBestLocalIp()): String =
        "http://$hostIp:$port/s/$setupToken/proxy.pac"

    fun detectBestLocalIp(): String {
        val candidates = mutableListOf<Pair<Int, String>>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "192.168.43.1"
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val priority = when {
                    name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name.startsWith("rndis") || name.startsWith("usb") -> 2
                    name.startsWith("wlan") -> 3
                    else -> 4
                }
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        candidates.add(priority to (addr.hostAddress ?: continue))
                    }
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second ?: "192.168.43.1"
    }

    fun isAndroidVpnActive(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun start() {
        stop()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope?.launch {
            runCatching {
                val bindIp = detectBestLocalIp()
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    runCatching { bind(InetSocketAddress(bindIp, port)) }
                        .onFailure { bind(InetSocketAddress("0.0.0.0", port)) }
                }
                serverSocket = ss
                while (isActive) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    launch { handleClient(client) }
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()
            val remoteIp = sock.inetAddress?.hostAddress ?: return

            val firstLine = readLine(input) ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = mutableListOf<String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                headers.add(line)
            }

            val hostIp = detectBestLocalIp()
            val secretPrefix = "/s/$setupToken/"

            when {
                target.startsWith(secretPrefix) -> {
                    allowedClientIps[remoteIp] = System.currentTimeMillis()
                    val subPath = target.removePrefix(secretPrefix)
                    when {
                        subPath.startsWith("proxy.pac") -> {
                            sendHttpResponse(output, "application/x-ns-proxy-autoconfig", buildPacScript(hostIp))
                        }
                        subPath.startsWith("simbridge.mobileconfig") -> {
                            sendHttpResponse(output, "application/x-apple-aspen-config", buildAppleMobileConfig(hostIp))
                        }
                        subPath.startsWith("check") -> {
                            sendHttpResponse(output, "application/json", "{\"ok\":true,\"vpn\":${isAndroidVpnActive()}}")
                        }
                        else -> {
                            sendHttpResponse(output, "text/html; charset=utf-8", buildSetupHtml(hostIp))
                        }
                    }
                }

                method == "CONNECT" -> {
                    if (!isAuthorizedProxyClient(remoteIp, headers)) {
                        sendProxyAuthRequired(output)
                        return
                    }
                    handleHttpsConnect(target, input, output)
                }

                target.startsWith("http://") -> {
                    if (!isAuthorizedProxyClient(remoteIp, headers)) {
                        sendProxyAuthRequired(output)
                        return
                    }
                    handlePlainHttpProxy(method, target, headers, input, output)
                }

                else -> {
                    output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                }
            }
        }
    }

    private fun isAuthorizedProxyClient(remoteIp: String, headers: List<String>): Boolean {
        if (remoteIp == "127.0.0.1" || allowedClientIps.containsKey(remoteIp)) return true
        val authHeader = headers.firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) } ?: return false
        val token = authHeader.substringAfter(":", "").trim()
        if (!token.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(Base64.decode(token.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return false
        return decoded == "$proxyUser:$proxyPass"
    }

    private fun sendProxyAuthRequired(output: OutputStream) {
        val resp = "HTTP/1.1 407 Proxy Authentication Required\r\n" +
            "Proxy-Authenticate: Basic realm=\"SimBridge\"\r\n" +
            "Content-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(resp.toByteArray())
        output.flush()
    }

    private fun handleHttpsConnect(hostPort: String, clientIn: InputStream, clientOut: OutputStream) {
        val idx = hostPort.lastIndexOf(':')
        val host = if (idx > 0) hostPort.substring(0, idx) else hostPort
        val targetPort = if (idx > 0) hostPort.substring(idx + 1).toIntOrNull() ?: 443 else 443

        val upstream = runCatching {
            Socket().apply { connect(InetSocketAddress(host, targetPort), 10_000) }
        }.getOrElse {
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }

        upstream.use { remote ->
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()

            val t1 = Thread { runCatching { clientIn.copyTo(remote.getOutputStream()) } }
            val t2 = Thread { runCatching { remote.getInputStream().copyTo(clientOut) } }
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        }
    }

    private fun handlePlainHttpProxy(
        method: String,
        fullUrl: String,
        headers: List<String>,
        clientIn: InputStream,
        clientOut: OutputStream
    ) {
        val url = runCatching { URL(fullUrl) }.getOrNull() ?: return
        val remotePort = if (url.port > 0) url.port else 80
        val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")

        val contentLength = headers
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toLongOrNull() ?: 0L
        val isChunked = headers
            .firstOrNull { it.startsWith("Transfer-Encoding:", ignoreCase = true) }
            ?.contains("chunked", ignoreCase = true) == true
        val hasHost = headers.any { it.startsWith("Host:", ignoreCase = true) }

        runCatching {
            Socket().apply { connect(InetSocketAddress(url.host, remotePort), 10_000) }.use { remote ->
                val remoteOut = remote.getOutputStream()
                val req = StringBuilder("$method $path HTTP/1.1\r\n")
                if (!hasHost) {
                    val hostHdr = if (url.port > 0) "${url.host}:${url.port}" else url.host
                    req.append("Host: ").append(hostHdr).append("\r\n")
                }
                headers.filterNot {
                    it.startsWith("Proxy-", ignoreCase = true) ||
                        it.startsWith("Connection:", ignoreCase = true)
                }.forEach {
                    req.append(it).append("\r\n")
                }
                req.append("Connection: close\r\n\r\n")
                remoteOut.write(req.toString().toByteArray(Charsets.UTF_8))

                if (isChunked) {
                    forwardChunkedBody(clientIn, remoteOut)
                } else if (contentLength > 0L) {
                    copyExactBytes(clientIn, remoteOut, contentLength)
                }
                remoteOut.flush()

                remote.getInputStream().copyTo(clientOut)
                clientOut.flush()
            }
        }
    }

    private fun copyExactBytes(input: InputStream, output: OutputStream, totalBytes: Long) {
        val buf = ByteArray(8192)
        var remaining = totalBytes
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            output.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun forwardChunkedBody(input: InputStream, output: OutputStream) {
        while (true) {
            val sizeLine = readLine(input) ?: break
            output.write((sizeLine + "\r\n").toByteArray(Charsets.US_ASCII))
            val chunkSize = sizeLine.substringBefore(";").trim().toLongOrNull(16) ?: break
            if (chunkSize == 0L) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    output.write((trailer + "\r\n").toByteArray(Charsets.US_ASCII))
                    if (trailer.isEmpty()) break
                }
                break
            }
            copyExactBytes(input, output, chunkSize)
            val crlf = readLine(input)
            output.write("\r\n".toByteArray(Charsets.US_ASCII))
            if (crlf == null) break
        }
    }

    private fun buildPacScript(hostIp: String): String {
        val domainsJoined = directDomains.joinToString("|")
        val vpnFlag = routeThroughVpn && isAndroidVpnActive()
        return """
            var VPN = $vpnFlag;
            var LOCAL = $localSitesDirect;
            var CC = "ir";
            var D = {};
            "$domainsJoined".split("|").forEach(function(d){ if(d) D[d]=1; });
            var P = "PROXY $hostIp:$port; DIRECT";
            function FindProxyForURL(url, host) {
              host = host.toLowerCase();
              if (isPlainHostName(host) || /^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2[0-9]|3[01])\.)/.test(host) ||
                  host == "localhost" || shExpMatch(host, "*.local")) return "DIRECT";
              if (!VPN) return "DIRECT";
              if (LOCAL) {
                if (host == CC || shExpMatch(host, "*." + CC)) return "DIRECT";
                var h = host;
                for (;;) {
                  if (D.hasOwnProperty(h)) return "DIRECT";
                  var i = h.indexOf(".");
                  if (i < 0) break;
                  h = h.substring(i + 1);
                }
              }
              return P;
            }
        """.trimIndent()
    }

    private fun buildAppleMobileConfig(hostIp: String): String {
        val ssidEsc = xmlEscape(hotspotSsid)
        val passEsc = xmlEscape(hotspotPassword)
        val pacUrl = xmlEscape(getPacUrl(hostIp))
        val passwordEntry = if (passEsc.isNotEmpty()) {
            "<key>EncryptionType</key><string>WPA2</string>\n              <key>Password</key><string>$passEsc</string>"
        } else {
            "<key>EncryptionType</key><string>None</string>"
        }
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>PayloadContent</key>
          <array>
            <dict>
              <key>PayloadType</key><string>com.apple.wifi.managed</string>
              <key>PayloadVersion</key><integer>1</integer>
              <key>PayloadIdentifier</key><string>com.simbridge.wifi</string>
              <key>PayloadUUID</key><string>${UUID.randomUUID()}</string>
              <key>PayloadDisplayName</key><string>SimBridge Wi-Fi</string>
              <key>SSID_STR</key><string>$ssidEsc</string>
              <key>HIDDEN_NETWORK</key><false/>
              <key>AutoJoin</key><true/>
              $passwordEntry
              <key>ProxyType</key><string>Auto</string>
              <key>ProxyPACURL</key><string>$pacUrl</string>
              <key>ProxyPACFallbackAllowed</key><true/>
            </dict>
          </array>
          <key>PayloadDisplayName</key><string>SimBridge Internet</string>
          <key>PayloadIdentifier</key><string>com.simbridge.net</string>
          <key>PayloadOrganization</key><string>SimBridge</string>
          <key>PayloadRemovalDisallowed</key><false/>
          <key>PayloadType</key><string>Configuration</string>
          <key>PayloadUUID</key><string>${UUID.randomUUID()}</string>
          <key>PayloadVersion</key><integer>1</integer>
        </dict>
        </plist>
        """.trimIndent()
    }

    private fun buildSetupHtml(hostIp: String): String {
        val pacUrl = getPacUrl(hostIp)
        val profileHref = "/s/$setupToken/simbridge.mobileconfig"
        val profileCard = if (hotspotSsid.isNotBlank()) {
            """<div class="card"><h2>SimBridge Open Internet</h2>
            <p>Install the Wi-Fi &amp; Auto-Proxy profile once on your iPhone (SSID: <code>${xmlEscape(hotspotSsid)}</code>):</p>
            <a class="btn" href="$profileHref">Install Profile</a></div>"""
        } else {
            """<div class="card"><h2>SimBridge Open Internet</h2>
            <p>Enter your Android Hotspot SSID &amp; Password in the SimBridge app's Internet tab to enable one-tap Wi-Fi profile install, or use the Auto-Proxy URL below:</p></div>"""
        }
        return """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>SimBridge Open Internet</title>
        <style>body{margin:0;background:#0b0c14;color:#f2f4f8;font:16px/1.6 -apple-system,sans-serif;padding:20px}
        .card{background:#151722;border-radius:20px;padding:20px;max-width:520px;margin:0 auto 14px}
        .btn{display:inline-block;background:#7b68ee;color:#fff;text-decoration:none;padding:12px 22px;border-radius:50px;font-weight:600;margin-top:10px}
        code{background:#1e2130;padding:3px 7px;border-radius:6px;direction:ltr;display:inline-block;word-break:break-all}</style></head><body>
        $profileCard
        <div class="card"><b>Wi-Fi / USB-C Cable (iPhone 15+):</b>
        <p>Settings → Wi-Fi or Ethernet → Configure Proxy → Automatic:<br>
        <code>$pacUrl</code></p>
        <p style="font-size:13px;color:#8c92a4">This iPhone's IP has been authorized automatically.</p></div>
        </body></html>
        """.trimIndent()
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun sendHttpResponse(out: OutputStream, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nCache-Control: no-cache\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.size() == 0) null else sb.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
        }
        return sb.toString("ISO-8859-1")
    }
}
