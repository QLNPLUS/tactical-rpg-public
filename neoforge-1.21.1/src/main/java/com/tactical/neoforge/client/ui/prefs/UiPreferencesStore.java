package com.tactical.neoforge.client.ui.prefs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * The only code that touches the preference file.
 *
 * <p>Everything above it works on {@link UiPreferences} values, so there is exactly one file, one
 * format and one in-memory copy: no class keeps a second {@link Properties} that could drift. A
 * change made by the UI stays in memory until the caller saves - a size drag updates geometry every
 * frame and writes once when the pointer is released - which is why {@link #update} never writes.</p>
 *
 * <p>Failures are not fatal and never destroy other settings: a missing file, an unreadable file or a
 * parse error all fall back to the defaults, and a failed save keeps the in-memory values intact.
 * The revision is bumped by every change, including a reload, so a layout can tell that something it
 * cached has moved underneath it.</p>
 */
public final class UiPreferencesStore {

    public static final String FILE_NAME = "tactical-ui.properties";
    private static final String COMMENT = "Tactical UI preferences; viewport stays at 100%";

    private final Path file;
    private volatile UiPreferences current;
    private volatile boolean loaded;
    /** Reported by the caller, so a failed write is visible without throwing into a render path. */
    private volatile String lastFailure = "";

    public UiPreferencesStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public String lastFailure() {
        return lastFailure;
    }

    public synchronized UiPreferences current() {
        if (!loaded) load();
        return current;
    }

    /**
     * Reads the file once, and again only when the caller really wants a reload.
     *
     * <p>The revision is the session's change counter, so it must move forward from the previous value
     * even though the file knows nothing about it: the parsed value always starts at zero, and taking
     * that as the new counter would pin every reload to the same number, leaving a cached layout
     * convinced nothing changed when the file on disk did.</p>
     */
    public synchronized UiPreferences load() {
        loaded = true;
        UiPreferences previous = current;
        UiPreferences parsed = read();
        current = previous == null
                ? parsed.withRevision(parsed.revision() + 1L)
                : parsed.withRevision(previous.revision() + 1L);
        return current;
    }

    private UiPreferences read() {
        if (file == null || !Files.isRegularFile(file)) return UiPreferences.defaults();
        try (InputStream in = Files.newInputStream(file)) {
            Properties properties = new Properties();
            properties.load(in);
            return UiPreferencesCodec.read(properties);
        } catch (IOException | RuntimeException failure) {
            lastFailure = "read " + failure.getClass().getSimpleName();
            return UiPreferences.defaults();
        }
    }

    /** Applies a change in memory and bumps the revision; nothing is written until {@link #save}. */
    public synchronized UiPreferences update(UnaryOperator<UiPreferences> change) {
        UiPreferences base = current();
        UiPreferences changed = change == null ? base : change.apply(base);
        current = (changed == null ? base : changed).nextRevision();
        return current;
    }

    /** Writes the whole preference set. Called on a confirmed change, never per frame. */
    public synchronized boolean save() {
        UiPreferences prefs = current();
        try {
            if (file == null) return false;
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Properties existing = new Properties();
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    existing.load(in);
                } catch (IOException | RuntimeException ignored) {
                    // A broken file is replaced by the values in memory; unrelated keys are only kept
                    // when the old file could still be read.
                }
            }
            Properties written = UiPreferencesCodec.write(existing, prefs);
            try (OutputStream out = Files.newOutputStream(file)) {
                written.store(out, COMMENT);
            }
            lastFailure = "";
            return true;
        } catch (IOException | RuntimeException failure) {
            lastFailure = "write " + failure.getClass().getSimpleName();
            return false;
        }
    }
}
