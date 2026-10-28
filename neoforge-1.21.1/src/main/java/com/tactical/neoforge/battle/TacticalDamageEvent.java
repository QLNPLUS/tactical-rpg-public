package com.tactical.neoforge.battle;

import com.tactical.core.combat.PositionalDamage;
import net.neoforged.bus.api.Event;

import java.util.Objects;

/** Posted once per eligible battle attack, after base factors and registered native modifiers.
 * Listeners multiply an independent stage; a backstab skill uses multiply(Stage.SKILL, 2).
 * No cancellation or client classes. Invalid/nonfinite/negative factors are ignored, zero is valid.
 * Each bucket is capped at 1e6; total is at most 1e18 and excludes defense. */
public final class TacticalDamageEvent extends Event {
    private final TacticalDamageMultipliers.Context context;
    private final TacticalDamageMultipliers.Breakdown base;
    private TacticalDamageMultipliers.Breakdown current;

    public TacticalDamageEvent(TacticalDamageMultipliers.Context context,
                               TacticalDamageMultipliers.Breakdown base) {
        this.context = Objects.requireNonNull(context, "context");
        this.base = Objects.requireNonNull(base, "base");
        this.current = base;
    }

    public TacticalDamageMultipliers.Context context() { return context; }
    public TacticalDamageMultipliers.Breakdown base() { return base; }
    public TacticalDamageMultipliers.Breakdown breakdown() { return current; }

    public void multiply(TacticalDamageMultipliers.Stage stage, double factor) {
        if (stage == null || !PositionalDamage.validFactor(factor)) return;
        double direction = current.direction(), height = current.height(), skill = current.skill();
        switch (stage) {
            case DIRECTION -> direction = PositionalDamage.multiply(direction, factor);
            case HEIGHT -> height = PositionalDamage.multiply(height, factor);
            case SKILL -> skill = PositionalDamage.multiply(skill, factor);
        }
        current = new TacticalDamageMultipliers.Breakdown(current.sector(), direction, height, skill);
    }

    void restore(TacticalDamageMultipliers.Breakdown before) { current = before; }
}
