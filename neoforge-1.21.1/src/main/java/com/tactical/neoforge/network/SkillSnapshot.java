package com.tactical.neoforge.network;

import java.util.List;

/**
 * One immutable, complete skill state: everything a client shows after it has assembled every page.
 *
 * <p>This is the shared model, not a wire type. The server keeps the last published snapshot as the
 * encounter's own change detection baseline, the pager slices it into bounded pages, and the client
 * publishes it in one step once all pages of one publication have arrived. Nothing per-page ever
 * replaces a client's view: a page is only a fragment of this value.</p>
 *
 * <p>The limits are the protocol contract, checked before anything is allocated or sent:</p>
 * <ul>
 *   <li>at most {@value SkillPacketIO#MAX_SKILLS} skills - a legitimate full list is never truncated;</li>
 *   <li>at most 4096 friendly entity ids (identity-bearing relations, not side ids);</li>
 *   <li>finite, non-negative resources with {@code mp <= maximum};</li>
 *   <li>an inactive state carries no skills at all (the existing inactive contract).</li>
 * </ul>
 *
 * <p>Equality is value equality: every field is an immutable value, so the server can compare the freshly
 * built snapshot with the last published one instead of tracking dirty flags.</p>
 */
public record SkillSnapshot(boolean active, double mp, double maximum, double recovery,
                            List<SkillView> skills, List<Integer> friendlyIds) {

    public static final int MAX_FRIENDLY_IDS = 4096;

    public SkillSnapshot {
        skills = List.copyOf(skills);
        friendlyIds = List.copyOf(friendlyIds);
        SkillPacketIO.finiteResource(mp);
        SkillPacketIO.finiteResource(maximum);
        SkillPacketIO.finiteResource(recovery);
        if (mp > maximum || skills.size() > SkillPacketIO.MAX_SKILLS || friendlyIds.size() > MAX_FRIENDLY_IDS
                || !active && !skills.isEmpty())
            throw new IllegalArgumentException("Invalid skill snapshot");
    }

    public int skillCount() {
        return skills.size();
    }
}
