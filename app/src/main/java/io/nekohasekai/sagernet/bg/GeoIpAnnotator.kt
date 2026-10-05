/******************************************************************************
 *                                                                            *
 * Exclave Next addition: GeoIP / DNS annotation of proxy profiles.           *
 *                                                                            *
 * Ported from the WhiteListVPN.py sorter:                                    *
 *   - resolve_domain_to_ip      -> resolveToIp                               *
 *   - get_country_by_ip         -> lookup (walks the configured chain)       *
 *   - get_asn_provider          -> MmdbAsnResolver / ApiResolver             *
 *   - get_country_from_api      -> ApiResolver (freeipapi + i.pn, breaker)   *
 *   - country_code_to_flag      -> codeToFlag (GeoIpResolvers.kt)             *
 *   - add_country_to_line       -> annotateName (writes into bean.name tag)  *
 *                                                                            *
 * The lookup order is no longer hardcoded: DataStore.geoIpChain holds an     *
 * ordered list of entries (local MaxMind databases and/or public APIs) and   *
 * each one only fills the fields that are still missing. See GeoIpResolvers  *
 * for the individual sources and GeoIpConfig for the chain model.            *
 *                                                                            *
 * The annotation is written ONLY into the profile display name. No new       *
 * database columns are introduced.                                          *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import com.maxmind.geoip2.DatabaseReader
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

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
    private const val COUNTRY_DB = GeoIpDefaults.COUNTRY_DB
    private const val ASN_DB = GeoIpDefaults.ASN_DB

    // P3TERX mirror — same URLs the Python sorter uses (GEOIP_URLS).
    const val COUNTRY_DB_URL = GeoIpDefaults.COUNTRY_DB_URL
    const val ASN_DB_URL = GeoIpDefaults.ASN_DB_URL

    private val dnsCache = ConcurrentHashMap<String, String>()      // host -> ip ("" = failed)
    private val geoCache = ConcurrentHashMap<String, GeoInfo>()     // ip -> GeoInfo

    @Volatile private var countryReader: DatabaseReader? = null
    @Volatile private var asnReader: DatabaseReader? = null
    @Volatile private var initialized = false

    fun countryDbFile(): File = File(SagerNet.application.externalAssets, COUNTRY_DB)
    fun asnDbFile(): File = File(SagerNet.application.externalAssets, ASN_DB)

    /** True when at least one chain entry can actually answer. */
    fun isChainUsable(): Boolean = DataStore.geoIpChain.any { it.enabled }

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

    /**
     * Build the resolver for one chain entry, or null when the entry cannot
     * work: a local database that is not installed, or an API with no URL.
     *
     * internal, not private: the file-level trace() below needs it to run one
     * entry at a time without duplicating this mapping.
     */
    internal fun resolverFor(entry: GeoIpEntry): GeoIpResolver? = when (entry.type) {
        GeoIpEntryType.MMDB_COUNTRY -> countryReader?.let { MmdbCountryResolver(it) }
        GeoIpEntryType.MMDB_ASN -> asnReader?.let { MmdbAsnResolver(it) }
        GeoIpEntryType.API -> if (entry.url.isBlank()) null else ApiResolver(entry.url)
        else -> null
    }

    /**
     * IP -> GeoInfo, resolved through the configured provider chain.
     *
     * Entries are consulted in order and each one only fills what is still
     * missing, so a local country database plus a later API still yields both
     * fields. Iteration stops as soon as the country AND the provider are known.
     * Cached, so a repeated profile costs nothing.
     */
    fun lookup(ip: String): GeoInfo {
        geoCache[ip]?.let { return it }
        ensureInit()

        val result = GeoIpChainResolver.resolve(ip, DataStore.geoIpChain) { resolverFor(it) }
        geoCache[ip] = result
        return result
    }


/**
 * Find the index of the first country-flag emoji (regional indicator symbol)
     * in [name]. Country flags are pairs of regional indicators U+1F1E6..U+1F1FF.
     * Returns -1 if none found. Uses codePoint iteration to correctly handle
     * supplementary-plane characters on JVM (where they are surrogate pairs).
     */
    private fun findFirstFlagIndex(name: String): Int {
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) return i
            i += Character.charCount(cp)
        }
        return -1
    }

    /** Strip everything from the first country-flag emoji to end-of-string. */
    fun stripGeoTag(name: String): String {
        val idx = findFirstFlagIndex(name)
        return if (idx >= 0) name.substring(0, idx).trimEnd() else name
    }

    /** Return the geo-tag tail starting at the first flag, or "". */
    fun geoTagOf(name: String): String {
        val idx = findFirstFlagIndex(name)
        return if (idx >= 0) name.substring(idx) else ""
    }

// Speed marker layout: a leading emoji from the fixed set, and the measured
    // value as a TRAILING "↓<Mbps>" so it reads after the geo tag:
    //   "🏁 🇷🇺 Russia (MTS) ↓22.2"
    // An alternation, NOT a character class: Java regex classes match UTF-16 code
    // units and break on supplementary-plane emoji, while \x{...} outside a class
    // matches full code points. ⭐️/🏳️ may carry U+FE0F; 🏴 may carry the ZWJ
    // pirate flag tail. Anything outside these emoji (e.g. a ⚡ belonging to
    // the subscription name) is never touched.
    // 🚩 (U+1F6A9) marks a profile whose ping never passed. It MUST be listed
    // here: composeSpeedName() strips the previous marker by this same
    // alternation, so a marker missing from it would never be replaced and the
    // annotations would stack ("🚩 🏴 <name>") on every re-run.
    private const val SPEED_EMOJI =
        "(?:\\x{2728}|\\x{2B50}\\uFE0F?|\\x{1F3C1}|\\x{1F3F3}\\uFE0F|\\x{1F3F4}(?:\\u200D\\x{2620}\\uFE0F)?|\\x{1F6A9})"

    // The leading marker alone (no value follows it any more).
    private val SPEED_EMOJI_PREFIX_REGEX = Regex("^$SPEED_EMOJI\\s*")

    // Legacy layout from earlier builds: the value sat between emoji and name,
    // and repeated runs could stack several values ("🏁 24.7 23.9 24.0 <name>").
    private val LEGACY_SPEED_PREFIX_REGEX = Regex(
        "^$SPEED_EMOJI(?:\\s+[0-9]+(?:\\.[0-9]+)?)+\\s+"
    )

    // The trailing "↓<Mbps>", anchored to the end. Matching the tail ALONE (not
    // from "^") is what makes this work: a pattern that starts at "^" and runs to
    // "$" matches the WHOLE name, because ".*" swallows everything before the
    // arrow — the name is then stripped to nothing.
    private val SPEED_SUFFIX_REGEX = Regex("\\s*↓\\s*[0-9]+(?:\\.[0-9]+)?\\s*$")
    private val SPEED_NUMBER_REGEX = Regex("[0-9]+(?:\\.[0-9]+)?")

    /**
     * Strip the trailing ↓N value (the new layout) and any legacy leading
     * values, leaving the leading emoji marker in place.
     */
    fun stripSpeedMarker(name: String): String {
        val suffix = SPEED_SUFFIX_REGEX.find(name) ?: return stripLegacy(name)
        // A "↓42.3" in a plain subscription name is not ours: only strip it when a
        // leading marker emoji says this name is a speed-annotated one.
        if (speedMarkerOf(name).isEmpty()) return stripLegacy(name)
        return stripLegacy(name.substring(0, suffix.range.first))
    }

    /** Drop the legacy leading "<emoji> <value(s)> " prefix, if present. */
    private fun stripLegacy(name: String): String = name.replace(LEGACY_SPEED_PREFIX_REGEX, "")

    /** Return the leading speed emoji, or "" when [name] carries no marker. */
    fun speedMarkerOf(name: String): String =
        SPEED_EMOJI_PREFIX_REGEX.find(name)?.value?.trimEnd() ?: ""

    /**
     * Cut a trailing "↓N" without requiring a leading marker. Needed for the
     * geo tag, which is the name tail from the first flag and therefore has no
     * marker of its own — stripSpeedMarker() would leave it untouched there.
     */
    private fun stripSpeedValue(name: String): String {
        val suffix = SPEED_SUFFIX_REGEX.find(name) ?: return name
        return name.substring(0, suffix.range.first)
    }

    /** Return the trailing ↓N value as written ("22.2"), or "" when absent. */
    fun speedValueOf(name: String): String {
        if (speedMarkerOf(name).isEmpty()) return ""
        val suffix = SPEED_SUFFIX_REGEX.find(name) ?: return ""
        return SPEED_NUMBER_REGEX.find(suffix.value)?.value ?: ""
    }

    /** Drop the leading speed emoji from [name], if present. */
    private fun withoutSpeedEmoji(name: String): String =
        name.replace(SPEED_EMOJI_PREFIX_REGEX, "").trim()

    /**
     * Build the display name for a speed test: strip whatever was there, then
     * write [marker] at the front and [mbps] as the trailing value.
     *
     * Both parts are replaced on every run — a profile that speeds up gains the
     * better emoji, one that dies loses its number — so annotations never stack.
     * A null [mbps] (ping never passed) omits the arrow entirely, and so does a
     * measured zero: "↓0.0" is noise, the bare marker already says it. This is
     * the ONE place that rule lives — callers pass the raw measurement.
     */
    fun composeSpeedName(name: String, marker: String, mbps: Double?): String {
        val head = withoutSpeedEmoji(stripSpeedMarker(name))
        val prefix = if (head.isEmpty()) "" else "$marker $head"
        val value = mbps?.takeIf { it > 0.0 }?.let { "↓$it" } ?: ""
        return "$prefix $value".trim()
    }

    /**
     * Carry a profile's geo/speed annotations from [oldName] onto a [freshName]
     * coming from a subscription refresh, so updating a subscription does not
     * wipe the user's annotations. Returns the fresh name wrapped with the old
     * speed marker and geo tag (when present), in the current "↓N" layout.
     */
    fun transferAnnotations(oldName: String, freshName: String): String {
        val marker = speedMarkerOf(oldName)
        // Legacy names carry the value next to the emoji, not in a ↓N tail.
        val legacyValue = LEGACY_SPEED_PREFIX_REGEX.find(oldName)
            ?.value?.let { SPEED_NUMBER_REGEX.find(it)?.value } ?: ""
        // A stored "0.0" is dropped rather than carried: a zero measurement is
        // expressed by the bare marker (composeSpeedName rule), so transferring
        // it would resurrect "↓0.0" on the fresh name.
        val mbps = (speedValueOf(oldName).ifEmpty { legacyValue })
            .takeIf { it.isNotEmpty() && it.toDoubleOrNull() != 0.0 }
        // stripGeoTag() cuts from the first flag to the END OF STRING, so the
        // ↓N tail has to be removed BEFORE the geo tag is taken — otherwise the
        // value rides along inside it and gets re-appended a second time.
        val geo = stripSpeedValue(geoTagOf(oldName)).trim()
        // Normalise the fresh name: drop any annotations it may already carry.
        var base = withoutSpeedEmoji(stripSpeedMarker(stripGeoTag(freshName))).trim()
        if (geo.isNotEmpty()) base = "$base $geo".trim()
        val head = if (marker.isEmpty()) base else "$marker $base".trim()
        val value = mbps?.let { "↓$it" } ?: ""
        return "$head $value".trim()
    }

        /** Replace the entire profile name with just the geo tag. */
    fun annotateName(originalName: String, info: GeoInfo): String {
        return if (info.isKnown) info.tag() else stripGeoTag(originalName).trimEnd()
    }
}


/**
 * One line of a lookup trace: what a single chain entry did for a single IP.
 *
 * @param label human-readable name of the entry (file name or API host)
 * @param consulted false when the entry was skipped (disabled, or the resolver
 *   could not be built at all — a missing local database, an empty API URL)
 * @param country/provider what this entry contributed to the merged result
 * @param error the failure message when the entry was asked and failed
 */
data class GeoInfoTraceLine(
    val label: String,
    val consulted: Boolean,
    val country: String = "",
    val provider: String = "",
    val error: String = "",
)

/**
 * Run the chain for [ip] and report every entry, not just the merged answer.
 *
 * Same order and same first-hit-wins merge as [lookup], but nothing is cached:
 * a trace exists to show what the chain actually does right now, so a cached
 * result from an earlier run would be misleading. Intended for the settings
 * screen, where a single manual lookup is cheap.
 */
fun GeoIpAnnotator.trace(ip: String): List<GeoInfoTraceLine> {
    ensureInit()
    val lines = ArrayList<GeoInfoTraceLine>()
    var country = ""
    var provider = ""

    for (entry in DataStore.geoIpChain) {
        val label = traceLabelOf(entry)
        if (!entry.enabled) {
            lines.add(GeoInfoTraceLine(label, consulted = false, error = "disabled"))
            continue
        }
        val resolver = resolverFor(entry)
        if (resolver == null) {
            lines.add(GeoInfoTraceLine(label, consulted = false, error = "unavailable"))
            continue
        }

        val fragment = try {
            resolver.resolve(ip)
        } catch (e: GeoIpLookupException) {
            lines.add(GeoInfoTraceLine(label, consulted = true, error = e.message ?: "failed"))
            continue
        } catch (e: Exception) {
            lines.add(GeoInfoTraceLine(label, consulted = true, error = e.message ?: "failed"))
            continue
        }

        val tookCountry = country.isEmpty() && fragment.country.isNotEmpty()
        val tookProvider = provider.isEmpty() && fragment.provider.isNotEmpty()
        if (tookCountry) country = fragment.country
        if (tookProvider) provider = fragment.provider

        lines.add(
            GeoInfoTraceLine(
                label = label,
                consulted = true,
                // Report only what this entry actually contributed: a field that
                // was already filled by an earlier entry is not its result.
                country = if (tookCountry) fragment.country else "",
                provider = if (tookProvider) fragment.provider else "",
            )
        )

        if (country.isNotEmpty() && provider.isNotEmpty()) break
    }
    return lines
}

private fun traceLabelOf(entry: GeoIpEntry): String = when {
    entry.isLocal && entry.file.isNotEmpty() -> entry.file
    entry.url.isNotBlank() -> entry.url
    entry.isLocal -> entry.type
    else -> entry.type
}