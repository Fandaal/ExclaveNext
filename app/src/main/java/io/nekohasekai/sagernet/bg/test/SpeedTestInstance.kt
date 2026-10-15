/******************************************************************************
 *                                                                            *
 * Exclave Next addition: speed test for a single proxy profile.              *
 *                                                                            *
 * Ported from WhiteListVPN.py:                                               *
 *   - start_singbox_proxy  -> a V2RayInstance with a local mixed/socks in    *
 *   - measure_speed        -> ping + download measurement through that proxy *
 *                                                                            *
 * The core is started in-process (libexclavecore) exactly like the URL test  *
 * (V2RayTestInstance). We reuse the test config (forTest=true) which exposes  *
 * a local SOCKS inbound, then drive traffic through it with a plain          *
 * HttpURLConnection bound to that SOCKS proxy.                               *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.test

import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.bg.proto.V2RayInstance
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/** Result of a speed probe. Speeds are in Mbit/s, ping in ms (0 = dead). */
data class SpeedResult(
    val pingMs: Int,
    val downloadMbps: Double,
    /** Real exit IP seen by echo services through this profile, "" when unknown. */
    val exitIp: String = "",
) {
    val alive: Boolean get() = pingMs > 0
}

/**
 * Starts [profile] as a local SOCKS proxy via the core, then measures latency
 * and download throughput through it. One instance per profile; call [doTest]
 * inside `use { }` so the core is always torn down.
 *
 * @param downloadUrls tried in order; the first that yields at least one byte
 *   wins, the rest are fallbacks for when a mirror is unreachable from this
 *   proxy.
 */
class SpeedTestInstance(
    profile: ProxyEntity,
    private val pingUrl: String,
    private val downloadUrls: List<String>,
    private val timeoutMs: Int,
    private val maxDurationMs: Long,
    /** When true, one echo GET after the ping reports the profile's real exit IP. */
    private val probeExitIp: Boolean = false,
    /** Echo URLs tried in order; the first valid IP answer wins. */
    private val exitIpUrls: List<String> = emptyList(),
) : V2RayInstance(profile) {

    // The forTest config publishes a local SOCKS inbound; capture its port.
    @Volatile
    private var socksPort: Int = 0

    override fun buildConfig() {
        config = buildV2RayConfig(profile, forTest = true)
        // The forTest config intentionally omits local inbounds (the URL test
        // probes the outbound directly). For a speed test we need a real local
        // SOCKS proxy, so inject one on a free port into the generated config.
        try {
            val port = findFreePort()
            val root = org.json.JSONObject(config.config)
            val inbounds = root.optJSONArray("inbounds") ?: org.json.JSONArray().also {
                root.put("inbounds", it)
            }
            val socksIn = org.json.JSONObject().apply {
                put("tag", "speedtest-in")
                put("listen", "127.0.0.1")
                put("port", port)
                put("protocol", "socks")
                put("settings", org.json.JSONObject().apply { put("udp", true) })
            }
            inbounds.put(socksIn)
            config.config = root.toString()
            socksPort = port
        } catch (e: Exception) {
            Logs.w("SpeedTest inbound injection failed: ${e.message}")
        }
    }

    /** Blocks (suspend) until the core is up, then runs the measurement. */
    suspend fun doTest(): SpeedResult {
        startCore()
        // Give the inbound a brief moment to bind.
        delay(300L)
        if (socksPort <= 0) {
            // No local inbound was injected — nothing to measure through.
            return SpeedResult(0, 0.0)
        }
        return measure()
    }

    /**
     * One GET through the probe SOCKS to an echo service, returning the exit IP
     * as a plain trimmed body, or "" when every URL failed. The URLs are tried
     * in order and the first non-empty answer wins — same fallback semantics as
     * the download URLs. Echo services are public and rate-limited, so the list
     * is the resilience mechanism; the probe itself must stay cheap because it
     * runs once per profile inside the speed test.
     */
    private fun probeExitIpFrom(urls: List<String>): String {
        for (url in urls) {
            try {
                val conn = URL(url).openConnection(proxy()) as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "GET"
                conn.connect()
                val body = conn.inputStream.use { it.readBytes().decodeToString().trim() }
                conn.disconnect()
                // An echo service answers with the IP alone (possibly with a
                // trailing newline); anything longer is a wrong endpoint, not
                // an IP — skip it rather than annotate with garbage.
                val candidate = body.lineSequence().firstOrNull { it.isNotBlank() } ?: continue
                if (candidate.length <= 45 && candidate.matches(IP_REGEX)) return candidate
            } catch (_: Exception) {
                // Next mirror; a dead echo service must not fail the speed test.
            }
        }
        return ""
    }

    companion object {
        // IPv4 plus all IPv6 textual forms (hextet groups, embedded IPv4,
        // leading "::" compression). Length-capped upstream at 45 (RFC 4291).
        private val IP_REGEX = Regex(
            "([0-9]{1,3}(\\.[0-9]{1,3}){3})|([0-9A-Fa-f:]{2,45})"
        )
    }

    private suspend fun startCore() {
        suspendCoroutine { c: Continuation<Unit> ->
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                try {
                    init()
                    launch()
                    c.tryResume(Unit)
                } catch (e: Exception) {
                    c.tryResumeWithException(e)
                }
            }
        }
    }

    private fun findFreePort(): Int {
        return java.net.ServerSocket(0).use { it.localPort }
    }

    private fun proxy(): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

    private fun measure(): SpeedResult {
        // --- ping ---
        var pingMs = 0
        try {
            val start = System.currentTimeMillis()
            val conn = URL(pingUrl).openConnection(proxy()) as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.connect()
            conn.inputStream.use { it.readBytesLimited(4096) }
            pingMs = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            conn.disconnect()
        } catch (e: Exception) {
            return SpeedResult(0, 0.0)
        }

        // --- exit IP (optional) ---
        // Probed right after the ping, BEFORE the download: the download can
        // take the whole maxDuration and must not delay the exit-IP answer.
        // Only a live profile is probed — a dead one has no exit at all.
        var exitIp = ""
        if (probeExitIp && exitIpUrls.isNotEmpty()) {
            exitIp = probeExitIpFrom(exitIpUrls)
        }

        // --- download ---
        // Try the URLs in order: a mirror that is unreachable (or resets mid-way)
        // from this proxy must not zero the measurement — the next one is tried.
        // Any bytes read from any URL count; the loop breaks on the first URL
        // that yields at least one byte.
        var downloaded = 0L
        val dlStart = System.currentTimeMillis()
        for (url in downloadUrls) {
            if (downloaded > 0) break
            if (System.currentTimeMillis() - dlStart >= maxDurationMs) break
            try {
                val conn = URL(url).openConnection(proxy()) as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.connect()
                conn.inputStream.use { input ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        downloaded += n
                        if (System.currentTimeMillis() - dlStart >= maxDurationMs) break
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Partial download still counts toward the average; try the next URL.
            }
        }
        val elapsedSec = (System.currentTimeMillis() - dlStart) / 1000.0
        val mbps = if (elapsedSec > 0) (downloaded * 8.0) / (1024 * 1024) / elapsedSec else 0.0
        return SpeedResult(pingMs, (Math.round(mbps * 10) / 10.0), exitIp)
    }

    private fun InputStream.readBytesLimited(limit: Int) {
        val buf = ByteArray(limit)
        read(buf)
    }
}
