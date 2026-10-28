package com.tactical.neoforge.skill;

/** Server-generated aim feedback. Tiles are XYZ triples; native targets are deduplicated entity IDs. */
public record SkillPreview(boolean valid,String reason,int apCost,double mpCost,int[] tiles,int[] affected) {
    public SkillPreview {tiles=tiles.clone();affected=affected.clone();}
    public static SkillPreview rejected(String reason) {return new SkillPreview(false,reason,0,0,new int[0],new int[0]);}
}
