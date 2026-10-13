/******************************************************************************
 *                                                                            *
 * Exclave Next addition: chain serialisation round-trip + safe defaults.      *
 *                                                                            *
 * The chain lives in a single preference as JSON, so a corrupted or partial  *
 * value must degrade to the default chain instead of taking the app down.     *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoIpChainSerializationTest {

    @Test
    fun chainSurvivesARoundTrip() {
        val original = listOf(
            GeoIpEntry(
                type = GeoIpEntryType.MMDB_COUNTRY,
                url = "https://example.invalid/country.mmdb",
                file = "Custom-Country.mmdb",
            ),
            GeoIpEntry(type = GeoIpEntryType.API, url = "https://ipwho.is/{ip}"),
            GeoIpEntry(
                type = GeoIpEntryType.API,
                enabled = false,
                url = "https://disabled.invalid/{ip}",
            ),
        )

        val restored = parseChainJson(toChainJson(original))

        assertEquals(original, restored)
    }

    @Test
    fun aBlankValueFallsBackToTheDefaultChain() {
        assertEquals(GeoIpDefaults.defaultChain(), parseChainJson(null))
        assertEquals(GeoIpDefaults.defaultChain(), parseChainJson(""))
        assertEquals(GeoIpDefaults.defaultChain(), parseChainJson("   "))
    }

    @Test
    fun malformedJsonFallsBackToTheDefaultChain() {
        assertEquals(GeoIpDefaults.defaultChain(), parseChainJson("{not json"))
        assertEquals(GeoIpDefaults.defaultChain(), parseChainJson("[]"))
    }

    @Test
    fun anEmptyEntryListFallsBackToTheDefaultChain() {
        assertEquals(GeoIpDefaults.defaultChain(), parseChainJson("""{"entries":[]}"""))
    }

    @Test
    fun entriesWithoutATypeAreDropped() {
        val json = """
            {"entries":[
              {"enabled":true,"url":"https://ipwho.is/{ip}"},
              {"type":"api","url":"https://i.pn/json/{ip}"}
            ]}
        """.trimIndent()

        val restored = parseChainJson(json)

        assertEquals(1, restored.size)
        assertEquals("https://i.pn/json/{ip}", restored[0].url)
    }

    @Test
    fun aMissingEnabledFieldDefaultsToTrue() {
        val restored = parseChainJson("""{"entries":[{"type":"api","url":"https://x.invalid/{ip}"}]}""")

        assertTrue(restored.single().enabled)
    }

    @Test
    fun aLocalEntryWithoutAFileGetsTheDefaultDatabaseName() {
        val restored = parseChainJson("""{"entries":[{"type":"mmdb-country"}]}""")

        assertEquals(GeoIpDefaults.COUNTRY_DB, restored.single().file)
    }

    @Test
    fun theDefaultChainStartsWithTheLocalDatabases() {
        val chain = GeoIpDefaults.defaultChain()

        assertEquals(GeoIpEntryType.MMDB_COUNTRY, chain[0].type)
        assertEquals(GeoIpEntryType.MMDB_ASN, chain[1].type)
        assertTrue(chain.drop(2).all { it.type == GeoIpEntryType.API })
        assertFalse(chain.any { !it.enabled })
    }

    @Test
    fun theDefaultChainCarriesNoLegacyEndpoints() {
        // freeipapi.com is deprecated upstream; the defaults carry only the
        // canonical free.freeipapi.com endpoint.
        val chain = GeoIpDefaults.defaultChain()

        assertFalse(chain.any { it.url == GeoIpDefaults.LEGACY_FREEIPAPI_URL })
        assertTrue(chain.any { it.url == GeoIpDefaults.FREEIPAPI_URL })
    }

    @Test
    fun aStoredLegacyEndpointIsMigratedOnRead() {
        val json = """
            {"entries":[
              {"type":"api","url":"${GeoIpDefaults.LEGACY_FREEIPAPI_URL}"},
              {"type":"api","url":"https://ipwho.is/{ip}"}
            ]}
        """.trimIndent()

        val restored = parseChainJson(json)

        assertEquals(2, restored.size)
        // The retired endpoint is rewritten to its live target…
        assertEquals(GeoIpDefaults.FREEIPAPI_URL, restored[0].url)
        // …and the other entries survive untouched.
        assertEquals("https://ipwho.is/{ip}", restored[1].url)
    }
}