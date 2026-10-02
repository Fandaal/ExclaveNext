/******************************************************************************
 *                                                                            *
 * Exclave Next addition: configurable GeoIP provider chain.                  *
 *                                                                            *
 * The annotation engine used to hardcode "local MMDB first, then three public *
 * APIs". The chain model makes that sequence user-editable: entries are       *
 * ordered, each declares what it can provide, and a resolver that fails to    *
 * answer is followed by the next one.                                         *
 *                                                                            *
 * The default chain reproduces the previous hardcoded behaviour exactly, so   *
 * an existing installation keeps working without touching the settings.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.StringReader
import java.io.StringWriter

object GeoIpEntryType {

    /** Local MaxMind GeoLite2-Country database — resolves the country only. */
    const val MMDB_COUNTRY = "mmdb-country"

    /** Local MaxMind GeoLite2-ASN database — resolves the ISP/organization. */
    const val MMDB_ASN = "mmdb-asn"

    /** Public HTTP API with an {ip} placeholder. */
    const val API = "api"
}

/**
 * One link of the chain.
 *
 * @param type one of [GeoIpEntryType]
 * @param enabled disabled entries stay in the list but are never consulted
 * @param url API template containing `{ip}`, or the update URL of a local MMDB
 *   (empty = the file is only ever installed by hand)
 * @param file name of the local database file inside external assets
 */
data class GeoIpEntry(
    val type: String = GeoIpEntryType.API,
    val enabled: Boolean = true,
    val url: String = "",
    val file: String = "",
) {
    val isLocal: Boolean get() = type == GeoIpEntryType.MMDB_COUNTRY || type == GeoIpEntryType.MMDB_ASN

    val providesCountry: Boolean get() = type != GeoIpEntryType.MMDB_ASN

    val providesProvider: Boolean get() = type != GeoIpEntryType.MMDB_COUNTRY
}

/** A single lookup attempt by one entry: whatever that entry could resolve. */
data class GeoInfoFragment(
    val flag: String = "",
    val country: String = "",
    val provider: String = "",
)

/** Thrown by a resolver that cannot answer (missing file, dead API, bad JSON). */
open class GeoIpLookupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown by a resolver asked to do something it cannot, e.g. an empty API URL. */
class GeoIpMisconfiguredException(message: String) : GeoIpLookupException(message)

/**
 * Walks [chain] for [ip] and merges the answers field-wise.
 *
 * A field, once filled, is never overwritten — first hit wins. Iteration stops
 * as soon as both the country and the provider are known, so trailing entries
 * cost nothing when an early database already answered everything.
 */
object GeoIpChainResolver {

    fun resolve(
        ip: String,
        chain: List<GeoIpEntry>,
        factory: (GeoIpEntry) -> GeoIpResolver?,
    ): GeoInfo {
        var flag = ""
        var country = ""
        var provider = ""

        for (entry in chain) {
            if (!entry.enabled) continue

            val fragment = try {
                factory(entry)?.resolve(ip)
            } catch (e: GeoIpLookupException) {
                null
            } ?: continue

            if (country.isEmpty() && fragment.country.isNotEmpty()) {
                country = fragment.country
                if (fragment.flag.isNotEmpty()) flag = fragment.flag
            }
            if (provider.isEmpty() && fragment.provider.isNotEmpty()) {
                provider = fragment.provider
            }

            if (country.isNotEmpty() && provider.isNotEmpty()) break
        }

        return GeoInfo(flag, country.ifEmpty { GeoInfo.UNKNOWN.country }, provider)
    }
}

/** Where a chain entry gets its data from when it is a local database. */
private fun mmdbFileOf(type: String): String = when (type) {
    GeoIpEntryType.MMDB_COUNTRY -> GeoIpDefaults.COUNTRY_DB
    GeoIpEntryType.MMDB_ASN -> GeoIpDefaults.ASN_DB
    else -> ""
}

/**
 * Serialises the chain to a JSON string for [io.nekohasekai.sagernet.database.DataStore].
 *
 * Uses Gson's streaming API rather than reflection so that a stored value with a
 * missing or misspelled field degrades to the entry default instead of throwing
 * while preferences are being read.
 */
fun toChainJson(chain: List<GeoIpEntry>): String {
    // Keep a handle on the sink: JsonWriter.toString() is Object.toString(),
    // so the JSON has to be read back from the writer itself.
    val sink = StringWriter()
    val out = JsonWriter(sink)
    out.beginObject()
    out.name("entries").beginArray()
    for (entry in chain) {
        out.beginObject()
        out.name("type").value(entry.type)
        out.name("enabled").value(entry.enabled)
        if (entry.url.isNotEmpty()) out.name("url").value(entry.url)
        if (entry.file.isNotEmpty()) out.name("file").value(entry.file)
        out.endObject()
    }
    out.endArray()
    out.endObject()
    out.flush()
    return sink.toString()
}

/** Parses [json] back into a chain; anything unusable yields the default chain. */
fun parseChainJson(json: String?): List<GeoIpEntry> {
    if (json.isNullOrBlank()) return GeoIpDefaults.defaultChain()
    return try {
        val entries = ArrayList<GeoIpEntry>()
        JsonReader(StringReader(json)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "entries") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    var type = ""
                    var enabled = true
                    var url = ""
                    var file = ""
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "type" -> type = reader.nextString()
                            "enabled" -> if (reader.peek() == com.google.gson.stream.JsonToken.BOOLEAN) {
                                enabled = reader.nextBoolean()
                            } else {
                                reader.skipValue()
                            }
                            "url" -> url = reader.nextString()
                            "file" -> file = reader.nextString()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (type.isEmpty()) continue   // no type -> unusable entry, drop it
                    entries.add(
                        GeoIpEntry(
                            type = type,
                            enabled = enabled,
                            url = url,
                            file = file.ifEmpty { mmdbFileOf(type) },
                        )
                    )
                }
                reader.endArray()
            }
            reader.endObject()
        }
        entries.ifEmpty { GeoIpDefaults.defaultChain() }
    } catch (e: Exception) {
        GeoIpDefaults.defaultChain()
    }
}