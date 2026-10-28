package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.BattleManager;
import com.tactical.neoforge.battle.BattleSide;
import com.tactical.neoforge.battle.ServerBattle;
import com.tactical.neoforge.battle.multi.ActionStamp;
import com.tactical.neoforge.client.SkillClientState;
import com.tactical.neoforge.client.SkillOverlay;
import com.tactical.neoforge.skill.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;

/**
 * Independent skill channel, protocol {@value #PROTOCOL}. Every intent resolves the real acting
 * member and re-validates the native runtime; per-recipient state (resources, grants, friendly ids)
 * is generated for each player instead of being broadcast from one owner.
 *
 * <p>The channel keeps its own version on purpose. {@link com.tactical.neoforge.network.SkillStatePacket}
 * gained the membership stamp, which is a wire change on this channel alone, so it is bumped here
 * instead of relying on the main protocol: a peer that speaks skill protocol 2 is refused by name
 * rather than silently misreading the new fields.</p>
 */
@EventBusSubscriber(modid=Tactical.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
public final class SkillNetwork {

    private static final class Entry {
        ServerBattle source;
        UUID session;
        SkillStatePacket last;
        long revision,ticks,lastList=Long.MIN_VALUE,lastPreview=Long.MIN_VALUE,lastCast=Long.MIN_VALUE;
    }

    /** Independent skill channel version: 3 adds the membership stamp to {@code SkillStatePacket}. */
    public static final String PROTOCOL = "3";

    private static final Map<UUID, Entry> STATES = new HashMap<>();
    private static final Map<UUID, Long> FORCED = new HashMap<>();

    private SkillNetwork() { }

    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var r=event.registrar(PROTOCOL);
        r.playToClient(SkillStatePacket.TYPE,SkillStatePacket.STREAM_CODEC,(p,c)->{
            Object arrived = c.connection();
            c.enqueueWork(()->com.tactical.neoforge.client.ClientPayloadGate.apply(arrived,()->SkillClientState.accept(p)));
        });
        r.playToClient(SkillPreviewResponsePacket.TYPE,SkillPreviewResponsePacket.STREAM_CODEC,(p,c)->{
            Object arrived = c.connection();
            c.enqueueWork(()->com.tactical.neoforge.client.ClientPayloadGate.apply(arrived,()->SkillOverlay.accept(p)));
        });
        r.playToServer(SkillListRequestPacket.TYPE,SkillListRequestPacket.STREAM_CODEC,(p,c)->c.enqueueWork(()->{
            if(c.player() instanceof ServerPlayer player) {
                ServerBattle battle=member(player);if(battle==null||!battle.session().accepts(p.stamp()))return;
                Entry entry=entry(player);
                long now=player.level().getGameTime();if(entry.lastList==now)return;entry.lastList=now;
                sync(battle,player,true,true);
            }
        }));
        r.playToServer(SkillCastPacket.TYPE,SkillCastPacket.STREAM_CODEC,(p,c)->c.enqueueWork(()->{
            if(c.player() instanceof ServerPlayer player)cast(player,p);
        }));
        r.playToServer(SkillPreviewRequestPacket.TYPE,SkillPreviewRequestPacket.STREAM_CODEC,(p,c)->c.enqueueWork(()->{
            if(c.player() instanceof ServerPlayer player)preview(player,p);
        }));
    }

    private static Entry entry(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), ignored -> new Entry());
    }

    /** Real connection identity -> the encounter that actually contains it. */
    private static ServerBattle member(ServerPlayer player) {
        ServerBattle battle=BattleManager.findFor(player.getUUID());
        return battle!=null&&!battle.finished()&&battle.isMember(player)&&player.isAlive()?battle:null;
    }

    private static boolean supported(ServerPlayer player,CustomPacketPayload.Type<?> type) {
        // Transport/capability only; legality is decided by the session/turn checks below.
        return com.tactical.neoforge.network.ServerPackets.supports(player,type);
    }

    private static void send(ServerPlayer player,CustomPacketPayload packet) {
        // Headless fixtures and older peers may negotiate only the original battle channels.
        com.tactical.neoforge.network.ServerPackets.send(player,packet);
    }

    private static boolean context(ServerBattle battle,ServerPlayer player,Entry entry,
                                   UUID wanted,long epoch,ResourceLocation dimension) {
        return battle!=null&&entry.source==battle&&battle.sessionId().equals(wanted)
                &&battle.session().accepts(new ActionStamp(wanted,epoch))
                &&entry.last!=null&&entry.last.active()
                &&entry.last.dimension().equals(dimension)&&player.level().dimension().location().equals(dimension)
                &&battle.isActorTurn(player)&&!battle.isBusy();
    }

    private static void cast(ServerPlayer player,SkillCastPacket packet) {
        ServerBattle battle=member(player);if(battle==null)return;
        Entry entry=entry(player);
        sync(battle,player,true,false);
        if(!context(battle,player,entry,packet.session(),packet.turnEpoch(),packet.dimension()))return;
        long now=player.level().getGameTime();if(entry.lastCast==now)return;entry.lastCast=now;
        // Source grants, target relation, cooldown, actor turn, AP, MP, geometry and effects are all native checks.
        TacticalSkills.cast(battle,player,packet.skill(),packet.target());
        sync(battle,player,!battle.finished(),true);
    }

    private static void preview(ServerPlayer player,SkillPreviewRequestPacket packet) {
        ServerBattle battle=member(player);if(battle==null)return;
        Entry entry=entry(player);
        long now=player.level().getGameTime();
        if(entry.source==battle&&entry.lastPreview==now)return;
        sync(battle,player,true,false);
        if(!context(battle,player,entry,packet.session(),packet.turnEpoch(),packet.dimension()))return;
        entry.lastPreview=now;
        SkillPreview preview=TacticalSkills.preview(battle,player,packet.skill(),packet.target());
        send(player,new SkillPreviewResponsePacket(entry.session,packet.dimension(),packet.requestId(),entry.revision,now,
                packet.skill(),packet.target(),preview.valid(),text(preview.reason(),512),preview.apCost(),preview.mpCost(),preview.tiles(),preview.affected()));
    }

    private static boolean targetCosts(SkillDefinition d) {
        Set<String> names=new HashSet<>(d.apCost().variables());names.addAll(d.mpCost().variables());
        for(String name:names)if(name.startsWith("target_")||Set.of("distance","height_difference","direction_multiplier").contains(name)
                ||d.attributes().containsKey(name)&&d.attributes().get(name).target())return true;
        return false;
    }

    /** Per-recipient skill state: this player's AP/MP/cooldowns/grants, never another member's. */
    private static void sync(ServerBattle battle,ServerPlayer player,boolean active,boolean force) {
        if(battle==null||player==null)return;
        Entry entry=entry(player);
        if(!supported(player,SkillStatePacket.TYPE))return;
        ResourceLocation dimension=player.level().dimension().location();
        if(entry.source!=battle||entry.last!=null&&!entry.last.dimension().equals(dimension)) {
            entry.source=battle;entry.session=battle.sessionId();entry.revision=0;entry.last=null;
            entry.lastList=entry.lastPreview=entry.lastCast=Long.MIN_VALUE;
        }
        List<SkillView> views=new ArrayList<>();
        if(active)for(SkillAvailability a:TacticalSkills.available(battle,player)) {
            SkillDefinition d=SkillRegistry.get(a.id());if(d==null)continue;
            if(views.size()==256)break;
            views.add(new SkillView(d.id(),d.name(),d.description(),a.level(),d.maxLevel(),d.aim(),d.area(),targetCosts(d),
                    a.ready(),text(a.reason(),512),a.apCost(),a.mpCost(),a.cooldown()));
        }
        // Identity-bearing relation: the future faction policy needs both actor UUIDs.
        List<Integer> friendly=active?battle.participants().stream()
                .filter(a->battle.allied(player,a))
                .map(a->a.getId()).limit(4096).toList():List.of();
        double mp=ManaResources.current(player),maximum=ManaResources.maximum(player),recovery=ManaResources.recovery(player);
        boolean changed=entry.last==null||entry.last.active()!=active||entry.last.mp()!=mp||entry.last.maximum()!=maximum
                ||entry.last.recovery()!=recovery||!entry.last.skills().equals(views)||!entry.last.friendlyIds().equals(friendly);
        if(changed)entry.last=new SkillStatePacket(entry.session,dimension,active,++entry.revision,mp,maximum,recovery,views,friendly);
        if(changed||force)send(player,entry.last);
    }

    /**
     * Force one member's skill state after its membership transition.
     *
     * <p>Skill state is display-only, so a packet that arrives before the transition is dropped by the
     * client; the transition therefore has to be followed by a forced resend instead of waiting for the
     * next unchanged-since-last-tick comparison.</p>
     */
    public static void forceSync(ServerBattle battle,ServerPlayer player) {
        sync(battle,player,true,true);
        if (player != null) FORCED.merge(player.getUUID(), 1L, Long::sum);
    }

    /** Fixture-only readback: how many forced skill publications this player was owed. */
    public static long forcedPublications(UUID playerId) {
        return playerId == null ? 0L : FORCED.getOrDefault(playerId, 0L);
    }

    private static String text(String text,int limit) {return text==null?"":text.substring(0,Math.min(text.length(),limit));}

    @EventBusSubscriber(modid=Tactical.MOD_ID)
    public static final class Lifecycle {
        private Lifecycle() { }
        @SubscribeEvent public static void changed(SkillStateChangedEvent event){
            ServerBattle battle=event.battle();
            if(battle==null)return;
            for(ServerPlayer player:battle.memberPlayers())sync(battle,player,event.active(),true);
        }
        @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
            for(ServerBattle battle:BattleManager.sessions().all()) {
                if(battle.finished())continue;
                for(ServerPlayer player:battle.memberPlayers()) {
                    Entry entry=STATES.get(player.getUUID());
                    if(entry==null||++entry.ticks%5!=0)continue;
                    sync(battle,player,true,false);
                }
            }
        }
        @SubscribeEvent public static void stop(ServerStoppedEvent event) {
            STATES.clear();
            FORCED.clear();
        }
    }
}
