package com.tactical.neoforge.skill;

import net.minecraft.network.chat.Component;

/**
 * Server-generated aim feedback. Tiles are XYZ triples; native targets are deduplicated entity IDs.
 *
 * <p>{@code reason} is display structure, not a finished sentence: the preview is answered for one
 * member, but the sentence is still resolved on that member's client.</p>
 */
public record SkillPreview(boolean valid,Component reason,int apCost,double mpCost,int[] tiles,int[] affected) {
    public SkillPreview {tiles=tiles.clone();affected=affected.clone();if(reason==null)reason=SkillText.none();}
    public static SkillPreview rejected(Component reason) {return new SkillPreview(false,reason,0,0,new int[0],new int[0]);}
    /** Compatibility form: the reason is finished text, carried as a literal and never as a key. */
    public static SkillPreview rejected(String reason) {return rejected(SkillText.literalText(reason==null?"":reason));}
}
