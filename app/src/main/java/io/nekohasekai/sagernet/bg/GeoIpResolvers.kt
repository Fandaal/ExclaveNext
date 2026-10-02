/******************************************************************************
 *                                                                            *
 * Exclave Next addition: concrete GeoIP resolvers.                           *
 *                                                                            *
 * One resolver per chain-entry type. The chain runner (GeoIpChainResolver)    *
 * asks them in order and merges whatever each one managed to answer, so a    *
 * resolver only has to know its own source.                                  *
 *                                                                            *
 * Ported from the single-function GeoIpAnnotator: the MaxMind MMDB readers,   *
 * the JSON scraping for ipwho.is / i.pn / freeipapi, and the per-endpoint     *
 * circuit breaker with exponential backoff.                                   *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import com.maxmind.geoip2.DatabaseReader
import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

/** Resolves the country + flag from a local MaxMind GeoLite2-Country database. */
class MmdbCountryResolver(private val reader: DatabaseReader) : GeoIpResolver {

    override val type: String = GeoIpEntryType.MMDB_COUNTRY

    override fun resolve(ip: String): GeoInfoFragment {
        val address = try {
            InetAddress.getByName(ip)
        } catch (e: Exception) {
            throw GeoIpLookupException("bad address $ip", e)
        }
        val response = try {
            reader.country(address)
        } catch (e: Exception) {
            // AddressNotFoundException: the IP simply is not in this database,
            // which is a normal outcome the next chain entry has to cover.
            throw GeoIpLookupException("country db has no $ip", e)
        }
        val name = response.country.name ?: return GeoInfoFragment()
        return GeoInfoFragment(
            flag = codeToFlag(response.country.isoCode ?: ""),
            country = name,
        )
    }
}

/** Resolves the ISP/organization from a local MaxMind GeoLite2-ASN database. */
class MmdbAsnResolver(private val reader: DatabaseReader) : GeoIpResolver {

    override val type: String = GeoIpEntryType.MMDB_ASN

    override fun resolve(ip: String): GeoInfoFragment {
        val address = try {
            InetAddress.getByName(ip)
        } catch (e: Exception) {
            throw GeoIpLookupException("bad address $ip", e)
        }
        val org = try {
            reader.asn(address).autonomousSystemOrganization
        } catch (e: Exception) {
            throw GeoIpLookupException("asn db has no $ip", e)
        } ?: return GeoInfoFragment()
        return GeoInfoFragment(provider = shortenProvider(org))
    }
}

/** ISO country code -> flag emoji (regional indicators). */
internal fun codeToFlag(code: String): String {
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

/** Words skipped when they lead an ASN org or ISP name (lower-case). */
private val IGNORE_ASN_WORDS = setOf("the", "llc", "inc", "ltd", "ooo", "jsc")

/** Shorten an org string the way the Python sorter trims an ASN organization. */
internal fun shortenProvider(org: String): String {
    val words = org.split(Regex("[^A-Za-z0-9.-]")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> ""
        words[0].lowercase() in IGNORE_ASN_WORDS && words.size > 1 -> words[1]
        else -> words[0]
    }
}

/**
 * Resolves country and/or provider from an HTTP endpoint.
 *
 * The endpoint shape is whatever the user configured — the parsing below
 * covers the common public-IP APIs, and any field it cannot find is simply
 * left blank for the next chain entry. A per-endpoint circuit breaker keeps one
 * dead provider from slowing every lookup.
 */
class ApiResolver(private val template: String) : GeoIpResolver {

    override val type: String = GeoIpEntryType.API

    override fun resolve(ip: String): GeoInfoFragment {
        if (template.isBlank()) throw GeoIpMisconfiguredException("empty API url")
        val url = template.replace(IP_PLACEHOLDER, ip)
        if (!url.contains(IP_PLACEHOLDER) && !template.contains(IP_PLACEHOLDER)) {
            throw GeoIpMisconfiguredException("api url has no $IP_PLACEHOLDER placeholder")
        }

        val state = states.computeIfAbsent(template) { ApiState() }
        val now = System.currentTimeMillis()
        if (now < state.circuitOpenUntil) {
            throw GeoIpLookupException("circuit open for $template")
        }

        val delay = backoffDelay(state.consecutiveErrors)
        val elapsed = (now - state.lastCall).toDouble()
        if (elapsed < delay) {
            try {
                Thread.sleep((delay - elapsed).toLong())
            } catch (_: InterruptedException) {
            }
        }

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = REQUEST_TIMEOUT_MS
            conn.readTimeout = REQUEST_TIMEOUT_MS
            conn.instanceFollowRedirects = true   // freeipapi 307-redirects
            conn.setRequestProperty("User-Agent", "Exclave")
            try {
                val code = conn.responseCode
                if (code == 429) {
                    state.consecutiveErrors++
                    throw GeoIpLookupException("rate limited by $template")
                }
                if (code !in 200..299) {
                    throw GeoIpLookupException("HTTP $code from $template")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val fragment = parseBody(body)
                if (fragment.country.isEmpty()) {
                    throw GeoIpLookupException("no country in response from $template")
                }
                state.consecutiveErrors = maxOf(0, state.consecutiveErrors - 1)
                state.lastCall = System.currentTimeMillis()
                return fragment
            } finally {
                conn.disconnect()
            }
        } catch (e: GeoIpLookupException) {
            state.consecutiveErrors++
            state.lastCall = System.currentTimeMillis()
            if (state.consecutiveErrors >= CIRCUIT_THRESHOLD) {
                state.circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_TIMEOUT_MS
                Logs.w("GeoIP circuit breaker open for $template")
            }
            throw e
        }
    }

    /** Pull country, flag and provider out of an arbitrary IP-geolocation JSON body. */
    private fun parseBody(body: String): GeoInfoFragment {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw GeoIpLookupException("malformed JSON", e)
        }
        val country = json.optString("countryName", json.optString("country", ""))
        if (country.isBlank() || country == "Unknown") return GeoInfoFragment()
        val cc = json.optString("countryCode", json.optString("country_code", ""))
        return GeoInfoFragment(
            flag = codeToFlag(cc),
            country = country,
            provider = providerFromJson(json),
        )
    }

    /** Per-endpoint rate-limit state; keyed by URL so user-defined ones work too. */
    private class ApiState {
        @Volatile var consecutiveErrors = 0
        @Volatile var circuitOpenUntil = 0L
        @Volatile var lastCall = 0L
    }

    companion object {
        const val IP_PLACEHOLDER = "{ip}"
        private const val REQUEST_TIMEOUT_MS = 6000
        private const val CIRCUIT_THRESHOLD = 10
        private const val CIRCUIT_TIMEOUT_MS = 60_000L
        private const val MAX_BACKOFF_MS = 30_000.0

        private val states = ConcurrentHashMap<String, ApiState>()

        private fun backoffDelay(consecutiveErrors: Int): Double {
            val base = if (consecutiveErrors == 0) 500.0
            else min(500.0 * 2.0.pow(consecutiveErrors), MAX_BACKOFF_MS)
            return base * (0.8 + Math.random() * 0.4)
        }

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
    }
}

/** Where a chain entry's local database file lives. */
fun geoIpDatabaseFile(fileName: String, assetsDir: File): File = File(assetsDir, fileName)
