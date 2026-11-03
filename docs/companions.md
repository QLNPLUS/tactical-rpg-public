# Companions: hand-commanded allies

A companion is a mob that a player commands by hand during a battle. The built-in kind is the vanilla
wolf; the control skeleton is generic, so another mod may register its own kind without touching the
battle, the network or the interface.

This page is the integration contract. It describes what an adapter must answer, what the battle owns,
and which lifecycle rules the battle already enforces so an add-on does not have to repeat them.

## What the battle owns

The battle (not the adapter) owns everything that decides authority:

- the roster slot, the turn queue entry, the AP pool, the movement animation and the guard;
- who may issue an order: the real connection player, the live session and turn generation, the acting
  slot, and the fact that the acting companion still resolves to that player;
- the release path: a controller leaving, the companion dying or unloading, and the encounter ending.

An adapter therefore cannot widen a rule. It answers four questions and nothing more.

## The adapter contract

`com.tactical.neoforge.api.companion.CompanionAdapter`:

| Method | Answers |
| --- | --- |
| `id()` | Stable identity of this kind, used by diagnostics and by tests. |
| `supports(Mob)` | Whether this adapter claims that mob. The first matching adapter in registration order wins. |
| `controllerId(Mob)` | Who controls it **right now**. Called again on every order, so a mob that changes hands stops answering to its previous controller. |
| `canJoin(ServerLevel, Mob, UUID)` | Kind-specific eligibility only (for the wolf: tamed and not ordered to sit). Same level, inside the zone, deployment, ownership and the shared friendly slot budget are checked by the battle and must not be duplicated. |
| `abilities(Mob)` | Which orders it understands, as a `CompanionAbility` mask. **Enforced**: the server reads it live on every order and refuses an order the adapter does not declare, changing no cell, no AP, no guard and no turn. The command panel and the attack-range preview follow the same mask, and the client's own copy is never trusted. `END_TURN` is mandatory in practice: a kind that does not declare it can never hand its own turn over, and admission logs a warning about it. |
| `capturePose` / `beginControl` / `restorePose` | The presentation state control has to change and give back (the wolf's sitting pose). The captured value is opaque to the battle and is never persisted. |

`CompanionAbility` in this version: `MOVE`, `ATTACK`, `SKILL`, `DEFEND`, `END_TURN`. `SKILL` opens the
companion's skill page, which is a deliberate empty state - no companion skill is executed in 0.26.0. Every
declared order is enforced server side; an undeclared one is drawn disabled and refused with a journal line.

## Registration

Register during mod construction, before any battle runs. Registration is a plain factory table: it never
stores an entity, a battle, a turn or a cost, so a leftover entry can never act for a finished encounter.

```java
package com.example;

import com.tactical.neoforge.api.companion.CompanionAbility;
import com.tactical.neoforge.api.companion.CompanionAdapter;
import com.tactical.neoforge.registry.companion.CompanionRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import java.util.UUID;

public final class ExampleCompanions {

    /** One kind: any mob of our own type that our player tamed. */
    private record Adapter(ResourceLocation id) implements CompanionAdapter {
        public boolean supports(Mob mob) { return mob instanceof OurCompanionMob; }
        public UUID controllerId(Mob mob) { return ((OurCompanionMob) mob).ownerId(); }
        public boolean canJoin(ServerLevel level, Mob mob, UUID controllerId) {
            // Kind-specific eligibility only. The battle checks level, zone, deployment, ownership and the
            // shared friendly budget by itself.
            return ((OurCompanionMob) mob).isReadyForBattle(controllerId);
        }
        public int abilities(Mob mob) { return CompanionAbility.all(); }
    }

    public static void doRegister() {
        CompanionRegistry.doRegister(new Adapter(ResourceLocation.fromNamespaceAndPath("example", "companion")));
    }
}
```

Register the specific kind before any broader one: the first adapter that claims a mob wins.

Recruitment itself needs no code. When an encounter starts, when a player joins it, and on the existing
reinforcement pulse, the encounter looks inside its own zone for mobs whose adapter names a controller that
is a member of that same encounter, and admits them. The explicit entry
`BattleParticipation.tryAddCompanion(Mob)` uses the same admission for a companion that arrives later.

## Lifecycle rules the battle already enforces

- A companion has its own turn and its own AP; it acts only on its controller's explicit order. It is never
  played by the mob AI, never inherits the controller's weapon, enchantments or growth.
- Movement uses the existing ground-grid path and per-cell animation, and charges that companion's AP for
  the real number of cells. When the path ends the turn stays open: attacking, moving again or ending the
  turn remains the controller's decision. The player's own drop/leap shortcut is not offered to companions.
- Melee hits the participant the controller picked with the companion's own native attack. A refused order
  charges nothing.
- The guard is the shared no-shield stance: 25 AP, one of four facings, damage multiplier 0.5 in the same
  damage path that already handles players, cleared at that actor's own next turn. It never protects another
  participant.
- A controller that dies, disconnects, leaves or changes dimension takes its companions with it. They are
  never handed to another player and never silently turned back into AI. A companion that already left for
  a lifecycle reason is not recruited again by the same encounter, so it cannot refresh its initial AP.
- A normal exit keeps the companion's real damage, equipment and final legal cell. Only what control took
  over - the AI lock, persistence, the captured pose - is given back. Only a failed admission rolls back a
  position.
- Skins, models, sounds and drops stay the mob's own; the mod never replaces them.
- **Saving and unloading.** Control takes the AI lock and the persistence flag for the duration of a fight.
  While a unit is under control it also carries one small versioned record in its own persistent data - the
  two original flags, plus the session for diagnosis. A clean release removes it. If a unit is saved or a
  chunk unloads mid-fight, the record survives; when that unit is loaded again and no live encounter still
  controls that exact instance, the record restores those two flags and is removed. Nothing else is restored
  by that path: not the cell, not the health, not the equipment, not the owner, and never an AP grant or a
  fresh queue entry. It is not a crash-recovery system for battles.

## Versioning

The companion projection travels in the battle snapshot, which is why the main protocol moved 11 -> 12 in
0.26.0. The skill channel (4) and the journal channel (2) keep their own versions because neither wire
changed. A client and a server of different protocol versions refuse each other instead of reading a body
with a missing block.

## Not promised

No compatibility with a companion mod that does not register an adapter; no support for a non-`Mob`
companion; no companion skill content, growth, inventory or feeding; no cross-dimension recall and no
automatic/manual mode switch. Those are not implemented, and this page does not claim them.
