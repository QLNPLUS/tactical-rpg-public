package com.tactical.neoforge.battle;

import com.tactical.core.combat.ProjectileAim;
import com.tactical.core.combat.RangedWeapon;
import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Facing-independent, deterministic central-shot planning against actual world collision. */
public final class BallisticShot {
    public static final int MAX_TICKS = RangedWeapon.MAX_FLIGHT_TICKS;
    /** Vanilla arrow drag assumptions, kept for callers that state raw parameters instead of a weapon. */
    private static final double ARROW_DRAG = 0.99D;
    private static final double ARROW_WATER_DRAG = 0.6D;
    public record Plan(float yaw, float pitch, float speed, boolean gravity, Vec3 velocity) { }
    private BallisticShot() { }

    /**
     * Plan one shot with the real configuration of the weapon that will fire it.
     *
     * <p>Speed, gravity and drag come from the weapon, so a crossbow cannot be validated with bow
     * numbers and then fly differently. The plan is the same {@link Plan} the capture applies to the
     * first real projectile, which is what keeps "planned" and "actually happened" one computation.</p>
     *
     * <p>Piercing is a parameter and not a constant: the real arrow carries the weapon's piercing
     * enchantment, so a legal line through one allowed entity has to be planned with the same count,
     * otherwise the planner rejects a shot the arrow would really take. There is deliberately no
     * variant that silently assumes zero.</p>
     */
    public static Plan plan(Level level, LivingEntity shooter, GridPos from, LivingEntity target,
                            RangedWeapon weapon, int pierce) {
        return plan(level, shooter, from, target, weapon.speed(), weapon.gravity(), pierce,
                weapon.drag(), weapon.waterDrag());
    }

    /** Legacy raw-parameter form; the numbers are the vanilla arrow ones. */
    public static Plan plan(Level level, LivingEntity shooter, GridPos from, LivingEntity target,
                            float speed, boolean gravity, int pierce) {
        return plan(level, shooter, from, target, speed, gravity, pierce, ARROW_DRAG, ARROW_WATER_DRAG);
    }

    /**
     * Plan one throw with the flight of the thrown entity that will carry it.
     *
     * <p>A splash potion is not an arrow: it leaves slower, keeps more of its speed in water and is not a
     * piercing projectile. This overload exists so the thrower's own ballistics are neither the bow's nor the
     * crossbow's, and the same {@link Plan} is what the real entity is given.</p>
     */
    public static Plan plan(Level level, LivingEntity shooter, GridPos from, LivingEntity target,
                            com.tactical.core.combat.ThrowProfile profile, int pierce) {
        return plan(level, shooter, from, target, profile.speed(), profile.gravity(), pierce,
                profile.drag(), profile.waterDrag());
    }

    private static Plan plan(Level level, LivingEntity shooter, GridPos from, LivingEntity target,
                             float speed, boolean gravity, int pierce, double drag, double waterDrag) {
        Vec3 origin = new Vec3(from.x()+.5, from.y()+shooter.getEyeHeight()-.1, from.z()+.5);
        Vec3 middle = target.getBoundingBox().getCenter();
        double dx=middle.x-origin.x,dz=middle.z-origin.z,horizontal=Math.hypot(dx,dz);
        float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
        List<Float> pitches=new ArrayList<>();
        for(double fraction:new double[]{.5,.72,.28}) {
            double vertical=target.getY(fraction)-origin.y;
            pitches.add(gravity?ProjectileAim.pitch(horizontal,vertical,speed):(float)-Math.toDegrees(Math.atan2(vertical,horizontal)));
        }
        // A low arc may hit cover while another physically valid arc still reaches the target.
        // Search by least change from the direct aim, bounded by the same flight lifetime as real shots.
        if(gravity) {
            float preferred=pitches.get(0);
            List<Float> alternatives=new ArrayList<>();
            for(int pitch=-85;pitch<=85;pitch++) alternatives.add((float)pitch);
            alternatives.sort(Comparator.comparingDouble(p->Math.abs(p-preferred)));
            pitches.addAll(alternatives);
        }
        for(float pitch:pitches) {
            double y=Math.toRadians(yaw),p=Math.toRadians(pitch),cos=Math.cos(p);
            Vec3 velocity=new Vec3(-Math.sin(y)*cos,-Math.sin(p),Math.cos(y)*cos).scale(speed);
            if(clear(level,shooter,origin,velocity,target,gravity,pierce,drag,waterDrag)) return new Plan(yaw,pitch,speed,gravity,velocity);
        }
        return null;
    }

    private static boolean clear(Level level, LivingEntity shooter, Vec3 origin, Vec3 velocity,
                                 LivingEntity target, boolean gravity, int pierce,
                                 double drag, double waterDrag) {
        Vec3 position=origin;
        var pierced=new java.util.HashSet<Integer>();
        for(int tick=0;tick<MAX_TICKS;tick++) {
            Vec3 end=position.add(velocity);
            var block=level.clip(new ClipContext(position,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,shooter));
            double obstruction=block.getType()==HitResult.Type.MISS?Double.POSITIVE_INFINITY:position.distanceToSqr(block.getLocation());
            var targetHit=target.getBoundingBox().inflate(.1).clip(position,end);
            double targetDistance=target.getBoundingBox().inflate(.1).contains(position)?0:
                    targetHit.map(position::distanceToSqr).orElse(Double.POSITIVE_INFINITY);
            AABB search=new AABB(position,end).inflate(.4);
            List<EntityHit> hits=new ArrayList<>();
            for(Entity other:level.getEntities(shooter,search,e->e!=target&&!e.isSpectator()&&e.isPickable()&&!pierced.contains(e.getId()))) {
                AABB box=other.getBoundingBox().inflate(.3);
                var hit=box.clip(position,end);
                if(box.contains(position)) hits.add(new EntityHit(other,0));
                else if(hit.isPresent()) hits.add(new EntityHit(other,position.distanceToSqr(hit.get())));
            }
            hits.sort(Comparator.comparingDouble(EntityHit::distance));
            for(EntityHit hit:hits) {
                if(hit.distance()>=Math.min(obstruction,targetDistance)) break;
                if(pierce<=pierced.size()) return false;
                pierced.add(hit.entity().getId());
            }
            if(targetDistance<Double.POSITIVE_INFINITY&&targetDistance<obstruction) return true;
            if(obstruction<Double.POSITIVE_INFINITY) return false;
            position=end;
            double step=gravity&&level.getFluidState(net.minecraft.core.BlockPos.containing(position)).is(net.minecraft.tags.FluidTags.WATER)?waterDrag:drag;
            velocity=gravity?velocity.scale(step).add(0,-.05,0):velocity;
            if(velocity.lengthSqr()<1.0E-8||position.y<level.getMinBuildHeight()-2) return false;
        }
        return false;
    }
    private record EntityHit(Entity entity,double distance) { }
}
