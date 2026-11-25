package com.tactical.neoforge.battle;

import com.tactical.neoforge.mixin.ShulkerNativeInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.monster.Shulker;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The four native shulker entries this encounter drives, reached instead of re-implemented.
 *
 * <p>All four are private or package-private in {@code net.minecraft.world.entity.monster}, and each of them is
 * the single source of truth for a state the client also reads: {@code setRawPeekAmount} writes the peek the
 * shell animation answers to and applies or removes the covered armour, {@code setAttachFace} writes the
 * synched face the renderer rotates by, and {@code teleportSomewhere} is the relocation that also fires the
 * cancelable {@code EnderEntity} event. A copy of any of them would be a second source of truth - the client
 * would keep drawing the old one.</p>
 *
 * <p>The descriptors are bytecode facts of this target version, not inherited guesses: a changed signature fails
 * mixin application instead of silently doing nothing.</p>
 */
public final class ShulkerNative {

    private ShulkerNative() {}

    /**
     * The relocation permission, keyed by the exact body it was opened for.
     *
     * <p>Deliberately not one thread-wide slot. A single global permit could be dropped by another unit's
     * settlement - or, worse, survive a refused attempt and license an unrelated relocation later in the same
     * tick. Keying it by the entity makes the permission belong to exactly one body, single-use, and revocable
     * by that body's own scope alone.</p>
     */
    private static final Map<UUID, Integer> AUTHORIZED = new HashMap<>();

    private static ShulkerNativeInvoker invoker(Shulker shulker) {
        if (shulker instanceof ShulkerNativeInvoker nativeInvoker) return nativeInvoker;
        throw new IllegalStateException("Shulker tactical mixin missing: register tactical.shulker.mixins.json");
    }

    public static TacticalShulkerAccess access(Shulker shulker) {
        if (shulker instanceof TacticalShulkerAccess access) return access;
        throw new IllegalStateException("Shulker tactical mixin missing: register tactical.shulker.mixins.json");
    }

    /** The synched attach face vanilla itself uses. */
    public static Direction attachFace(Shulker shulker) {
        return invoker(shulker).tactical$attachFace();
    }

    /** Write vanilla's own attach face, exactly as the native support-loss rule does. */
    public static void setAttachFace(Shulker shulker, Direction face) {
        invoker(shulker).tactical$setAttachFace(face);
    }

    /** The raw peek value: {@code 0} is closed, larger numbers open the shell further. */
    public static int rawPeek(Shulker shulker) {
        return invoker(shulker).tactical$rawPeek();
    }

    /** Write the raw peek, applying vanilla's own armour modifier, sounds and game events. */
    public static void setRawPeek(Shulker shulker, int peek) {
        invoker(shulker).tactical$setRawPeek(peek);
    }

    /** The vanilla support question, asked instead of copied. */
    public static boolean canStayAt(Shulker shulker, BlockPos cell, Direction face) {
        return invoker(shulker).tactical$canStayAt(cell, face);
    }

    /** The first face vanilla would re-glue this body to at that cell, or {@code null}. */
    @javax.annotation.Nullable
    public static Direction findAttachableSurface(Shulker shulker, BlockPos cell) {
        return invoker(shulker).tactical$findAttachableSurface(cell);
    }

    /**
     * Allow exactly one native relocation of that body, and nothing else.
     *
     * <p>Opening the permission does not move anything: it only makes the native method's own
     * {@code isNoAi()} guard answer false for that one unit, for one call. The entity's flag is never
     * written.</p>
     */
    public static void authorizeOnce(Shulker shulker) {
        if (shulker == null) return;
        AUTHORIZED.put(shulker.getUUID(), 1);
    }

    /** Whether that body currently holds an unused permission. */
    public static boolean holdsAuthorization(Shulker shulker) {
        return shulker != null && AUTHORIZED.containsKey(shulker.getUUID());
    }

    /**
     * Consume the permission for that body.
     *
     * <p>Called from the native {@code isNoAi()} guard inside {@code teleportSomewhere}. True exactly once, only
     * for the authorized unit.</p>
     */
    public static boolean consumeAuthorization(Shulker shulker) {
        if (shulker == null) return false;
        Integer pending = AUTHORIZED.get(shulker.getUUID());
        if (pending == null || pending <= 0) return false;
        AUTHORIZED.remove(shulker.getUUID());
        return true;
    }

    /**
     * Revoke any unused permission of that body.
     *
     * <p>Called when the scope that opened it closes without the native entry having used it: a cancelled hit, a
     * damage path that refused the hit, or a random branch vanilla did not take must never leave a permit that a
     * later, different source could spend for free.</p>
     */
    public static void revoke(Shulker shulker) {
        if (shulker != null) AUTHORIZED.remove(shulker.getUUID());
    }

    /** Drop every permission: encounter teardown only. */
    public static void clearAll() {
        AUTHORIZED.clear();
    }

    /**
     * Drive vanilla's own {@code teleportSomewhere()} once.
     *
     * <p>The native method keeps its five attempts, its three-axis {@code ±8} search, its own support and
     * collision gates and the cancelable {@code EnderEntity} event. What this adds is the one-call permission the
     * controlled unit needs, revoked in a {@code finally} including when the native call throws.</p>
     *
     * @return the native answer: true when the body really relocated
     */
    public static boolean teleportSomewhere(Shulker shulker) {
        authorizeOnce(shulker);
        try {
            return invoker(shulker).tactical$teleportSomewhere();
        } finally {
            revoke(shulker);
        }
    }
}
