package com.tactical.neoforge.client.ui.prefs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preference foundation: one codec, one file, and the promise that a file written by an older
 * build or damaged by hand always degrades to "today's appearance" instead of to a broken page.
 */
class UiPreferencesCodecTest {

    // ------------------------------------------------------------------ defaults and legacy

    @Test void anAbsentFileMeansDefaults() {
        UiPreferences prefs = UiPreferencesCodec.read(new Properties());
        assertEquals(100, prefs.percent());
        assertFalse(prefs.showRangeLegend());
        assertTrue(prefs.rosterWidthAuto());
        assertTrue(prefs.rosterHeightAuto());
        assertTrue(prefs.journalWidthAuto());
        assertTrue(prefs.journalHeightAuto());
        assertNull(prefs.themeRgb(), "an untouched file must not recolour anything");
        assertNull(prefs.rangeRgb());
    }

    @Test void oldStepFilesStillMigrateToTheSamePreset() {
        Properties legacy = new Properties();
        legacy.setProperty("step", "0");
        legacy.setProperty("unrelatedKey", "keep me");
        UiPreferences prefs = UiPreferencesCodec.read(legacy);
        assertEquals(85, prefs.percent(), "legacy step 0 really was 85%");
        Properties written = UiPreferencesCodec.write(legacy, prefs);
        assertEquals("3", written.getProperty("version"));
        assertNull(written.getProperty("step"), "the old key is dropped by the size writer");
        assertEquals("keep me", written.getProperty("unrelatedKey"));
        assertEquals(85, UiPreferencesCodec.read(written).percent(), "the migrated value is stable");
    }

    @Test void anOutOfRangePercentageStillSnapsIntoTheRange() {
        Properties old = new Properties();
        old.setProperty("version", "2");
        old.setProperty("percent", "125");
        assertEquals(115, UiPreferencesCodec.read(old).percent());
        old.setProperty("percent", "oops");
        assertEquals(100, UiPreferencesCodec.read(old).percent(), "a corrupt size is not an end of the range");
    }

    // ------------------------------------------------------------------ new fields

    @Test void aStoredDimensionIsKeptInsideItsBoundsAndOtherwiseMeansAuto() {
        Properties p = new Properties();
        p.setProperty(UiPreferencesCodec.ROSTER_WIDTH, "180");
        p.setProperty(UiPreferencesCodec.ROSTER_HEIGHT, "64");
        p.setProperty(UiPreferencesCodec.JOURNAL_WIDTH, "garbage");
        p.setProperty(UiPreferencesCodec.JOURNAL_HEIGHT, "9999");
        UiPreferences prefs = UiPreferencesCodec.read(p);
        assertEquals(180, prefs.rosterWidth().intValue());
        assertEquals(64, prefs.rosterHeight().intValue());
        assertNull(prefs.journalWidth(), "a broken field falls back to AUTO on its own");
        assertNull(prefs.journalHeight(), "a value outside the accepted range is not a size");
        assertEquals(100, prefs.percent(), "a broken field never resets the sizes that were readable");
    }

    @Test void aBrokenAlphaPairIsNotWashedIntoAValidColour() {
        // Stripping the alpha before validating turned these into a perfectly good 112233 / 004455.
        assertNull(UiPreferencesCodec.parseRgb("GG112233"));
        assertNull(UiPreferencesCodec.parseRgb("--112233"));
        assertNull(UiPreferencesCodec.parseRgb("ZZ004455"));
        assertNull(UiPreferencesCodec.parseRgb("0xZZ1122"));
        assertNull(UiPreferencesCodec.parseRgb("##112233"));
        assertEquals(0x223344, UiPreferencesCodec.parseRgb("11223344").intValue(),
                "eight valid digits are read as alpha + RGB, so the first pair is never part of the colour");
        assertEquals(0xC4473D, UiPreferencesCodec.parseRgb("FFC4473D").intValue(),
                "a legal AARRGGBB is still accepted, with the alpha dropped");
        assertEquals(0xC4473D, UiPreferencesCodec.parseRgb("#FFC4473D").intValue());
    }

    @Test void coloursAcceptEveryWrittenFormAndRejectRubbish() {
        assertEquals(0xC4473D, UiPreferencesCodec.parseRgb("C4473D").intValue());
        assertEquals(0xC4473D, UiPreferencesCodec.parseRgb("#c4473d").intValue());
        assertEquals(0xC4473D, UiPreferencesCodec.parseRgb("0xC4473D").intValue());
        assertEquals(0xC4473D, UiPreferencesCodec.parseRgb("FFC4473D").intValue(), "alpha is accepted and dropped");
        assertNull(UiPreferencesCodec.parseRgb(""));
        assertNull(UiPreferencesCodec.parseRgb("12345"));
        assertNull(UiPreferencesCodec.parseRgb("ZZZZZZ"));
        assertNull(UiPreferencesCodec.parseRgb(null));
        Properties p = new Properties();
        p.setProperty(UiPreferencesCodec.THEME_RGB, "#00FF80");
        p.setProperty(UiPreferencesCodec.RANGE_RGB, "nope");
        assertEquals(0x00FF80, UiPreferencesCodec.read(p).themeRgb().intValue());
        assertNull(UiPreferencesCodec.read(p).rangeRgb());
    }

    @Test void writingKeepsUnknownKeysAndDropsTheOnesThatMeanAbsent() throws IOException {
        Properties existing = new Properties();
        existing.setProperty(UiPreferencesCodec.ROSTER_WIDTH, "200");
        existing.setProperty(UiPreferencesCodec.JOURNAL_HEIGHT, "70");
        existing.setProperty("futurePreference", "keep");
        existing.setProperty("step", "2");
        UiPreferences prefs = UiPreferencesCodec.read(existing).withRosterSize(null, 120)
                .withColors(0x112233, null);
        Properties written = roundTrip(UiPreferencesCodec.write(existing, prefs));
        assertEquals("keep", written.getProperty("futurePreference"));
        assertNull(written.getProperty("step"));
        assertNull(written.getProperty(UiPreferencesCodec.ROSTER_WIDTH), "clearing a size removes the key");
        assertEquals("120", written.getProperty(UiPreferencesCodec.ROSTER_HEIGHT));
        assertEquals("70", written.getProperty(UiPreferencesCodec.JOURNAL_HEIGHT));
        assertEquals("112233", written.getProperty(UiPreferencesCodec.THEME_RGB));
        assertNull(written.getProperty(UiPreferencesCodec.RANGE_RGB));
        UiPreferences back = UiPreferencesCodec.read(written);
        assertNull(back.rosterWidth());
        assertEquals(120, back.rosterHeight().intValue());
        assertEquals(0x112233, back.themeRgb().intValue());
    }

    @Test void everyChangeBumpsTheRevision() {
        UiPreferences prefs = UiPreferences.defaults();
        long seen = prefs.revision();
        UiPreferences[] steps = new UiPreferences[5];
        prefs = prefs.withPercent(110);
        steps[0] = prefs;
        prefs = prefs.withRangeLegend(true);
        steps[1] = prefs;
        prefs = prefs.withRosterSize(150, 90);
        steps[2] = prefs;
        prefs = prefs.withJournalSize(150, null);
        steps[3] = prefs;
        prefs = prefs.withColors(0x101010, 0x202020);
        steps[4] = prefs;
        for (UiPreferences next : steps) {
            assertTrue(next.revision() > seen, "a change must move the revision the layout caches");
            seen = next.revision();
        }
        assertEquals(seen, prefs.nextRevision().revision() - 1);
    }

    @Test void sizesAreNormalisedOnConstructionSoNoBadValueEverReachesALayout() {
        UiPreferences prefs = new UiPreferences(100, false, 1, 5000, -3, null, 0x1000000, -1, 0);
        assertNull(prefs.rosterWidth());
        assertNull(prefs.rosterHeight());
        assertNull(prefs.journalWidth());
        assertNull(prefs.journalHeight());
        assertNull(prefs.themeRgb());
        assertNull(prefs.rangeRgb());
        assertEquals(UiPreferences.MIN_SIZE, (int) UiPreferences.size(UiPreferences.MIN_SIZE));
        assertEquals(UiPreferences.MAX_SIZE, (int) UiPreferences.size(UiPreferences.MAX_SIZE));
    }

    // ------------------------------------------------------------------ the journal's own domain

    @Test void theJournalBodyReachesDownToEighteenAndTheOtherSizesDoNot() {
        Properties p = new Properties();
        p.setProperty(UiPreferencesCodec.JOURNAL_HEIGHT, "18");
        assertEquals(18, UiPreferencesCodec.read(p).journalHeight().intValue(),
                "one two-line entry is a legal body height, so 18 must not fall back to AUTO");
        p.setProperty(UiPreferencesCodec.JOURNAL_HEIGHT, "17");
        assertNull(UiPreferencesCodec.read(p).journalHeight());
        p.setProperty(UiPreferencesCodec.JOURNAL_HEIGHT, "400");
        assertEquals(400, UiPreferencesCodec.read(p).journalHeight().intValue());
        p.setProperty(UiPreferencesCodec.JOURNAL_HEIGHT, "401");
        assertNull(UiPreferencesCodec.read(p).journalHeight());

        // The same number is not a legal panel width: one accepted range for every key would either
        // refuse the body floor or accept a 20-pixel panel nobody can read.
        Properties panel = new Properties();
        panel.setProperty(UiPreferencesCodec.ROSTER_WIDTH, "18");
        panel.setProperty(UiPreferencesCodec.JOURNAL_WIDTH, "30");
        assertNull(UiPreferencesCodec.read(panel).rosterWidth());
        assertNull(UiPreferencesCodec.read(panel).journalWidth());
        assertEquals(18, (int) UiPreferences.bodyHeight(18));
        assertNull(UiPreferences.bodyHeight(17));
    }

    @Test void anEighteenPixelBodySurvivesAFullWriteAndRead() throws IOException {
        UiPreferences prefs = UiPreferences.defaults().withJournalSize(null, 18);
        assertEquals(18, prefs.journalHeight().intValue());
        Properties written = roundTrip(UiPreferencesCodec.write(new Properties(), prefs));
        assertEquals("18", written.getProperty(UiPreferencesCodec.JOURNAL_HEIGHT));
        assertEquals(18, UiPreferencesCodec.read(written).journalHeight().intValue());
    }

    // ------------------------------------------------------------------ "reset HUD sizes"

    @Test void theResetEntryIsDisabledOnTheEffectiveDefaultNotOnAnEmptyKey() {
        assertTrue(UiPreferences.defaults().hudSizesAreDefault(), "a fresh file has nothing to reset");
        assertTrue(UiPreferences.defaults().withRosterSize(140, null).hudSizesAreDefault(),
                "a width dragged back to 140 is the default again");
        assertTrue(UiPreferences.defaults().withJournalSize(140, 75).hudSizesAreDefault(),
                "and so is a body dragged back to 75");
        assertFalse(UiPreferences.defaults().withRosterSize(160, null).hudSizesAreDefault());
        assertFalse(UiPreferences.defaults().withJournalSize(null, 76).hudSizesAreDefault());
        assertFalse(UiPreferences.defaults().withRosterSize(null, 120).hudSizesAreDefault(),
                "an explicit height is never the AUTO default, whatever it measures today");
    }

    @Test void clearingTheGeometryLeavesThePresetTheColoursAndTheLegendAlone() {
        UiPreferences custom = UiPreferences.defaults().withPercent(110).withRangeLegend(true)
                .withColors(0x112233, 0x445566).withRosterSize(180, 140).withJournalSize(150, 90);
        assertFalse(custom.hudSizesAreDefault());
        // The same two calls UiZoom.resetHudSizes makes.
        UiPreferences cleared = custom.withRosterSize(null, null).withJournalSize(null, null);
        assertTrue(cleared.hudSizesAreDefault());
        assertEquals(110, cleared.percent(), "the type preset is not a HUD size");
        assertEquals(0x112233, cleared.themeRgb().intValue());
        assertEquals(0x445566, cleared.rangeRgb().intValue());
        assertTrue(cleared.showRangeLegend());
    }

    // ------------------------------------------------------------------ the file adapter

    @Test void theStoreCreatesTheFileOnSaveAndReadsItBack(@TempDir Path dir) {
        Path file = dir.resolve(UiPreferencesStore.FILE_NAME);
        UiPreferencesStore store = new UiPreferencesStore(file);
        assertFalse(Files.exists(file));
        assertEquals(100, store.current().percent(), "a missing file is the default, not an error");
        store.update(prefs -> prefs.withPercent(110).withJournalSize(160, 80).withColors(0x336699, null));
        assertEquals(110, store.current().percent());
        assertTrue(store.save(), "a save into a writable directory succeeds");
        assertTrue(Files.isRegularFile(file));
        UiPreferencesStore reopened = new UiPreferencesStore(file);
        assertEquals(110, reopened.current().percent());
        assertEquals(160, reopened.current().journalWidth().intValue());
        assertEquals(80, reopened.current().journalHeight());
        assertEquals(0x336699, reopened.current().themeRgb().intValue());
    }

    @Test void aDamagedFileFallsBackWithoutLosingItsOtherKeys(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(UiPreferencesStore.FILE_NAME);
        Files.writeString(file, "percent=not-a-number\nhudRosterWidth=wide\nuserNote=hello\nversion=3\n",
                StandardCharsets.ISO_8859_1);
        UiPreferencesStore store = new UiPreferencesStore(file);
        assertEquals(100, store.current().percent());
        assertNull(store.current().rosterWidth());
        store.update(prefs -> prefs.withPercent(95));
        assertTrue(store.save());
        String written = Files.readString(file, StandardCharsets.ISO_8859_1);
        assertTrue(written.contains("userNote=hello"), "a repair must not delete keys this build ignores");
        assertEquals(95, new UiPreferencesStore(file).current().percent());
    }

    @Test void anUnreadableLocationIsReportedAndNeverThrows(@TempDir Path dir) throws IOException {
        Path notAFile = dir.resolve("sub");
        Files.createDirectories(notAFile);
        UiPreferencesStore store = new UiPreferencesStore(notAFile);
        assertEquals(100, store.current().percent(), "the default is served instead of an exception");
        assertFalse(store.save(), "a directory in place of the file cannot be written");
        assertFalse(store.lastFailure().isEmpty(), "the failure is readable instead of thrown");
    }

    @Test void everyReloadMovesTheRevisionForward(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(UiPreferencesStore.FILE_NAME);
        Files.writeString(file, "percent=100\nversion=3\nshowRangeLegend=false\n", StandardCharsets.ISO_8859_1);
        UiPreferencesStore store = new UiPreferencesStore(file);

        long first = store.current().revision();
        long second = store.load().revision();
        long third = store.load().revision();
        assertTrue(second > first, "the first reload has to move the counter");
        assertTrue(third > second, "a second reload must not land on the same number");

        // The counter is the session's, the content is the file's: a change on disk is picked up and
        // still moves the counter forward.
        Files.writeString(file, "percent=110\nversion=3\nshowRangeLegend=false\n", StandardCharsets.ISO_8859_1);
        long fourth = store.load().revision();
        assertEquals(110, store.current().percent(), "the reload really read the new file");
        assertTrue(fourth > third);

        store.update(prefs -> prefs.withPercent(95));
        long fifth = store.current().revision();
        assertTrue(fifth > fourth);
        long sixth = store.load().revision();
        assertTrue(sixth > fifth, "a reload after a change keeps the counter monotonic");
        assertEquals(110, store.current().percent(), "the reload restores what the file says");
    }

    private static Properties roundTrip(Properties properties) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        properties.store(bytes, "preferences test");
        Properties loaded = new Properties();
        loaded.load(new java.io.ByteArrayInputStream(bytes.toByteArray()));
        assertNotNull(loaded);
        return loaded;
    }
}
