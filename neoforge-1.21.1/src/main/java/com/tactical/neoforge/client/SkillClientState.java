package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.SkillListRequestPacket;
import com.tactical.neoforge.network.SkillSnapshot;
import com.tactical.neoforge.network.SkillStatePacket;
import com.tactical.neoforge.network.SkillView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/**
 * Server-owned skill grants/resources. No client NBT parsing, formula execution, spending or cooldown math.
 *
 * <p>The state arrives as pages of one complete publication, so this class keeps the previous complete
 * view - its list, its AP/MP and the user's selection - until every page of the new publication is in
 * hand, and then replaces all of it in one step. A page never edits the visible list. Nothing here
 * resolves a component: the texts are stored as they arrived and every display point resolves them in the
 * client's own language.</p>
 */
@EventBusSubscriber(modid=Tactical.MOD_ID,value=Dist.CLIENT)
public final class SkillClientState {
    /** One bounded re-request per second at most while a publication is missing pages. */
    private static final int REFETCH_INTERVAL = 20;
    private static final SkillPageAssembler ASSEMBLER = new SkillPageAssembler();
    private static ClientLevel level;
    private static UUID session;
    private static SkillSnapshot state;
    private static long revision = -1L;
    private static long publication = -1L;
    private static int pageCount;
    private static ResourceLocation selected;
    private static long version,lastRequest=Long.MIN_VALUE;
    private static boolean refetch;
    private SkillClientState() { }

    /**
     * One page of the skill state.
     *
     * <p>The membership and world gates run first, so a page of another encounter or level cannot even
     * enter the pending publication. A page of an older publication, a duplicate page or a page whose
     * header belongs to another snapshot is refused by {@link SkillPageAssembler}; only a completed
     * publication publishes anything.</p>
     */
    public static void accept(SkillStatePacket page) {
        // Only the membership this client currently shows may drive its skill state.
        if(!BattleMembership.matches(page.membershipServerEpoch(),page.membershipGeneration(),page.session()))return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||!page.dimension().equals(mc.level.dimension().location()))return;
        // A world change or another encounter starts from nothing. A known previous level is compared, and
        // an unknown one is not treated as a change: the pages of one publication must stay together, so a
        // first page may never wipe the batch its own later pages belong to.
        if(level!=null&&level!=mc.level)clear();
        else if(session!=null&&!session.equals(page.session()))clear();
        SkillPageAssembler.Key key=new SkillPageAssembler.Key(page.session(),page.dimension(),
                page.membershipServerEpoch(),page.membershipGeneration());
        SkillPageAssembler.Outcome outcome=ASSEMBLER.accept(page,key,mc.level.getGameTime());
        if(outcome==SkillPageAssembler.Outcome.CONFLICT) {
            // Two snapshots were interleaved, so this batch is dropped; the old view stays on screen and the
            // bounded request path below asks for one fresh publication instead of leaving it there.
            refetch=true;
            return;
        }
        if(outcome!=SkillPageAssembler.Outcome.COMPLETED)return;
        SkillPageAssembler.Complete complete=ASSEMBLER.take();
        if(complete!=null)publish(complete);
    }

    /** The one atomic update: the whole complete state, or nothing at all. */
    private static void publish(SkillPageAssembler.Complete complete) {
        SkillSnapshot snapshot=complete.snapshot();
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||!complete.dimension().equals(mc.level.dimension().location()))return;
        // The membership and world gates already ran for every page of this publication, on this thread.
        if(!snapshot.active()) {
            // The encounter ended: the view is cleared, but the assembler keeps this context's watermark so a
            // delayed page of the snapshot just replaced cannot bring the old list back during a transition.
            clearView();
            return;
        }
        // A legitimate empty list is published like any other state: zero skills still has MP, recovery and
        // allied identity, and it is exactly how "the last skill was removed" clears the page.
        level=mc.level;session=complete.session();state=snapshot;revision=complete.revision();
        pageCount=complete.pageCount();publication=complete.publication();version++;
        BattleClientState.mp=snapshot.mp();BattleClientState.maxMp=snapshot.maximum();BattleClientState.mpRecovery=snapshot.recovery();
        BattleClientState.dirty=true;
        SkillOverlay.clearPreview();
        if(selected!=null&&view(selected)==null) {
            cancelSelection();
            if(BattleClientState.isPlayerTurn()&&BattleAim.isOpen())SkillMenu.open();
        }
        BattleAim.refreshLabels();
    }

    public static List<SkillView> skills(){return state==null?List.of():state.skills();}
    public static SkillView view(ResourceLocation id){return id==null?null:skills().stream().filter(v->v.id().equals(id)).findFirst().orElse(null);}
    public static SkillView selected(){return view(selected);}
    public static ResourceLocation selectedId(){return selected;}
    public static void castConfirmed() { selected=null; SkillOverlay.clearPreview(); BattleClientState.mode=BattleMode.NONE; }
    public static UUID session(){return session;}
    public static long revision(){return state==null?-1L:revision;}
    public static long version(){return version;}
    /** Read-only diagnostics: how many pages the visible state needed, and its publication number. */
    public static int pageCount(){return state==null?0:pageCount;}
    public static long publication(){return state==null?-1L:publication;}
    public static boolean friendly(int entityId){return state!=null&&state.friendlyIds().contains(entityId);}
    public static void select(ResourceLocation id) {
        SkillView view=view(id);
        if(view==null||!view.ready()||!BattleClientState.isPlayerTurn()||BattleClientState.moving)return;
        selected=id;SkillOverlay.clearPreview();BattleClientState.mode=BattleMode.SKILL;BattleAim.open();
    }
    public static void cancelSelection() {
        selected=null;SkillOverlay.clearPreview();
        if(BattleClientState.mode==BattleMode.SKILL)BattleClientState.mode=BattleMode.NONE;
    }
    public static void request() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.getConnection()==null||!mc.getConnection().hasChannel(SkillListRequestPacket.TYPE))return;
        long now=mc.level.getGameTime();if(lastRequest==now)return;
        var stamp=BattleClientState.stamp();if(stamp==null)return;
        lastRequest=now;PacketDistributor.sendToServer(new SkillListRequestPacket(stamp));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc=Minecraft.getInstance();
        // The first batch of a session has no visible state yet, so "there is nothing to clear" cannot be
        // decided from the view alone: a pending publication is part of what has to be dropped here.
        if(!BattleClientState.active||mc.level==null||mc.player==null||!mc.player.isAlive()
                ||(level!=null&&level!=mc.level)||pendingInAnotherWorld(mc)) {
            if(state!=null||selected!=null||session!=null||ASSEMBLER.pending())clear();return;
        }
        long now=mc.level.getGameTime();
        // A publication that never completed is abandoned; the existing bounded request path refetches it.
        if(ASSEMBLER.clearExpired(now))refetch=true;
        // Never ask for a new publication while one is still being assembled: that would replace the very
        // batch the next page belongs to.
        if(!ASSEMBLER.pending()&&(state==null||refetch)
                &&(lastRequest==Long.MIN_VALUE||now<lastRequest||now-lastRequest>=REFETCH_INTERVAL)) {
            refetch=false;
            request();
        }
        SkillMenu.tick();
    }
    private static boolean pendingInAnotherWorld(Minecraft mc) {
        SkillPageAssembler.Key context=ASSEMBLER.context();
        return ASSEMBLER.pending()&&context!=null&&mc.level!=null
                &&!context.dimension().equals(mc.level.dimension().location());
    }
    /** The view only: the assembler keeps this context's watermark against delayed replays. */
    private static void clearView() {
        level=null;session=null;state=null;revision=-1L;publication=-1L;pageCount=0;selected=null;version++;
        BattleClientState.mp=BattleClientState.maxMp=BattleClientState.mpRecovery=0;
        BattleClientState.dirty=true;
        if(BattleClientState.mode==BattleMode.SKILL)BattleClientState.mode=BattleMode.NONE;
        SkillOverlay.clearPreview();
    }
    /** Lifecycle exit: the view and the whole assembler context are gone. */
    public static void clear() {
        clearView();
        lastRequest=Long.MIN_VALUE;
        refetch=false;
        ASSEMBLER.reset();
    }
    public static String number(double value) {
        return value==Math.rint(value)?Long.toString((long)value):String.format(Locale.ROOT,"%.1f",value);
    }
    public static String mpText(){return number(BattleClientState.mp)+" / "+number(BattleClientState.maxMp);}
    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn event){clear();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){clear();}
    @SubscribeEvent public static void unload(LevelEvent.Unload event){
        if(event.getLevel()==level) {clear();return;}
        // A pending first batch has no visible level yet; it still belongs to the world being unloaded.
        SkillPageAssembler.Key context=ASSEMBLER.context();
        if(ASSEMBLER.pending()&&context!=null&&event.getLevel() instanceof net.minecraft.world.level.Level unloading)
            if(context.dimension().equals(unloading.dimension().location()))clear();
    }
}
