/******************************************************************************
 *                                                                            *
 * Exclave Next addition: GeoIP resolver abstraction + default chain.         *
 *                                                                            *
 * [TDD] Phase 2 GREEN part 1: the interface the chain resolver drives.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

/** One lookup attempt against one configured source. */
interface GeoIpResolver {

    /** One of [GeoIpEntryType] — identifies which entry produced this resolver. */
    val type: String

    /**
     * Resolve [ip], returning whatever this source can answer. Fields left blank
     * simply mean "I don't know" and let the next chain entry try.
     *
     * @throws GeoIpLookupException when the source cannot answer at all.
     */
    fun resolve(ip: String): GeoInfoFragment
}

/**
 * The chain an installation gets before the user edits anything: local MaxMind
 * databases first (free, offline, no rate limit), public APIs after — exactly
 * the sequence GeoIpAnnotator used to hardcode.
 */
object GeoIpDefaults {

    const val COUNTRY_DB = "GeoLite2-Country.mmdb"
    const val ASN_DB = "GeoLite2-ASN.mmdb"

    // P3TERX mirror of the free MaxMind GeoLite2 databases.
    const val COUNTRY_DB_URL =
        "https://github.com/P3TERX/GeoLite.mmdb/raw/download/GeoLite2-Country.mmdb"
    const val ASN_DB_URL =
        "https://github.com/P3TERX/GeoLite.mmdb/raw/download/GeoLite2-ASN.mmdb"

    /** Canonical freeipapi endpoint (freeipapi.com is deprecated upstream and 307s here). */
    const val FREEIPAPI_URL = "https://free.freeipapi.com/api/v1/json/{ip}"

    /** Deprecated endpoint; 307-redirects to [FREEIPAPI_URL]. Stored chains migrate on read. */
    const val LEGACY_FREEIPAPI_URL = "https://freeipapi.com/api/json/{ip}"

    fun defaultChain(): List<GeoIpEntry> = listOf(
        GeoIpEntry(type = GeoIpEntryType.MMDB_COUNTRY, url = COUNTRY_DB_URL, file = COUNTRY_DB),
        GeoIpEntry(type = GeoIpEntryType.MMDB_ASN, url = ASN_DB_URL, file = ASN_DB),
        GeoIpEntry(type = GeoIpEntryType.API, url = "https://ipwho.is/{ip}"),
        GeoIpEntry(type = GeoIpEntryType.API, url = "https://i.pn/json/{ip}"),
        // Canonical freeipapi endpoint. The old freeipapi.com host is
        // deprecated upstream ("use free.freeipapi.com with the /api/v1/
        // path", per their docs) and 307s here — an extra round-trip to a
        // host we should not be talking to at all.
        GeoIpEntry(type = GeoIpEntryType.API, url = FREEIPAPI_URL),
    )
}