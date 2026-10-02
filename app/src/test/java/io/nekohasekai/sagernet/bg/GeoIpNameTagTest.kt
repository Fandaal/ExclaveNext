/******************************************************************************
 *                                                                            *
 * Exclave Next addition: regression tests for profile name annotation.          *
 *                                                                            *
 * The geo and speed markers are rewritten in place inside bean.name on every   *
 * annotation pass, so a regex regression here silently corrupts every profile  *
 * name in the app. These tests pin the existing behaviour before the GeoIP      *
 * chain refactor, so a naming break shows up as a failed test instead of on   *
 * the device.                                                                *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoIpNameTagTest {

    private val sweden = "\uD83C\uDDF8\uD83C\uDDEA"   // 🇸🇪
    private val germany = "\uD83C\uDDE9\uD83C\uDDEA"  // 🇩🇪

    // --- geo tag -------------------------------------------------------------

    @Test
    fun geoTagOfReturnsTheFlagTail() {
        val name = "My proxy $sweden Sweden (Alexhost)"

        assertEquals("$sweden Sweden (Alexhost)", GeoIpAnnotator.geoTagOf(name))
    }

    @Test
    fun geoTagOfIsEmptyWithoutAFlag() {
        assertEquals("", GeoIpAnnotator.geoTagOf("My proxy"))
    }

    @Test
    fun stripGeoTagKeepsTheBaseName() {
        val name = "My proxy $sweden Sweden (Alexhost)"

        assertEquals("My proxy", GeoIpAnnotator.stripGeoTag(name))
    }

    @Test
    fun stripGeoTagDropsJunkBetweenNameAndFlag() {
        // A subscription name often carries its own emoji/marketing tail, and the
        // old annotation appended a flag after it. Everything from the FIRST flag
        // on must go, otherwise re-annotation stacks tags.
        val name = "tg:VLESSFORU $germany \u26A1 t.me/x $sweden Sweden (Paul)"

        assertEquals("tg:VLESSFORU", GeoIpAnnotator.stripGeoTag(name))
    }

    @Test
    fun stripGeoTagIsIdempotent() {
        val once = GeoIpAnnotator.stripGeoTag("Proxy $sweden Sweden (Alexhost)")

        assertEquals("Proxy", once)
        assertEquals("Proxy", GeoIpAnnotator.stripGeoTag(once))
    }

    // --- speed marker --------------------------------------------------------

    @Test
    fun stripSpeedMarkerRemovesTheLeadingMarker() {
        assertEquals("My proxy", GeoIpAnnotator.stripSpeedMarker("\u2728 42.3 My proxy"))
    }

    @Test
    fun stripSpeedMarkerDropsStackedLegacyValues() {
        // earlier builds appended a new number on every speed test
        // ("🏁 24.7 23.9 24.0 <name>") — re-annotation has to collapse the pile.
        assertEquals("My proxy", GeoIpAnnotator.stripSpeedMarker("\uD83C\uDFC1 24.7 23.9 24.0 My proxy"))
    }

    @Test
    fun stripSpeedMarkerDropsABareEmoji() {
        assertEquals("My proxy", GeoIpAnnotator.stripSpeedMarker("\uD83C\uDFF4 My proxy"))
    }

    @Test
    fun stripSpeedMarkerLeavesUnrelatedEmojiAlone() {
        // ⚡ belongs to the subscription name, not to the speed test.
        val name = "\u26A1 My proxy"

        assertEquals(name, GeoIpAnnotator.stripSpeedMarker(name))
    }

    @Test
    fun speedMarkerOfNormalisesATemplateString() {
        assertEquals("\u2728 42.3 ", GeoIpAnnotator.speedMarkerOf("\u2728 42.3 My proxy"))
    }

    @Test
    fun speedMarkerOfCollapsesStackedValuesToTheFirst() {
        assertEquals("\uD83C\uDFC1 24.7 ", GeoIpAnnotator.speedMarkerOf("\uD83C\uDFC1 24.7 23.9 24.0 My proxy"))
    }

    @Test
    fun speedMarkerOfIsEmptyWithoutAMarker() {
        assertEquals("", GeoIpAnnotator.speedMarkerOf("My proxy"))
    }

    // --- annotation ----------------------------------------------------------

    @Test
    fun annotateNameReplacesTheWholeName() {
        val name = "My proxy"

        assertEquals(
            "$sweden Sweden (Alexhost)",
            GeoIpAnnotator.annotateName(name, GeoInfo(sweden, "Sweden", "Alexhost")),
        )
    }

    @Test
    fun annotateNameFallsBackToTheBaseNameWithoutAFlag() {
        // Unknown IP, and the current name carries no tag yet: the base name survives.
        assertEquals("My proxy", GeoIpAnnotator.annotateName("My proxy", GeoInfo.UNKNOWN))
    }

    @Test
    fun annotateNameCleansAStaleGeoTagWhenUnknown() {
        // A previously annotated profile whose IP no longer resolves: the old
        // tag has to disappear instead of lingering on the profile.
        val stale = "$sweden Sweden (Alexhost)"

        assertEquals("", GeoIpAnnotator.annotateName(stale, GeoInfo.UNKNOWN))
    }

    // --- transferAcrossSubscriptionRefresh -----------------------------

    @Test
    fun transferAnnotationsCarriesBothMarkersOntoAFreshName() {
        val old = "\u2728 42.3 Old (ISP) $sweden Sweden (Alexhost)"

        assertEquals(
            "\u2728 42.3 New (ISP) $sweden Sweden (Alexhost)",
            GeoIpAnnotator.transferAnnotations(old, "New (ISP)"),
        )
    }

    @Test
    fun transferAnnotationsIsIdempotentAgainstANameThatAlreadyHasTags() {
        val old = "\u2728 42.3 Base $sweden Sweden (Alexhost)"

        assertEquals(old, GeoIpAnnotator.transferAnnotations(old, old))
    }
}
