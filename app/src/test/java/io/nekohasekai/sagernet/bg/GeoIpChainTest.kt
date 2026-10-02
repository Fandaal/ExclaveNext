/******************************************************************************
 *                                                                            *
 * Exclave Next addition: unit tests for the GeoIP provider chain.             *
 *                                                                            *
 * These lock down the fallback semantics the settings screen exposes: an       *
 * entry that does not resolve a country and/or a provider is followed by     *
 * the next one, first hit per field wins, and iteration stops as soon as     *
 * both fields are filled.                                                    *
 *                                                                            *
 * [TDD] Phase 2 RED: written before GeoIpResolver / GeoIpChainResolver        *
 * exist — the compile failure IS the expected RED result.                     *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoIpChainTest {

    /** Records every call so a test can assert the exact iteration order. */
    private val callLog = mutableListOf<String>()

    /**
     * A resolver returning a canned fragment. [failed] makes it throw, standing
     * in for a dead API or a missing database file.
     */
    private inner class Fake(
        val name: String,
        val country: String = "",
        val flag: String = "",
        val provider: String = "",
        val failed: Boolean = false,
    ) : GeoIpResolver {
        override val type: String = "fake"
        override fun resolve(ip: String): GeoInfoFragment {
            callLog.add(name)
            if (failed) throw GeoIpLookupException("no")
            return GeoInfoFragment(flag = flag, country = country, provider = provider)
        }
    }

    private fun entry(type: String) = GeoIpEntry(type = type)

    private fun resolve(
        chain: List<GeoIpEntry>,
        factory: (GeoIpEntry) -> GeoIpResolver?,
    ): GeoInfo = GeoIpChainResolver.resolve("1.2.3.4", chain, factory)

    @Test
    fun firstHitWinsPerField() {
        val result = resolve(
            listOf(entry(GeoIpEntryType.MMDB_COUNTRY), entry(GeoIpEntryType.API)),
        ) { e ->
            when (e.type) {
                GeoIpEntryType.MMDB_COUNTRY -> Fake("mmdb", country = "Sweden", flag = "\uD83C\uDDF8\uD83C\uDDEA")
                else -> Fake("api", country = "Germany", flag = "\uD83C\uDDE9\uD83C\uDDEA", provider = "Hetzner")
            }
        }

        // The API is consulted for the missing provider, but it must NOT
        // overwrite the country the local database already resolved.
        assertEquals("Sweden", result.country)
        assertEquals("\uD83C\uDDF8\uD83C\uDDEA", result.flag)
        assertEquals("Hetzner", result.provider)
    }

    @Test
    fun providerIsFilledFromALaterEntry() {
        val result = resolve(
            listOf(entry(GeoIpEntryType.MMDB_COUNTRY), entry(GeoIpEntryType.MMDB_ASN)),
        ) { e ->
            when (e.type) {
                GeoIpEntryType.MMDB_COUNTRY -> Fake("mmdb-country", country = "Sweden")
                else -> Fake("mmdb-asn", provider = "Alexhost")
            }
        }

        assertEquals("Sweden", result.country)
        assertEquals("Alexhost", result.provider)
    }

    @Test
    fun stopsWhenBothFieldsAreKnown() {
        resolve(
            listOf(
                entry(GeoIpEntryType.API),
                entry(GeoIpEntryType.API),
                entry(GeoIpEntryType.API),
            ),
        ) { Fake("api", country = "Sweden", provider = "Alexhost") }

        assertEquals(listOf("api"), callLog)
    }

    @Test
    fun disabledEntryIsSkipped() {
        val result = resolve(
            listOf(
                GeoIpEntry(type = GeoIpEntryType.API, enabled = false),
                GeoIpEntry(type = GeoIpEntryType.MMDB_COUNTRY),
            ),
        ) { e ->
            when (e.type) {
                GeoIpEntryType.API -> Fake("api", country = "Germany")
                else -> Fake("mmdb", country = "Sweden", provider = "Alexhost")
            }
        }

        assertEquals(listOf("mmdb"), callLog)
        assertEquals("Sweden", result.country)
    }

    @Test
    fun aFailingEntryDoesNotAbortTheChain() {
        val result = resolve(
            listOf(entry(GeoIpEntryType.API), entry(GeoIpEntryType.MMDB_COUNTRY)),
        ) { e ->
            when (e.type) {
                GeoIpEntryType.API -> Fake("api", failed = true)
                else -> Fake("mmdb", country = "Sweden", provider = "Alexhost")
            }
        }

        assertEquals(listOf("api", "mmdb"), callLog)
        assertEquals("Sweden", result.country)
        assertEquals("Alexhost", result.provider)
    }

    @Test
    fun orderIsSignificant() {
        val asnFirst = resolve(
            listOf(entry(GeoIpEntryType.MMDB_ASN), entry(GeoIpEntryType.MMDB_COUNTRY)),
        ) { e ->
            when (e.type) {
                GeoIpEntryType.MMDB_ASN -> Fake("mmdb-asn", provider = "Alexhost")
                else -> Fake("mmdb-country", country = "Sweden")
            }
        }
        val asnFirstLog = callLog.toList()
        callLog.clear()

        val countryFirst = resolve(
            listOf(entry(GeoIpEntryType.MMDB_COUNTRY), entry(GeoIpEntryType.MMDB_ASN)),
        ) { e ->
            when (e.type) {
                GeoIpEntryType.MMDB_COUNTRY -> Fake("mmdb-country", country = "Sweden")
                else -> Fake("mmdb-asn", provider = "Alexhost")
            }
        }
        val countryFirstLog = callLog.toList()

        // Same final answer, different visit order — the chain is a sequence.
        assertEquals("Sweden", asnFirst.country)
        assertEquals("Sweden", countryFirst.country)
        assertEquals(listOf("mmdb-asn", "mmdb-country"), asnFirstLog)
        assertEquals(listOf("mmdb-country", "mmdb-asn"), countryFirstLog)
    }

    @Test
    fun anExhaustedChainYieldsUnknownCountry() {
        val result = resolve(listOf(entry(GeoIpEntryType.API))) { Fake("api") }

        assertTrue(!result.isKnown)
        assertEquals("Unknown", result.country)
        assertEquals("", result.provider)
    }

    @Test
    fun aNullResolverIsSkipped() {
        val result = resolve(
            listOf(entry(GeoIpEntryType.MMDB_ASN), entry(GeoIpEntryType.MMDB_COUNTRY)),
        ) { e ->
            if (e.type == GeoIpEntryType.MMDB_ASN) null
            else Fake("mmdb-country", country = "Sweden")
        }

        assertEquals(listOf("mmdb-country"), callLog)
        assertEquals("Sweden", result.country)
    }
}