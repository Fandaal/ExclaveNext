/******************************************************************************
 *                                                                            *
 * Exclave Next addition: GeoIP / DNS annotation of proxy profiles.           *
 *                                                                            *
 * Ported from the WhiteListVPN.py sorter:                                    *
 *   - resolve_domain_to_ip      -> resolveToIp                               *
 *   - get_country_by_ip         -> lookup (local MMDB first)                 *
 *   - get_asn_provider          -> aspProvider                              *
 *   - get_country_from_api      -> apiLookup (freeipapi + i.pn, breaker)     *
 *   - country_code_to_flag      -> codeToFlag                                *
 *   - add_country_to_line       -> annotateName (writes into bean.name tag)  *
 *                                                                            *
 * The annotation is written ONLY into the profile display name after the     *
 * "#" style tag that the sorter uses, e.g.  "<orig> 🇩🇪 Germany (Hetzner)".   *
 * No new database columns are introduced.                                    *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import com.maxmind.geoip2.DatabaseReader
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

/**
 * Annotation payload for a resolved server: flag + country + ISP/provider.
 */
data class GeoInfo(
    val flag: String,
    val country: String,
    val provider: String,
) {
    val isKnown: Boolean get() = country.isNotEmpty() && country != "Unknown"

    /** "🇩🇪 Germany (Hetzner)" — matches the sorter's add_country_to_line format. */
    fun tag(): String {
        val prov = provider.ifBlank { "Unknown" }
        return "$flag $country ($prov)".trim()
    }

    companion object {
        val UNKNOWN = GeoInfo("", "Unknown", "")
    }
}

/**
 * GeoIP engine. Local MaxMind MMDB databases are consulted first; when a lookup
 * misses (or no local DB is present) it falls back to the public APIs with a
 * per-API circuit breaker, mirroring the Python sorter.
 *
 * Thread-safe: all caches are concurrent and the MMDB readers are read-only.
 */
object GeoIpAnnotator {

    // MMDB file names, searched inside externalAssets (same dir as geoip.dat).
    private const val COUNTRY_DB = "GeoLite2-Country.mmdb"
    private const val ASN_DB = "GeoLite2-ASN.mmdb"

    // P3TERX mirror — same URLs the Python sorter uses (GEOIP_URLS).
    const val COUNTRY_DB_URL =
        "https://github.com/P3TERX/GeoLite.mmdb/raw/download/GeoLite2-Country.mmdb"
    const val ASN_DB_URL =
        "https://github.com/P3TERX/GeoLite.mmdb/raw/download/GeoLite2-ASN.mmdb"

    // Public fallback APIs ({ip} placeholder). ipwho.is and i.pn return BOTH
    // country and ISP; freeipapi returns country + asnOrganization.
    private val API_SERVICES = arrayOf(
        "https://ipwho.is/{ip}",
        "https://i.pn/json/{ip}",
        "https://freeipapi.com/api/json/{ip}",
    )
    private const val REQUEST_TIMEOUT_MS = 6000
    private const val CIRCUIT_THRESHOLD = 10
    private const val CIRCUIT_TIMEOUT_MS = 60_000L
    private const val MAX_BACKOFF_MS = 30_000.0

    // Words skipped when they lead the ASN org string (lower-case).
    private val IGNORE_ASN_WORDS = setOf("the", "llc", "inc", "ltd", "ooo", "jsc")

    private val dnsCache = ConcurrentHashMap<String, String>()      // host -> ip ("" = failed)
    private val geoCache = ConcurrentHashMap<String, GeoInfo>()     // ip -> GeoInfo
    private val notFound = ConcurrentHashMap<String, Boolean>()     // ip not in local db & api

    @Volatile private var countryReader: DatabaseReader? = null
    @Volatile private var asnReader: DatabaseReader? = null
    @Volatile private var initialized = false

    private class ApiState {
        @Volatile var consecutiveErrors = 0
        @Volatile var circuitOpenUntil = 0L
        @Volatile var lastCall = 0L
    }
    private val apiState = API_SERVICES.associateWith { ApiState() }

    fun countryDbFile(): File = File(SagerNet.application.externalAssets, COUNTRY_DB)
    fun asnDbFile(): File = File(SagerNet.application.externalAssets, ASN_DB)

    fun hasLocalDatabases(): Boolean = countryDbFile().isFile

    /** Opens the MMDB readers once. Safe to call repeatedly. */
    @Synchronized
    fun ensureInit() {
        if (initialized) return
        initialized = true
        val countryFile = countryDbFile()
        if (countryFile.isFile) {
            try {
                countryReader = DatabaseReader.Builder(countryFile).build()
            } catch (e: Exception) {
                Logs.w("GeoIP country db: ${e.message}")
            }
        }
        val asnFile = asnDbFile()
        if (asnFile.isFile) {
            try {
                asnReader = DatabaseReader.Builder(asnFile).build()
            } catch (e: Exception) {
                Logs.w("GeoIP asn db: ${e.message}")
            }
        }
    }

    /** Reopen readers after a DB download. */
    @Synchronized
    fun reload() {
        try {
            countryReader?.close()
        } catch (_: Exception) {
        }
        try {
            asnReader?.close()
        } catch (_: Exception) {
        }
        countryReader = null
        asnReader = null
        initialized = false
        ensureInit()
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        for (p in parts) {
            if (p.isEmpty() || p.length > 1 && p[0] == '0') return false
            val n = p.toIntOrNull() ?: return false
            if (n !in 0..255) return false
        }
        return true
    }

    /** host -> IPv4 string, or null. Cached. Mirrors resolve_domain_to_ip. */
    fun resolveToIp(host: String): String? {
        val clean = host.substringBefore(':').trim()
        if (clean.isEmpty()) return null
        if (isValidIp(clean)) return clean
        dnsCache[clean]?.let { return it.ifEmpty { null } }
        return try {
            val addr = InetAddress.getAllByName(clean).firstOrNull { it is Inet4Address }
                ?: InetAddress.getAllByName(clean).firstOrNull()
            val ip = addr?.hostAddress
            if (ip != null) {
                dnsCache[clean] = ip
                ip
            } else {
                dnsCache[clean] = ""
                null
            }
        } catch (_: Exception) {
            dnsCache[clean] = ""
            null
        }
    }

    /** ISO country code -> flag emoji (regional indicators). */
    private fun codeToFlag(code: String): String {
        if (code.length != 2) return ""
        return try {
            buildString {
                for (c in code.uppercase()) {
                    appendCodePoint(0x1F1E6 + (c.code - 'A'.code))
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun asnProvider(ip: String): String {
        val reader = asnReader ?: return ""
        return try {
            val org = reader.asn(InetAddress.getByName(ip)).autonomousSystemOrganization
                ?: return ""
            val words = org.split(Regex("[^A-Za-z0-9.-]")).filter { it.isNotBlank() }
            when {
                words.isEmpty() -> ""
                words[0].lowercase() in IGNORE_ASN_WORDS && words.size > 1 -> words[1]
                else -> words[0]
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** IP -> GeoInfo. Local MMDB first, then the API fallback. Cached. */
    fun lookup(ip: String): GeoInfo {
        geoCache[ip]?.let { return it }
        ensureInit()

        var provider = asnProvider(ip)   // local ASN MMDB (may be empty)
        var flag = ""
        var country = "Unknown"

        countryReader?.let { reader ->
            try {
                val resp = reader.country(InetAddress.getByName(ip))
                resp.country.name?.let {
                    country = it
                    flag = codeToFlag(resp.country.isoCode ?: "")
                }
            } catch (e: com.maxmind.geoip2.exception.AddressNotFoundException) {
                notFound[ip] = true
            } catch (e: Exception) {
                Logs.w("GeoIP lookup: ${e.message}")
            }
        }

        // Hit the API when EITHER country or provider is still missing (local
        // ASN db is often absent, so provider frequently needs the API too).
        if ((country == "Unknown" && notFound[ip] != true) || provider.isBlank()) {
            val api = apiLookup(ip)
            if (api.country != "Unknown") {
                if (country == "Unknown") {
                    flag = api.flag
                    country = api.country
                }
            } else if (country == "Unknown") {
                notFound[ip] = true
            }
            if (provider.isBlank() && api.provider.isNotBlank()) {
                provider = api.provider
            }
        }

        val result = GeoInfo(flag, country, provider)
        geoCache[ip] = result
        return result
    }

    private fun backoffDelay(consecutiveErrors: Int): Double {
        val base = if (consecutiveErrors == 0) 500.0
        else min(500.0 * 2.0.pow(consecutiveErrors), MAX_BACKOFF_MS)
        return base * (0.8 + Math.random() * 0.4)
    }

    /** Shorten an ISP/org string the same way asnProvider trims an ASN org. */
    private fun shortenProvider(org: String): String {
        val words = org.split(Regex("[^A-Za-z0-9.-]")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> ""
            words[0].lowercase() in IGNORE_ASN_WORDS && words.size > 1 -> words[1]
            else -> words[0]
        }
    }

    /** Pull an ISP/provider name out of whatever shape the API returned. */
    private fun providerFromJson(json: JSONObject): String {
        // ipwho.is nests it under "connection".
        json.optJSONObject("connection")?.let { conn ->
            val v = conn.optString("isp", conn.optString("org", ""))
            if (v.isNotBlank()) return shortenProvider(v)
        }
        // i.pn: isp/org/asName ; freeipapi: asnOrganization.
        for (key in arrayOf("isp", "asName", "org", "asnOrganization")) {
            val v = json.optString(key, "")
            if (v.isNotBlank()) return shortenProvider(v)
        }
        return ""
    }

    /** GeoInfo from public APIs, or UNKNOWN. Ports get_country_from_api (+ ISP). */
    private fun apiLookup(ip: String): GeoInfo {
        for (apiUrl in API_SERVICES) {
            val state = apiState[apiUrl]!!
            val now = System.currentTimeMillis()
            if (now < state.circuitOpenUntil) continue

            val delay = backoffDelay(state.consecutiveErrors)
            val elapsed = (now - state.lastCall).toDouble()
            if (elapsed < delay) {
                try {
                    Thread.sleep((delay - elapsed).toLong())
                } catch (_: InterruptedException) {
                }
            }

            try {
                val conn = URL(apiUrl.replace("{ip}", ip)).openConnection()
                    as java.net.HttpURLConnection
                conn.connectTimeout = REQUEST_TIMEOUT_MS
                conn.readTimeout = REQUEST_TIMEOUT_MS
                conn.instanceFollowRedirects = true   // freeipapi 307-redirects
                conn.setRequestProperty("User-Agent", "Exclave")
                try {
                    val code = conn.responseCode
                    if (code == 429) {
                        state.consecutiveErrors++
                        continue
                    }
                    if (code in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val country = json.optString("countryName",
                            json.optString("country", "Unknown"))
                        val cc = json.optString("countryCode",
                            json.optString("country_code", ""))
                        val provider = providerFromJson(json)
                        if (country.isNotEmpty() && country != "Unknown") {
                            state.consecutiveErrors = maxOf(0, state.consecutiveErrors - 1)
                            state.lastCall = System.currentTimeMillis()
                            return GeoInfo(codeToFlag(cc), country, provider)
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }

            state.consecutiveErrors++
            state.lastCall = System.currentTimeMillis()
            if (state.consecutiveErrors >= CIRCUIT_THRESHOLD) {
                state.circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_TIMEOUT_MS
                Logs.w("GeoIP circuit breaker open for $apiUrl")
            }
        }
        return GeoInfo.UNKNOWN
    }

    // Matches the leading flag(regional-indicator pair) + "country (provider)" suffix.
    private val GEO_TAG_REGEX = Regex(
        "\\s*[\\x{1F1E6}-\\x{1F1FF}]{2}\\s+[^#]*\\([^)]*\\)\\s*$"
    )

    /** Strip a previously-appended geo tag so re-annotation doesn't stack suffixes. */
    fun stripGeoTag(name: String): String = name.replace(GEO_TAG_REGEX, "").trimEnd()

    /** Return the trailing geo tag (incl. leading whitespace) of [name], or "". */
    fun geoTagOf(name: String): String = GEO_TAG_REGEX.find(name)?.value ?: ""

    // Leading speed marker (emoji) + Mbps number written by the speed test, e.g.
    // "✨ 42.3 <name>". Kept in sync with ConfigurationFragment.speedTest().
    private val SPEED_MARKER_REGEX = Regex(
        "^[\\x{2728}\\x{2B50}\\x{1F3C1}\\x{1F3F3}\\uFE0F]+\\s*[0-9]+(?:\\.[0-9]+)?\\s+"
    )

    /** Return the leading speed marker of [name], or "". */
    fun speedMarkerOf(name: String): String = SPEED_MARKER_REGEX.find(name)?.value ?: ""

    /** Strip the leading speed marker from [name]. */
    fun stripSpeedMarker(name: String): String = name.replace(SPEED_MARKER_REGEX, "")

    /**
     * Carry a profile's geo/speed annotations from [oldName] onto a [freshName]
     * coming from a subscription refresh, so updating a subscription does not
     * wipe the user's annotations. Returns the fresh name wrapped with the old
     * speed-marker prefix and geo-tag suffix (when present).
     */
    fun transferAnnotations(oldName: String, freshName: String): String {
        val speed = speedMarkerOf(oldName)
        val geo = geoTagOf(oldName)
        // Normalise the fresh name: drop any annotations it may already carry.
        var base = stripSpeedMarker(stripGeoTag(freshName)).trim()
        if (geo.isNotEmpty()) base = "$base ${geo.trim()}".trim()
        if (speed.isNotEmpty()) base = "${speed.trim()} $base".trim()
        return base
    }

    /** Append (or replace) the geo tag inside the profile display name. */
    fun annotateName(originalName: String, info: GeoInfo): String {
        val base = stripGeoTag(originalName)
        return if (info.isKnown) "$base ${info.tag()}".trim() else base
    }
}
