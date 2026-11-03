package com.tactical.neoforge.client.ui.skill;

import com.tactical.neoforge.client.ui.layout.Fingerprint;
import com.tactical.neoforge.network.SkillView;

import java.util.List;

/**
 * The identity of the skill list's geometry: which skills are on it, in which order, and which of
 * them are usable right now.
 *
 * <p>The skill page builds its list once and reuses it until something that can move a row changes.
 * Counting rows is not enough - a loadout swap can replace a skill with another one and keep the
 * count - so the fingerprint hashes every stable id, its level and its ready flag. Values that are
 * only drawn (MP now, the cooldown text, a description that gets truncated) are deliberately absent:
 * those redraw without a rebuild, which is what keeps a busy page from relaying out every frame.</p>
 */
public final class SkillListFingerprint {

    private SkillListFingerprint() {
    }

    public static long of(List<SkillView> views) {
        if (views == null) return Fingerprint.mix(Fingerprint.start(), -1L);
        long hash = Fingerprint.mix(Fingerprint.start(), views.size());
        for (SkillView view : views) {
            hash = Fingerprint.text(hash, view.id() == null ? null : view.id().toString());
            hash = Fingerprint.mix(hash, view.level());
            hash = Fingerprint.mix(hash, view.ready() ? 1L : 0L);
        }
        return hash;
    }
}
