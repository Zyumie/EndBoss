package fr.zyumie.EndBoss.IA;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class EndBossWater {

    private static final int STRIKE_DELAY = 20;
    private static final int ENTER_FX_DELAY = 100;
    private static final double MAX_CLIMB = 0.6;
    private static final int EXIT_WINDOW = 6;
    private static final double EXIT_PUSH = 0.55;
    private static final double EXIT_LIFT = 0.42;

    private final EndBossBrain brain;
    private final WitherSkeleton boss;

    private boolean active;
    private long nextStrike, nextEnterFx, lastSwim = -100;

    public EndBossWater(EndBossBrain brain) {
        this.brain = brain;
        this.boss = brain.boss;
    }

    public boolean enabled() {
        return brain.cfg().getBoolean("boss.water.enabled", true);
    }

    public boolean active() {
        return active;
    }

    public void apply() {
        boolean on = enabled();
        EndBossBrain.setAttribute(boss, Attribute.WATER_MOVEMENT_EFFICIENCY, on ? 1.0 : 0.0);
        boss.getPathfinder().setCanFloat(on);
    }

    public void tick() {
        boolean wet = enabled() && boss.isInWater();
        if (wet && !active) onEnter();
        if (!wet && active) onExit();
        active = wet;
        if (!active) return;

        boss.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 30, 0, false, false, false));
        if (boss.getVelocity().lengthSquared() > 0.04) {
            boss.getWorld().spawnParticle(Particle.BUBBLE, boss.getLocation().add(0, boss.getHeight() / 2.0, 0),
                    6, 0.4, 0.5, 0.4, 0.02);
        }
    }

    private void onEnter() {
        if (brain.now() < nextEnterFx) return;
        nextEnterFx = brain.now() + ENTER_FX_DELAY;
        Location at = boss.getLocation();
        World w = boss.getWorld();
        w.playSound(at, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.2f, 0.8f);
        w.spawnParticle(Particle.SPLASH, at.clone().add(0, 1, 0), 80, 1.2, 0.6, 1.2, 0.2);
        EndBossEffects.burst(at.clone().add(0, 1, 0), Material.PRISMARINE, 12);
    }

    private void onExit() {
        Player t = brain.target();
        if (t == null || brain.now() - lastSwim > EXIT_WINDOW) return;
        Vector flat = t.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (flat.lengthSquared() < 0.01) return;
        flat.normalize().multiply(EXIT_PUSH);
        boss.setVelocity(flat.setY(Math.max(EXIT_LIFT, boss.getVelocity().getY())));
        boss.getWorld().spawnParticle(Particle.SPLASH, boss.getLocation(), 40, 0.8, 0.2, 0.8, 0.2);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 1.2f, 0.7f);
    }

    public boolean swim(Player t, double dist) {
        if (!active || !deep()) return false;
        lastSwim = brain.now();
        boss.getPathfinder().stopPathfinding();

        double reach = 1.8 + boss.getWidth();
        if (dist <= reach && brain.now() >= nextStrike) {
            nextStrike = brain.now() + STRIKE_DELAY;
            boss.swingMainHand();
            boss.attack(t);
        }
        if (dist <= reach * 0.7) {
            boss.setVelocity(new Vector(0, 0, 0));
            return true;
        }

        Location bl = boss.getLocation();
        Vector dir = t.getLocation().toVector().subtract(bl.toVector()).normalize();
        Vector velocity = dir.clone().multiply(swimSpeed());
        velocity.setY(Math.max(-MAX_CLIMB, Math.min(MAX_CLIMB, velocity.getY())));

        Vector flat = dir.clone().setY(0);
        if (flat.lengthSquared() > 0.001) {
            flat.normalize().multiply(boss.getWidth() / 2.0 + 0.7);
            if (!bl.clone().add(flat).getBlock().isPassable()) velocity.setY(MAX_CLIMB);
        }
        boss.setVelocity(velocity);
        return true;
    }

    private boolean deep() {
        return boss.isUnderWater() || boss.getLocation().add(0, 1, 0).getBlock().getType() == Material.WATER;
    }

    private double swimSpeed() {
        return brain.cfg().getDouble("boss.water.swim-speed", 0.6) * brain.phases().speedMultiplier();
    }

    public double damageDealt() {
        return active ? brain.cfg().getDouble("boss.water.damage-multiplier", 1.35) : 1.0;
    }

    public double damageTaken() {
        return active ? brain.cfg().getDouble("boss.water.damage-taken-multiplier", 0.75) : 1.0;
    }

    public double cooldownMultiplier() {
        return active ? brain.cfg().getDouble("boss.water.cooldown-multiplier", 0.7) : 1.0;
    }

    public boolean whirlpoolEnabled() {
        return active && brain.cfg().getBoolean("boss.water.whirlpool.enabled", true);
    }

    public double whirlpoolRadius() {
        return brain.cfg().getDouble("boss.water.whirlpool.radius", 14.0);
    }

    public double whirlpoolDamage() {
        return brain.cfg().getDouble("boss.water.whirlpool.damage", 3.0);
    }
}
