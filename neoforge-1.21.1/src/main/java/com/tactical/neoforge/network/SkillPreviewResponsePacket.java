package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.skill.SkillTarget;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/**
 * Native planned area and affected participants; the client never evaluates skill formulas.
 *
 * <p>{@code reason} is display structure - a translation key plus typed arguments, or the literal text a
 * custom condition itself returned - so the member who asked reads the refusal in that member's own
 * language. It travels in a bounded component frame from {@link ComponentIO}, not as a finished sentence
 * in a 512-character field.</p>
 */
public record SkillPreviewResponsePacket(UUID session,ResourceLocation dimension,int requestId,long revision,long serverTick,
        ResourceLocation skill,SkillTarget target,boolean valid,Component reason,int apCost,double mpCost,int[] tiles,int[] affected) implements CustomPacketPayload {
    public static final Type<SkillPreviewResponsePacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"skill_preview_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf,SkillPreviewResponsePacket> STREAM_CODEC=
            StreamCodec.of(SkillPreviewResponsePacket::encode,SkillPreviewResponsePacket::decode);
    public SkillPreviewResponsePacket {
        tiles=tiles.clone();affected=affected.clone();SkillPacketIO.finiteResource(mpCost);
        if(session==null||dimension==null||skill==null||target==null||reason==null||apCost<0
                ||tiles.length%3!=0||tiles.length>4096*3||affected.length>4096)throw new IllegalArgumentException("Invalid skill preview");
    }
    /** Compatibility form: the reason is finished text, carried as a literal and never as a key. */
    public SkillPreviewResponsePacket(UUID session,ResourceLocation dimension,int requestId,long revision,long serverTick,
            ResourceLocation skill,SkillTarget target,boolean valid,String reason,int apCost,double mpCost,int[] tiles,int[] affected) {
        this(session,dimension,requestId,revision,serverTick,skill,target,valid,
                Component.literal(reason==null?"":reason),apCost,mpCost,tiles,affected);
    }
    @Override public int[] tiles(){return tiles.clone();}
    @Override public int[] affected(){return affected.clone();}
    private static void encode(RegistryFriendlyByteBuf b,SkillPreviewResponsePacket p) {
        b.writeUUID(p.session);b.writeResourceLocation(p.dimension);b.writeVarInt(p.requestId);b.writeLong(p.revision);b.writeLong(p.serverTick);
        b.writeResourceLocation(p.skill);SkillPacketIO.target(b,p.target);b.writeBoolean(p.valid);ComponentIO.write(b,p.reason);
        b.writeVarInt(p.apCost);b.writeDouble(p.mpCost);
        b.writeVarInt(p.tiles.length/3);for(int coordinate:p.tiles)b.writeVarInt(coordinate);
        b.writeVarInt(p.affected.length);for(int id:p.affected)b.writeVarInt(id);
    }
    private static SkillPreviewResponsePacket decode(RegistryFriendlyByteBuf b) {
        UUID session=b.readUUID();ResourceLocation dimension=b.readResourceLocation();int request=b.readVarInt();long revision=b.readLong(),tick=b.readLong();
        ResourceLocation skill=b.readResourceLocation();SkillTarget target=SkillPacketIO.target(b);boolean valid=b.readBoolean();Component reason=ComponentIO.read(b);
        int ap=b.readVarInt();double mp=b.readDouble();int size=SkillPacketIO.size(b,4096);int[] tiles=new int[size*3];
        for(int i=0;i<tiles.length;i++)tiles[i]=b.readVarInt();int count=SkillPacketIO.size(b,4096);int[] affected=new int[count];
        for(int i=0;i<count;i++)affected[i]=b.readVarInt();
        return new SkillPreviewResponsePacket(session,dimension,request,revision,tick,skill,target,valid,reason,ap,mp,tiles,affected);
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
