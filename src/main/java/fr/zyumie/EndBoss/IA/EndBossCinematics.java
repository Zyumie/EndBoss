package fr.zyumie.EndBoss.IA;

import fr.redsavant.bdapi.DisplayCrate;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

public final class EndBossCinematics {

    private record Swirl(int spawnTicks, int perTick, double minRadius, double maxRadius, double top,
                         int collapseAt, int burstAt, double focusHeight, double burstSpeed) {}

    private static final class Piece {
        final DisplayCrate crate;
        final double omega, climb;
        double angle, radius, height;

        Piece(DisplayCrate crate, double angle, double radius, double height, double omega, double climb) {
            this.crate = crate;
            this.angle = angle;
            this.radius = radius;
            this.height = height;
            this.omega = omega;
            this.climb = climb;
        }
    }

    private static final Swirl INTRO = new Swirl(50, 2, 5, 20, 26, 72, 92, 7, 1.1);
    private static final Swirl DEATH = new Swirl(30, 3, 4, 16, 16, 40, 54, 3, 1.2);
    private static final int SLAM_TICKS = 6;
    private static final int RAIN_TICKS = 40;

    private static final Random RNG = new Random();
    private static JavaPlugin plugin;

    private EndBossCinematics() {}

    public static void init(JavaPlugin p) {
        plugin = p;
    }

    public static void intro(EndBossBrain brain) {
        WitherSkeleton boss = brain.boss;
        Location center = boss.getLocation().clone();
        World w = center.getWorld();
        List<Player> v = EndBossEffects.viewers(center);
        if (v.isEmpty()) return;

        brain.beginCinematic(INTRO.burstAt() + 10);
        double hover = hoverHeight(center);
        int slamAt = INTRO.burstAt() - SLAM_TICKS;

        IntConsumer onTick = t -> {
            float yaw = center.getYaw() + t * 14f;
            double lift = t < INTRO.collapseAt() ? hover * Math.sin(Math.PI / 2 * t / INTRO.collapseAt()) : hover;
            if (t >= slamAt) lift = hover * (1.0 - (t - slamAt) / (double) SLAM_TICKS);
            Location at = center.clone().add(0, lift, 0);
            at.setYaw(yaw);
            boss.teleport(at);

            w.spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0, 1, 0), 12, 1, 1.5, 1, 0.3);
            if (t % 14 == 0) w.playSound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 4.0f, 0.5f);
            if (t % 5 == 0) w.playSound(center, Sound.BLOCK_DEEPSLATE_BREAK, 3.0f, 0.5f);
            if (t == INTRO.collapseAt()) w.playSound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 4.0f, 0.5f);
        };

        Runnable onBurst = () -> {
            brain.endCinematic();
            boss.teleport(center);
            shockwave(center, 16.0, 1.5);
            EndBossEffects.smash(center, 15);
            EndBossEffects.eruption(center, 3);
            w.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 4.0f, 0.5f);
            for (Player p : EndBossEffects.viewers(center)) {
                p.sendTitle(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "LE GARDIEN DU VIDE",
                        ChatColor.GRAY + "s'éveille", 5, 50, 15);
            }
        };

        play(center, INTRO, v, onTick, brain::isAlive, onBurst);
    }

    public static void death(Location at) {
        Location center = at.clone();
        World w = center.getWorld();
        List<Player> v = EndBossEffects.viewers(center);
        if (v.isEmpty()) return;

        IntConsumer onTick = t -> {
            w.spawnParticle(Particle.REVERSE_PORTAL, center.clone().add(0, DEATH.focusHeight(), 0), 20, 1.2, 1.2, 1.2, 0.5);
            if (t == 0) w.playSound(center, Sound.ENTITY_WITHER_DEATH, 4.0f, 0.5f);
            int beat = Math.max(4, 16 - t / 4);
            if (t % beat == 0) w.playSound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 4.0f, 0.5f + t * 0.02f);
            if (t == DEATH.collapseAt()) w.playSound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 4.0f, 0.7f);
        };

        Runnable onBurst = () -> {
            shockwave(center, 0.0, 0.0);
            w.playSound(center, Sound.ENTITY_ENDER_DRAGON_DEATH, 4.0f, 0.7f);
            EndBossEffects.beam(center, 80f, v);
            EndBossEffects.smash(center, 16);
            EndBossEffects.eruption(center, 3);
            rain(center, v);
        };

        play(center, DEATH, v, onTick, () -> true, onBurst);
    }

    private static void play(Location center, Swirl s, List<Player> v, IntConsumer onTick, BooleanSupplier alive,
                             Runnable onBurst) {
        List<Piece> pieces = new ArrayList<>();
        double focus = center.getY() + s.focusHeight();
        int perTick = EndBossEffects.count(s.perTick());

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!alive.getAsBoolean()) {
                    for (Piece p : pieces) p.crate.remove();
                    cancel();
                    return;
                }
                onTick.accept(t);
                if (t < s.spawnTicks()) {
                    for (int i = 0; i < perTick; i++) addPiece(pieces, center, s, v);
                }
                for (Piece p : pieces) advance(p, center, s, focus, t);
                if (t == s.burstAt()) {
                    for (Piece p : pieces) launch(p, center, s);
                    onBurst.run();
                    cancel();
                    return;
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static void addPiece(List<Piece> pieces, Location center, Swirl s, List<Player> v) {
        double angle = RNG.nextDouble() * 2 * Math.PI;
        double radius = s.minRadius() + RNG.nextDouble() * (s.maxRadius() - s.minRadius());
        double x = center.getX() + Math.cos(angle) * radius;
        double z = center.getZ() + Math.sin(angle) * radius;
        Block ground = EndBossEffects.surface(center.getWorld(), x, center.getY(), z);

        float size = 0.8f + RNG.nextFloat() * RNG.nextFloat() * 2.4f;
        double height = ground != null ? ground.getY() + 1.0 - size - center.getY() : -2.0;
        Material type = ground != null ? EndBossEffects.material(ground.getType()) : EndBossEffects.voidBlock();

        Location at = new Location(center.getWorld(), x, center.getY() + height, z);
        DisplayCrate crate = EndBossEffects.tumblingPiece(at, type, size, s.burstAt() + 70, v);
        if (crate == null) return;
        pieces.add(new Piece(crate, angle, radius, height, 0.14 + RNG.nextDouble() * 0.16,
                0.25 + RNG.nextDouble() * 0.3));
    }

    private static void advance(Piece p, Location center, Swirl s, double focus, int t) {
        if (t < s.collapseAt()) {
            p.height = Math.min(s.top(), p.height + p.climb);
            double funnel = 2.5 + Math.max(0, p.height) * 0.55;
            p.radius += (funnel - p.radius) * 0.05;
            p.angle += p.omega;
        } else {
            double base = center.getY();
            p.height = (focus - base) + (p.height - (focus - base)) * 0.8;
            p.radius *= 0.8;
            p.angle += p.omega * 1.8;
        }
        p.crate.handle().teleport(new Location(center.getWorld(),
                center.getX() + Math.cos(p.angle) * p.radius,
                center.getY() + p.height,
                center.getZ() + Math.sin(p.angle) * p.radius));
    }

    private static void launch(Piece p, Location center, Swirl s) {
        Vector dir = Vector.getRandom().subtract(new Vector(0.5, 0.5, 0.5));
        dir.setY(Math.abs(dir.getY()) + 0.15);
        if (dir.lengthSquared() < 0.01) dir = new Vector(0, 1, 0);
        dir.normalize().multiply(s.burstSpeed() * (0.5 + RNG.nextDouble() * 0.7));
        EndBossEffects.fling(p.crate, dir);
    }

    private static void shockwave(Location center, double pushRadius, double power) {
        World w = center.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, center.clone().add(0, 1, 0), 5, 3, 1, 3, 0);
        w.spawnParticle(Particle.SONIC_BOOM, center.clone().add(0, 1, 0), 1);
        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 0.5f);
        w.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 4.0f, 0.6f);
        w.playSound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 4.0f, 0.5f);
        if (pushRadius <= 0) return;
        for (Player p : w.getNearbyPlayers(center, pushRadius)) {
            Vector push = p.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (push.lengthSquared() < 0.01) push = new Vector(0, 0, 1);
            p.setVelocity(push.normalize().multiply(power).setY(0.7));
        }
    }

    private static void rain(Location center, List<Player> v) {
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (++t > RAIN_TICKS) {
                    cancel();
                    return;
                }
                for (int i = 0, n = EndBossEffects.count(2); i < n; i++) {
                    double angle = RNG.nextDouble() * 2 * Math.PI;
                    double radius = 4 + RNG.nextDouble() * 20;
                    Location at = center.clone().add(Math.cos(angle) * radius, 24 + RNG.nextDouble() * 12,
                            Math.sin(angle) * radius);
                    float size = 0.5f + RNG.nextFloat() * 1.2f;
                    DisplayCrate crate = EndBossEffects.tumblingPiece(at, EndBossEffects.voidBlock(), size, 70, v);
                    if (crate != null) EndBossEffects.fling(crate, new Vector(0, -0.6, 0));
                }
            }
        }.runTaskTimer(plugin, 10L, 1L);
    }

    private static double hoverHeight(Location center) {
        double clear = 0;
        while (clear < 10 && center.clone().add(0, clear + 3, 0).getBlock().isPassable()) clear++;
        return Math.max(0, clear - 1);
    }
}
