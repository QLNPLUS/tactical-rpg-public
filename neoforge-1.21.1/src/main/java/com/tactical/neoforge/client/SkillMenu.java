package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.parser.HTML;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.SkillView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Owned skills remain visible while unavailable; updates preserve hovered rows and listeners. */
public final class SkillMenu {
    public static final String PAGE="tactical/screens/battle_skills.html";
    private static boolean registered;
    private static Document document;
    private static long generation=-1,version=-1;
    private static List<ResourceLocation> ids=List.of();
    private SkillMenu() { }
    public static void open() {
        Minecraft mc=Minecraft.getInstance();
        if(!BattleClientState.isPlayerTurn()||BattleClientState.moving||mc.screen!=null&&!(mc.screen instanceof TacticalScreen))return;
        if(!registerTemplate())return;
        SkillClientState.cancelSelection();
        var screen=new SkillsScreen();mc.setScreen(screen);screen.setPauseGame(false).setShowDefaultBackground(false);
        document=null;generation=version=-1;ids=List.of();SkillClientState.request();tick();
    }
    public static void back() {
        Minecraft mc=Minecraft.getInstance();SkillClientState.cancelSelection();mc.setScreen(null);BattleMenu.open();
    }
    private static boolean registerTemplate() {
        if(registered)return true;
        try(var stream=SkillMenu.class.getResourceAsStream("/assets/apricityui/apricity/"+PAGE)) {
            if(stream==null){Tactical.LOGGER.error("[Tactical] missing skill menu template");return false;}
            HTML.putTemple(PAGE,new String(stream.readAllBytes(),StandardCharsets.UTF_8));registered=true;return true;
        }catch(IOException failure){Tactical.LOGGER.error("[Tactical] cannot load skill menu",failure);return false;}
    }
    public static void tick() {
        var mc=Minecraft.getInstance();
        if(!(mc.screen instanceof SkillsScreen screen)||screen.getLinkedDocument()==null)return;
        Document next=screen.getLinkedDocument();
        boolean rebuilt=document!=next||generation!=next.getRefreshGeneration();
        if(rebuilt) {
            document=next;generation=document.getRefreshGeneration();version=-1;ids=List.of();
            Element back=document.getElementById("skill-back");
            if(back!=null)back.addEventListener("click",ignored->Minecraft.getInstance().tell(SkillMenu::back));
        }
        if(version==SkillClientState.version()&&!rebuilt)return;
        version=SkillClientState.version();
        List<SkillView> views=SkillClientState.skills();List<ResourceLocation> nextIds=views.stream().map(SkillView::id).toList();
        Element list=document.getElementById("skill-list");if(list==null)return;
        if(!nextIds.equals(ids)||rebuilt) {
            ids=nextIds;StringBuilder html=new StringBuilder();
            for(int i=0;i<ids.size();i++)html.append("<button class=\"option skill-row\" id=\"skill-row-").append(i)
                    .append("\"><span class=\"opt-body\"><span class=\"opt-title\" id=\"skill-name-").append(i)
                    .append("\"></span><span class=\"opt-desc\" id=\"skill-desc-").append(i)
                    .append("\"></span><span class=\"opt-desc\" id=\"skill-cost-").append(i)
                    .append("\"></span><span class=\"opt-desc skill-reason\" id=\"skill-reason-").append(i).append("\"></span></span></button>");
            list.setInnerHTML(html.toString());
            for(int i=0;i<ids.size();i++) {
                ResourceLocation id=ids.get(i);Element row=document.getElementById("skill-row-"+i);
                if(row!=null)row.addEventListener("click",ignored->Minecraft.getInstance().tell(()->SkillClientState.select(id)));
            }
            UiZoom.applyAll();
        }
        set("skill-mp","MP "+SkillClientState.mpText()+" · 自己回合恢复 +"+SkillClientState.number(BattleClientState.mpRecovery));
        set("skill-empty",views.isEmpty()?(SkillClientState.session()==null?"正在同步技能…":"当前角色与装备未提供技能"):"选择可用技能后，在战场确认目标");
        for(int i=0;i<views.size();i++) {
            SkillView view=views.get(i);set("skill-name-"+i,view.name()+" · Lv."+view.level()+" / "+view.maxLevel());
            set("skill-desc-"+i,view.description());
            set("skill-cost-"+i,(view.targetCosts()?"AP / MP：选定目标后确定":view.apCost()+" AP · "+SkillClientState.number(view.mpCost())+" MP")
                    +" · 冷却剩余 "+view.cooldown()+" 回合");
            set("skill-reason-"+i,view.ready()?switch(view.aim()) {
                case SELF->"自身目标 · 选择后左键确认";case ENTITY->"实体目标 · 指向参战单位";case GROUND->"地面目标 · 指向地面或单位脚下";
            }:view.reason());
            Element row=document.getElementById("skill-row-"+i);String classes="option skill-row"+(view.ready()?"":" skill-disabled");
            if(row!=null&&!classes.equals(row.getClassName()))row.setClassName(classes);
        }
    }
    private static void set(String id,String text) {
        Element element=document==null?null:document.getElementById(id);
        if(element!=null&&!text.equals(element.getTextContent()))element.setTextContent(text);
    }
    private static final class SkillsScreen extends TacticalScreen {private SkillsScreen(){super(PAGE,SkillMenu::back);}}
}
