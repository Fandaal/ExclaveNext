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
    // Format: "<emoji> <name...> ↓<Mbps>" — the emoji stays a leading marker, the
    // value moves to a trailing ↓N so it reads after the geo tag.

    @Test
    fun stripSpeedMarkerRemovesTheTrailingValue() {
        assertEquals(
            "✨ 🇷🇺 Russia (MTS)",
            GeoIpAnnotator.stripSpeedMarker("✨ 🇷🇺 Russia (MTS) ↓22.2"),
        )
    }

    @Test
    fun stripSpeedMarkerKeepsTheLeadingEmoji() {
        // Only the ↓N tail is removed; the marker emoji belongs to the name.
        assertEquals("🏁 My proxy", GeoIpAnnotator.stripSpeedMarker("🏁 My proxy ↓24.7"))
    }

    @Test
    fun stripSpeedMarkerRemovesLegacyLeadingValues() {
        // Must still heal names written by earlier builds, which put the value
        // right after the emoji ("🏁 24.7 23.9 24.0 <name>").
        assertEquals("My proxy", GeoIpAnnotator.stripSpeedMarker("🏁 24.7 My proxy"))
        assertEquals("My proxy", GeoIpAnnotator.stripSpeedMarker("🏁 24.7 23.9 24.0 My proxy"))
    }

    @Test
    fun stripSpeedMarkerKeepsABareEmojiPrefix() {
        // Under the current layout a bare emoji IS a valid marker: it means the
        // profile is dead and had no measurable speed. Only the value is ours to
        // strip — dropping the emoji here would erase dead/degraded markers.
        assertEquals("🏴 My proxy", GeoIpAnnotator.stripSpeedMarker("🏴 My proxy"))
    }

    @Test
    fun stripSpeedMarkerLeavesUnrelatedEmojiAlone() {
        // ⚡ belongs to the subscription name, not to the speed test.
        val name = "⚡ My proxy"

        assertEquals(name, GeoIpAnnotator.stripSpeedMarker(name))
    }

    @Test
    fun stripSpeedMarkerLeavesAnUnrelatedArrowAlone() {
        val name = "⚡ My proxy ↓42.3"

        assertEquals(name, GeoIpAnnotator.stripSpeedMarker(name))
    }

    @Test
    fun speedMarkerOfReturnsTheLeadingEmoji() {
        // No trailing space: the emoji itself, callers add the separator.
        assertEquals("✨", GeoIpAnnotator.speedMarkerOf("✨ My proxy ↓42.3"))
    }

    @Test
    fun speedMarkerOfIsEmptyWithoutAMarker() {
        assertEquals("", GeoIpAnnotator.speedMarkerOf("My proxy ↓42.3"))
    }

    @Test
    fun speedValueOfReadsTheTrailingValue() {
        assertEquals("22.2", GeoIpAnnotator.speedValueOf("✨ 🇷🇺 Russia (MTS) ↓22.2"))
    }

    @Test
    fun speedValueOfHandlesZeroAndBareEmoji() {
        assertEquals("0.0", GeoIpAnnotator.speedValueOf("🏴 My proxy ↓0.0"))
    }

    @Test
    fun speedValueOfIsEmptyWithoutASuffix() {
        assertEquals("", GeoIpAnnotator.speedValueOf("✨ My proxy"))
    }

    @Test
    fun speedValueOfIsEmptyWhenTheArrowIsNotANumber() {
        assertEquals("", GeoIpAnnotator.speedValueOf("✨ My proxy ↓fast"))
    }

    @Test
    fun composeSpeedNamePutsTheValueAfterTheGeoTag() {
        assertEquals(
            "🏁 🇷🇺 Russia (MTS) ↓22.2",
            GeoIpAnnotator.composeSpeedName("🏁 🇷🇺 Russia (MTS)", "🏁", 22.2),
        )
    }

    @Test
    fun composeSpeedNameWithZeroKeepsTheMarkerButNoValue() {
        // A zero download is written as the bare 🏴 marker: "↓0.0" is noise.
        assertEquals(
            "🏴 🇷🇺 Russia (MTS)",
            GeoIpAnnotator.composeSpeedName("🏴 🇷🇺 Russia (MTS)", "🏴", 0.0),
        )
    }

    @Test
    fun composeSpeedNameOmitsTheArrowWhenTheMarkerIsTheDeadTriangularFlag() {
        // Ping never passed: 🚩, no number — distinct from the alive-but-zero 🏴.
        assertEquals("🚩 My proxy", GeoIpAnnotator.composeSpeedName("🚩 My proxy", "🚩", null))
    }

    @Test
    fun composeSpeedNameReplacesAPreviousZeroValue() {
        // A re-run that still measures zero must not resurrect "↓0.0".
        assertEquals(
            "🏴 My proxy",
            GeoIpAnnotator.composeSpeedName("🏴 My proxy ↓0.0", "🏴", 0.0),
        )
    }

    @Test
    fun composeSpeedNameWithoutAValueOmitsTheArrow() {
        // Dead profile: ping never passed, so there is no number to show. The
        // marker passed in is whatever the caller decided; here 🏴 still shows
        // a bare marker with no value.
        assertEquals("🏴 My proxy", GeoIpAnnotator.composeSpeedName("🏴 My proxy", "🏴", null))
    }

    @Test
    fun composeSpeedNameReplacesAPreviousValue() {
        // Re-running a speed test overwrites, never stacks.
        assertEquals(
            "🏁 🇷🇺 Russia (MTS) ↓41.0",
            GeoIpAnnotator.composeSpeedName("🏁 🇷🇺 Russia (MTS) ↓22.2", "🏁", 41.0),
        )
    }

    @Test
    fun composeSpeedNameHealsALegacyLeadingValue() {
        assertEquals(
            "🏁 🇷🇺 Russia (MTS) ↓22.2",
            GeoIpAnnotator.composeSpeedName("🏁 22.2 🇷🇺 Russia (MTS)", "🏁", 22.2),
        )
    }

    @Test
    fun composeSpeedNameReplacesThePreviousEmoji() {
        // A faster profile must pick up the ✨ marker, not keep the old 🏁.
        assertEquals(
            "✨ 🇷🇺 Russia (MTS) ↓61.4",
            GeoIpAnnotator.composeSpeedName("🏁 🇷🇺 Russia (MTS) ↓22.2", "✨", 61.4),
        )
    }

    @Test
    fun composeSpeedNameReplacesEmojiWhenTheValueIsDropped() {
        // Downgrade: 🏁 → 🏴 with no number at all.
        assertEquals(
            "🏴 🇷🇺 Russia (MTS)",
            GeoIpAnnotator.composeSpeedName("🏁 🇷🇺 Russia (MTS) ↓22.2", "🏴", null),
        )
    }

    @Test
    fun composeSpeedNameKeepsTheBaseNameWithoutAGeoTag() {
        assertEquals("🏁 My proxy ↓8.5", GeoIpAnnotator.composeSpeedName("🏁 My proxy", "🏁", 8.5))
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
        val old = "✨ Old (ISP) $sweden Sweden (Alexhost) ↓42.3"

        assertEquals(
            "✨ New (ISP) $sweden Sweden (Alexhost) ↓42.3",
            GeoIpAnnotator.transferAnnotations(old, "New (ISP)"),
        )
    }

    @Test
    fun transferAnnotationsIsIdempotentAgainstANameThatAlreadyHasTags() {
        val old = "✨ Base $sweden Sweden (Alexhost) ↓42.3"

        assertEquals(old, GeoIpAnnotator.transferAnnotations(old, old))
    }

    @Test
    fun transferAnnotationsHealsALegacyLeadingValue() {
        // Old build format: value directly after the emoji, before the geo tag.
        val old = "✨ 42.3 Old (ISP) $sweden Sweden (Alexhost)"

        assertEquals(
            "✨ New (ISP) $sweden Sweden (Alexhost) ↓42.3",
            GeoIpAnnotator.transferAnnotations(old, "New (ISP)"),
        )
    }

    @Test
    fun transferAnnotationsDropsAStoredZeroValue() {
        // "↓0.0" must not ride onto the fresh name: a zero measurement is the
        // bare marker's job (composeSpeedName rule), and resurrecting the value
        // here would undo it on every subscription refresh.
        val old = "🏴 Old (ISP) $sweden Sweden (Alexhost) ↓0.0"

        assertEquals(
            "🏴 New (ISP) $sweden Sweden (Alexhost)",
            GeoIpAnnotator.transferAnnotations(old, "New (ISP)"),
        )
    }

    @Test
    fun transferAnnotationsKeepsANameWithNoSpeedAtAll() {
        assertEquals(
            "New $sweden Sweden (Alexhost)",
            GeoIpAnnotator.transferAnnotations("Base $sweden Sweden (Alexhost)", "New"),
        )
    }
}
