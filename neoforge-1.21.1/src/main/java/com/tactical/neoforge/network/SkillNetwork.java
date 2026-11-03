package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.BattleManager;
import com.tactical.neoforge.battle.ServerBattle;
import com.tactical.neoforge.battle.multi.ActionStamp;
import com.tactical.neoforge.client.SkillClientState;
import com.tactical.neoforge.client.SkillOverlay;
import com.tactical.neoforge.skill.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;

/**
 * Independent skill channel, protocol {@value #PROTOCOL}. Every intent resolves the real acting
 * member and re-validates the native runtime; per-recipient state (resources, grants, friendly ids)
 * is generated for each player instead of being broadcast from one owner.
 *
 * <p>The channel keeps its own version on purpose. Protocol 4 published the skill state as <em>pages</em>
 * of one complete snapshot and moved name/description/reason to bounded component frames, so a peer that
 * speaks skill protocol 3 is refused by name rather than silently misreading the layout.</p>
 */
@EventBusSubscriber(modid=Tactical.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
public final class SkillNetwork {

    private static final class Entry {
        ServerBattle source;
        UUID session;
        ResourceLocation dimension;
        /** The complete state last published to this recipient, used as the change baseline. */
        SkillSnapshot last;
        List<SkillStatePacket> pages = List.of();
        long revision,publication,ticks,lastList=Long.MIN_VALUE,lastPreview=Long.MIN_VALUE,lastCast=Long.MIN_VALUE;
        boolean stateRefused,textRefused;
    }

    /**
     * Independent skill channel version: 4 publishes a paged complete state whose texts are components.
     */
    public static final String PROTOCOL = "4";

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
                &&dimension.equals(entry.dimension)&&player.level().dimension().location().equals(dimension)
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
        // A preview can be requested several times a second, so its reason is verified once here and the
        // refusal of an over-long text is reported once per recipient rather than on every request.
        Component reason=preview.valid()?preview.reason()
                :verified(preview.reason(),player.level().registryAccess(),"preview reason",packet.skill(),entry);
        send(player,new SkillPreviewResponsePacket(entry.session,packet.dimension(),packet.requestId(),entry.revision,now,
                packet.skill(),packet.target(),preview.valid(),reason,preview.apCost(),preview.mpCost(),preview.tiles(),preview.affected()));
    }

    private static boolean targetCosts(SkillDefinition d) {
        Set<String> names=new HashSet<>(d.apCost().variables());names.addAll(d.mpCost().variables());
        for(String name:names)if(name.startsWith("target_")||Set.of("distance","height_difference","direction_multiplier").contains(name)
                ||d.attributes().containsKey(name)&&d.attributes().get(name).target())return true;
        return false;
    }

    /**
     * Per-recipient skill state: this player's AP/MP/cooldowns/grants, never another member's.
     *
     * <p>The complete state is built first ({@link SkillSnapshot}) and only then sliced into bounded pages,
     * so a client never sees a partially updated list: every page of one publication carries the same
     * header and the client publishes the assembled snapshot in one step.</p>
     */
    private static void sync(ServerBattle battle,ServerPlayer player,boolean active,boolean force) {
        if(battle==null||player==null)return;
        Entry entry=entry(player);
        if(!supported(player,SkillStatePacket.TYPE))return;
        ResourceLocation dimension=player.level().dimension().location();
        if(entry.source!=battle||entry.last!=null&&!dimension.equals(entry.dimension)) {
            entry.source=battle;entry.session=battle.sessionId();entry.revision=0;entry.publication=0;entry.last=null;
            entry.pages=List.of();entry.dimension=dimension;entry.stateRefused=false;entry.textRefused=false;
            entry.lastList=entry.lastPreview=entry.lastCast=Long.MIN_VALUE;
        }
        RegistryAccess registries=player.level().registryAccess();
        List<SkillView> views=new ArrayList<>();
        if(active)for(SkillAvailability a:TacticalSkills.available(battle,player)) {
            SkillDefinition d=SkillRegistry.get(a.id());if(d==null)continue;
            if(views.size()>=SkillPacketIO.MAX_SKILLS)break;
            views.add(new SkillView(d.id(),
                    verified(d.displayName(),registries,"name",d.id(),entry),
                    verified(d.displayDescription(),registries,"description",d.id(),entry),
                    a.level(),d.maxLevel(),d.aim(),d.area(),targetCosts(d),a.ready(),
                    verified(a.reason(),registries,"reason",d.id(),entry),
                    a.apCost(),a.mpCost(),a.cooldown()));
        }
        // Identity-bearing relation: the future faction policy needs both actor UUIDs.
        List<Integer> friendly=active?battle.participants().stream()
                .filter(a->battle.allied(player,a))
                .map(a->a.getId()).limit(SkillSnapshot.MAX_FRIENDLY_IDS).toList():List.of();
        double mp=ManaResources.current(player),maximum=ManaResources.maximum(player),recovery=ManaResources.recovery(player);
        SkillSnapshot snapshot;
        try { snapshot=new SkillSnapshot(active,mp,maximum,recovery,views,friendly); }
        catch(IllegalArgumentException refused) { reportRefused(entry,refused); return; }
        boolean changed=!snapshot.equals(entry.last);
        if(!changed&&!force)return;
        long revision=changed?entry.revision+1:entry.revision;
        long publication=entry.publication+1;
        List<SkillStatePacket> pages;
        try { pages=SkillStatePacket.pack(entry.session,dimension,snapshot,revision,publication,registries); }
        catch(IllegalArgumentException refused) { reportRefused(entry,refused); return; }
        entry.last=snapshot;entry.revision=revision;entry.publication=publication;entry.pages=pages;
        if(pages.size()>1) {
            // One bounded line per multi-page publication: a driver or a log reader can tell that the real
            // send was paged, instead of inferring it from a page count computed in a test.
            Tactical.LOGGER.info("[Tactical] skill state for {} published in {} pages ({} skills, revision {}, publication {})",
                    player.getGameProfile().getName(),pages.size(),snapshot.skillCount(),revision,publication);
        }
        for(SkillStatePacket page:pages)send(player,page);
    }

    /**
     * One skill text that does not fit a component frame is refused as a <em>field</em>: it becomes a stable
     * short localised line, while the skill's identity, level, readiness and costs are untouched.
     */
    private static Component verified(Component text,RegistryAccess registries,String field,ResourceLocation id,Entry entry) {
        Component value=SkillPacketIO.verified(text,registries);
        if(value!=null)return value;
        if(!entry.textRefused) {
            entry.textRefused=true;
            Tactical.LOGGER.warn("[Tactical] skill {} {} text does not fit one component frame; that field is "
                    +"replaced by a stable short line and the skill itself is kept",id,field);
        }
        Component fallback=SkillPacketIO.verified(SkillPacketIO.tooLong(),registries);
        return fallback!=null?fallback:Component.literal("?");
    }

    /** Bounded: one line per recipient and encounter, never one per sync attempt. */
    private static void reportRefused(Entry entry,RuntimeException refused) {
        if(entry.stateRefused)return;
        entry.stateRefused=true;
        Tactical.LOGGER.error("[Tactical] skill state was not published ({}); the previous state stays in place "
                +"and the unchanged case keeps its old publication",refused.toString());
    }

    /**
     * Force one member's skill state after its membership transition.
     *
     * <p>Skill state is display-only, so a page that arrives before the transition is dropped by the
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
