package com.tactical.neoforge.registry.companion;

import com.tactical.neoforge.api.companion.CompanionAdapter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The companion factory table: identity, duplicate refusal and the built-in default.
 *
 * <p>No mob is created here - an adapter's {@code supports} needs a live entity - so this pins the table's
 * own contract, which is what decides whether an add-on can quietly replace somebody else's companion.</p>
 */
class CompanionRegistryTest {

    private record Stub(ResourceLocation id, boolean supportsAll) implements CompanionAdapter {
        @Override
        public boolean supports(Mob mob) {
            return supportsAll;
        }

        @Override
        public UUID controllerId(Mob mob) {
            return null;
        }

        @Override
        public boolean canJoin(ServerLevel level, Mob mob, UUID controllerId) {
            return false;
        }
    }

    @AfterEach
    void restoreDefaults() {
        CompanionRegistry.clearForTests();
        CompanionRegistry.registerDefaults();
    }

    @Test
    void theDefaultTableClaimsExactlyTheBuiltInWolf() {
        CompanionRegistry.clearForTests();
        CompanionRegistry.registerDefaults();
        List<CompanionAdapter> adapters = CompanionRegistry.adapters();
        assertEquals(1, adapters.size(), "one built-in companion kind ships in this version");
        assertEquals(ResourceLocation.fromNamespaceAndPath("tactical", "wolf"), adapters.get(0).id());
        // Registering the defaults twice adds nothing.
        CompanionRegistry.registerDefaults();
        assertEquals(1, CompanionRegistry.adapters().size(), "the default install is idempotent");
    }

    @Test
    void registeringTheSameInstanceTwiceIsIdempotent() {
        Stub adapter = new Stub(ResourceLocation.fromNamespaceAndPath("tactical", "stub_once"), true);
        CompanionRegistry.doRegister(adapter);
        CompanionRegistry.doRegister(adapter);
        assertEquals(2, CompanionRegistry.adapters().size(), "the same instance is not added twice");
    }

    @Test
    void twoAdaptersMayNotShareOneId() {
        CompanionRegistry.doRegister(new Stub(ResourceLocation.fromNamespaceAndPath("tactical", "dup"), true));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                CompanionRegistry.doRegister(new Stub(ResourceLocation.fromNamespaceAndPath("tactical", "dup"), false)));
        assertTrue(failure.getMessage().contains("tactical:dup"),
                "a conflicting registration is refused, never silently replaced: " + failure.getMessage());
    }

    @Test
    void theTableKeepsClaimOrderSoASpecificKindCanWin() {
        CompanionRegistry.clearForTests();
        Stub specific = new Stub(ResourceLocation.fromNamespaceAndPath("tactical", "specific"), false);
        Stub broad = new Stub(ResourceLocation.fromNamespaceAndPath("tactical", "broad"), true);
        CompanionRegistry.doRegister(specific);
        CompanionRegistry.doRegister(broad);
        assertEquals(List.of(specific, broad), CompanionRegistry.adapters(),
                "registration order is the claim order");
    }

    @Test
    void anUnclaimedMobHasNoAdapter() {
        CompanionRegistry.clearForTests();
        CompanionRegistry.registerDefaults();
        assertNull(CompanionRegistry.find(null), "no entity, no companion kind");
        assertFalse(CompanionRegistry.adapters().isEmpty(), "the table itself is not empty");
    }

    @Test
    void clearingForTestsRestoresTheShippedDefault() {
        CompanionRegistry.clearForTests();
        assertTrue(CompanionRegistry.adapters().isEmpty(), "the fixture reset really empties the table");
        CompanionRegistry.registerDefaults();
        assertSame(CompanionRegistry.adapters().get(0).getClass(),
                com.tactical.neoforge.companion.WolfCompanionAdapter.class,
                "and the shipped default comes back");
    }
}
