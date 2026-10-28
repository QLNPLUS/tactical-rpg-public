package com.tactical.neoforge.skill;

import com.tactical.neoforge.battle.ServerBattle;
import net.neoforged.bus.api.Event;

/** A resource/skill lifecycle signal for network/UI integrations, without coupling mechanics to a screen. */
public final class SkillStateChangedEvent extends Event {
    private final ServerBattle battle;private final boolean active;
    public SkillStateChangedEvent(ServerBattle battle,boolean active){this.battle=battle;this.active=active;}
    public ServerBattle battle(){return battle;}public boolean active(){return active;}
}
