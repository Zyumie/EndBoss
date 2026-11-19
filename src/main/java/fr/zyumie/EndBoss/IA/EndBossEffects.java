package fr.zyumie.EndBoss.IA;

import fr.redsavant.bdapi.BDApi;
import fr.redsavant.bdapi.BDApiConfig;
import fr.redsavant.bdapi.DisplayCrate;
import fr.redsavant.bdapi.animation.Easing;
import fr.redsavant.bdapi.builder.BlockDisplayBuilder;
import fr.redsavant.bdapi.display.DisplayBackend;
import fr.redsavant.bdapi.packet.PacketBackendSupport;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class EndBossEffects {

    private record Live(DisplayCrate crate, long expiresAt) {}

    private record SwordPart(Material type, double offset, float x, float y, float z) {}

    private static final SwordPart[] SWORD = {
            new SwordPart(Material.NETHERITE_BLOCK, 0.35, 0.26f, 1.7f, 0.07f),
            new SwordPart(Material.AMETHYST_BLOCK, 0.35, 0.09f, 1.72f, 0.10f),
            new SwordPart(Material.NETHERITE_BLOCK, 1.28, 0.14f, 0.28f, 0.06f),
            new SwordPart(Material.CRYING_OBSIDIAN, -0.55, 0.8f, 0.14f, 0.16f),
            new SwordPart(Material.POLISHED_BLACKSTONE, -0.85, 0.1f, 0.5f, 0.1f),
            new SwordPart(Material.AMETHYST_BLOCK, -1.15, 0.18f, 0.18f, 0.18f)
    };

    public static final class BlockSword {
        private final List<DisplayCrate> crates = new ArrayList<>();
        private final List<Double> offsets = new ArrayList<>();

        private void add(DisplayCrate crate, double offset) {
            if (crate == null) return;
            crates.add(crate);
            offsets.add(offset);
        }

        public void aim(Location pos, Vector direction) {
            Vector d = direction.clone().normalize();
            float pitch = (float) Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, d.getY()))));
            float yaw = (float) Math.toDegrees(Math.atan2(d.getX(), d.getZ()));
            for (int i = 0; i < crates.size(); i++) {
                DisplayCrate crate = crates.get(i);
                crate.handle().teleport(pos.clone().add(d.clone().multiply(offsets.get(i))));
                crate.transform().rotate(pitch, yaw, 0f).apply();
            }
        }

        public void remove() {
            for (DisplayCrate crate : crates) crate.remove();
            crates.clear();
            offsets.clear();
        }
    }

    private static final Material FALLBACK = Material.END_STONE;
    private static final Material[] VOID_PALETTE = {
            Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.END_STONE_BRICKS, Material.PURPUR_BLOCK
    };

    private static final Material[] WATER_PALETTE = {
            Material.PRISMARINE, Material.DARK_PRISMARINE, Material.PRISMARINE_BRICKS, Material.SEA_LANTERN
    };

    private static final List<Live> LIVE = new ArrayList<>();
    private static final Random RNG = new Random();

    private static JavaPlugin plugin;
    private static boolean ready, enabled, warned;
    private static double range, density;
    private static int maxDisplays;

    private EndBossEffects() {}

    public static void init(JavaPlugin p) {
        plugin = p;
        reload();
        try {
            BDApi.init(p, BDApiConfig.builder().defaultBackend(DisplayBackend.PACKET_EVENTS).build());
            ready = true;
            if (!PacketBackendSupport.present()) {
                p.getLogger().warning("PacketEvents absent : effets de blocs affichés avec de vraies entités Paper.");
            }
        } catch (RuntimeException | LinkageError ex) {
            p.getLogger().log(Level.SEVERE, "BDApi indisponible : le boss fonctionnera sans effets de blocs.", ex);
        }
    }

    public static void reload() {
        enabled = plugin.getConfig().getBoolean("boss.effects.enabled", true);
        range = plugin.getConfig().getDouble("boss.effects.view-distance", 64.0);
        maxDisplays = plugin.getConfig().getInt("boss.effects.max-displays", 320);
        density = Math.max(0.0, plugin.getConfig().getDouble("boss.effects.density", 1.0));
    }

    public static void shutdown() {
        if (ready) BDApi.shutdown(true);
        ready = false;
        LIVE.clear();
    }

    public static void tick() {
        if (LIVE.isEmpty()) return;
        long now = System.currentTimeMillis();
        LIVE.removeIf(l -> {
            if (!l.crate().isValid()) return true;
            if (l.expiresAt() > now) return false;
            l.crate().remove();
            return true;
        });
    }

    public static void takeoff(Location at) {
        List<Player> v = viewers(at);
        if (v.isEmpty()) return;
        spray(at, 1.2, count(9), 0.28, 0.42, 0.28f, v);
    }

    public static void landing(Location at, double radius) {
        List<Player> v = viewers(at);
        if (v.isEmpty()) return;
        ring(at, radius * 0.75, 1.5f, 1.3f, 24f, 5, v);
        spray(at, radius * 0.6, count(12), 0.32, 0.5, 0.35f, v);
    }

    public static void dashTrail(LivingEntity boss, int ticks) {
        if (!active()) return;
        new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                if (++elapsed > ticks || !boss.isValid()) {
                    cancel();
                    return;
                }
                if (!boss.isOnGround()) return;
                Location at = boss.getLocation();
                List<Player> v = viewers(at);
                if (!v.isEmpty()) spray(at, 0.8, count(3), 0.14, 0.3, 0.26f, v);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public static void smashCharge(Location lock, double radius, int ticksUntilSlam) {
        List<Player> v = viewers(lock);
        if (v.isEmpty()) return;
        World w = lock.getWorld();
        for (int i = 0, n = count(18); i < n; i++) {
            double a = RNG.nextDouble() * 2 * Math.PI;
            double d = Math.sqrt(RNG.nextDouble()) * radius;
            Block ground = surface(w, lock.getX() + Math.cos(a) * d, lock.getY(), lock.getZ() + Math.sin(a) * d);
            if (ground == null) continue;

            float size = 0.4f + RNG.nextFloat() * 0.5f;
            Location from = ground.getLocation().add(0.5, 1.0, 0.5);
            DisplayCrate crate = spawn(from, material(ground.getType()), size, size,
                    randomAngle(), randomAngle(), randomAngle(), ticksUntilSlam + 40, v);
            if (crate == null) continue;

            Location top = from.clone().add(0, 1.5 + RNG.nextDouble() * 2.5, 0);
            crate.animate()
                    .moveTo(top)
                    .rotateTo(randomAngle(), randomAngle(), randomAngle())
                    .duration(ticksUntilSlam * 50L, TimeUnit.MILLISECONDS)
                    .easing(Easing.EASE_OUT)
                    .onComplete(() -> crate.physics()
                            .velocity(0, -0.9, 0)
                            .gravity(0.12)
                            .onLand(EndBossEffects::shrink)
                            .start())
                    .play();
        }
    }

    public static void smash(Location at, double radius) {
        List<Player> v = viewers(at);
        if (v.isEmpty()) return;
        ring(at, radius * 0.35, 2.0f, 2.2f, 34f, 12, v);
        later(2, () -> ring(at, radius * 0.65, 2.0f, 1.8f, 28f, 10, v));
        later(4, () -> ring(at, radius, 1.8f, 1.4f, 20f, 8, v));
        spray(at, radius * 0.5, count(24), 0.45, 0.75, 0.5f, v);
    }

    public static void shockRing(Location center, double r) {
        List<Player> v = viewers(center);
        if (v.isEmpty()) return;
        ring(center, r, 1.6f, 0.8f, 14f, 1, v);
    }

    public static void meteor(Location impact, double height, int ticks) {
        List<Player> v = viewers(impact);
        if (v.isEmpty()) return;
        float size = 1.5f + RNG.nextFloat() * 0.6f;
        Location from = impact.clone().add(0, height, 0);
        DisplayCrate crate = spawn(from, Material.MAGMA_BLOCK, size, size,
                randomAngle(), randomAngle(), randomAngle(), ticks + 4, v);
        if (crate == null) return;
        crate.animate()
                .moveTo(impact)
                .rotateTo(randomAngle(), randomAngle(), randomAngle())
                .duration(ticks * 50L, TimeUnit.MILLISECONDS)
                .easing(Easing.LINEAR)
                .onComplete(crate::remove)
                .play();
    }

    public static DisplayCrate raiseRock(Location buried, Location hover, Material m, float size, int riseTicks,
                                         int ttlTicks) {
        List<Player> v = viewers(hover);
        if (v.isEmpty()) return null;
        DisplayCrate crate = spawn(buried, m, size, size, randomAngle(), randomAngle(), randomAngle(), ttlTicks, v);
        if (crate == null) return null;
        crate.animate()
                .moveTo(hover)
                .rotateTo(randomAngle(), randomAngle(), randomAngle())
                .duration(riseTicks * 50L, TimeUnit.MILLISECONDS)
                .easing(Easing.EASE_OUT)
                .play();
        spray(buried.clone().add(0, size, 0), 0.7, count(4), 0.12, 0.3, 0.25f, v);
        return crate;
    }

    public static BlockSword sword(Location pos, int ttlTicks) {
        BlockSword sword = new BlockSword();
        List<Player> v = viewers(pos);
        if (v.isEmpty()) return sword;
        for (SwordPart p : SWORD) {
            sword.add(create(pos, p.type(), p.x(), p.y(), p.z(), 0f, 0f, 0f, 0f, ttlTicks, v), p.offset());
        }
        return sword;
    }

    static void beam(Location at, float height, List<Player> v) {
        Location base = at.clone().add(0, height / 2f - 2f, 0);
        DisplayCrate core = create(base, Material.CRYING_OBSIDIAN, 0.2f, height, 0.2f, 0f, 0f, 0f, 0f, 60, v);
        DisplayCrate halo = create(base, Material.PURPLE_STAINED_GLASS, 0.3f, height, 0.3f, 0f, 0f, 0f, 0f, 60, v);
        beamPulse(core, 3.5f, height);
        beamPulse(halo, 6.5f, height);
    }

    private static void beamPulse(DisplayCrate crate, float width, float height) {
        if (crate == null) return;
        crate.timeline()
                .easing(Easing.EASE_OUT)
                .scale(width, height, width, 300, TimeUnit.MILLISECONDS)
                .wait(1000, TimeUnit.MILLISECONDS)
                .easing(Easing.EASE_IN)
                .scale(0.01f, height, 0.01f, 700, TimeUnit.MILLISECONDS)
                .play();
    }

    static DisplayCrate tumblingPiece(Location at, Material m, float size, int ttlTicks, List<Player> v) {
        float rx = randomAngle(), ry = randomAngle(), rz = randomAngle();
        DisplayCrate crate = spawn(at, m, size, size, rx, ry, rz, ttlTicks, v);
        if (crate != null) tumble(crate, rx, ry, rz, randomStep(), randomStep());
        return crate;
    }

    private static void tumble(DisplayCrate crate, float rx, float ry, float rz, float dx, float dy) {
        if (!crate.isValid()) return;
        float nx = rx + dx, ny = ry + dy;
        crate.animate()
                .rotateTo(nx, ny, rz)
                .duration(600, TimeUnit.MILLISECONDS)
                .onComplete(() -> tumble(crate, nx, ny, rz, dx, dy))
                .play();
    }

    private static float randomStep() {
        return (RNG.nextBoolean() ? 1 : -1) * (50f + RNG.nextFloat() * 40f);
    }

    static void fling(DisplayCrate crate, Vector velocity) {
        crate.physics()
                .velocity(velocity)
                .gravity(0.06)
                .drag(0.008)
                .bounce(0.2)
                .onLand(EndBossEffects::shrink)
                .start();
    }

    static Material voidBlock() {
        return VOID_PALETTE[RNG.nextInt(VOID_PALETTE.length)];
    }

    public static void spin(DisplayCrate crate, int ticks) {
        if (crate == null) return;
        crate.animate()
                .rotateTo(randomAngle(), randomAngle(), randomAngle())
                .duration(ticks * 50L, TimeUnit.MILLISECONDS)
                .play();
    }

    public static void spikeTips(Location lock, double radius, int hold) {
        List<Player> v = viewers(lock);
        if (v.isEmpty()) return;
        World w = lock.getWorld();
        for (int i = 0, n = count((int) (radius * radius)); i < n; i++) {
            double a = RNG.nextDouble() * 2 * Math.PI;
            double d = Math.sqrt(RNG.nextDouble()) * radius;
            Block ground = surface(w, lock.getX() + Math.cos(a) * d, lock.getY(), lock.getZ() + Math.sin(a) * d);
            if (ground != null) plate(ground, Material.POINTED_DRIPSTONE, a, 1.0f, 1.0f, 0f, hold, v);
        }
    }

    public static void spikes(Location lock, double radius) {
        List<Player> v = viewers(lock);
        if (v.isEmpty()) return;
        Block middle = surface(lock.getWorld(), lock.getX(), lock.getY(), lock.getZ());
        if (middle != null) plate(middle, Material.POINTED_DRIPSTONE, 0, 2.8f, 10f, 0f, 14, v);
        ring(lock, radius * 0.5, 2.0f, 7f, 10f, 13, v, Material.POINTED_DRIPSTONE);
        ring(lock, radius, 1.8f, 5.5f, 18f, 12, v, Material.POINTED_DRIPSTONE);
        ring(lock, radius + 1.2, 1.6f, 1.3f, 24f, 8, v, null);
        spray(lock, radius * 0.6, count(12), 0.3, 0.9, 0.4f, v);
    }

    public static void slamImpact(Location at, double radius) {
        smash(at, radius);
        List<Player> v = viewers(at);
        if (!v.isEmpty()) ring(at, radius * 0.6, 1.8f, 4.5f, 34f, 10, v, Material.POINTED_DRIPSTONE);
    }

    public static void pullStream(Location from, Location to) {
        List<Player> v = viewers(from);
        if (v.isEmpty()) return;
        for (int i = 0, n = count(2); i < n; i++) {
            double a = RNG.nextDouble() * 2 * Math.PI;
            double r = 1.5 + RNG.nextDouble() * 1.5;
            Location at = from.clone().add(Math.cos(a) * r, RNG.nextDouble() * 2, Math.sin(a) * r);
            float size = 0.3f + RNG.nextFloat() * 0.5f;
            DisplayCrate crate = spawn(at, voidBlock(), size, size, randomAngle(), randomAngle(), randomAngle(), 26, v);
            if (crate == null) continue;
            crate.animate()
                    .moveTo(to)
                    .rotateTo(randomAngle(), randomAngle(), randomAngle())
                    .duration(1000, TimeUnit.MILLISECONDS)
                    .easing(Easing.EASE_IN)
                    .onComplete(crate::remove)
                    .play();
        }
    }

    public static void orbit(Location center, double radius, int pieces, int ticks) {
        List<Player> v = viewers(center);
        if (v.isEmpty()) return;
        List<DisplayCrate> crates = new ArrayList<>();
        for (int i = 0, n = count(pieces); i < n; i++) {
            DisplayCrate crate = tumblingPiece(center, voidBlock(), 0.8f + RNG.nextFloat() * 1.2f, ticks + 60, v);
            if (crate != null) crates.add(crate);
        }
        new BukkitRunnable() {
            int t = 0;

            private double angle(int i) {
                return 2 * Math.PI * i / crates.size() + t * 0.12;
            }

            @Override
            public void run() {
                for (int i = 0; i < crates.size(); i++) {
                    double a = angle(i);
                    double y = Math.sin(a * 3) * 1.5;
                    if (t < ticks) {
                        crates.get(i).handle().teleport(center.clone()
                                .add(Math.cos(a) * radius, y, Math.sin(a) * radius));
                    } else {
                        fling(crates.get(i), new Vector(Math.cos(a) * 0.6, -0.8, Math.sin(a) * 0.6));
                    }
                }
                if (t++ >= ticks) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public static void whirlpool(Location center, double radius, int ticks) {
        List<Player> v = viewers(center);
        if (v.isEmpty()) return;
        List<DisplayCrate> crates = new ArrayList<>();
        for (int i = 0, n = count(26); i < n; i++) {
            Material m = WATER_PALETTE[RNG.nextInt(WATER_PALETTE.length)];
            DisplayCrate crate = tumblingPiece(center, m, 0.6f + RNG.nextFloat() * 0.9f, ticks + 60, v);
            if (crate != null) crates.add(crate);
        }
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                for (int i = 0; i < crates.size(); i++) {
                    double share = (i / (double) crates.size() + t * 0.012) % 1.0;
                    double r = radius * (1.0 - share);
                    double a = i * 2.4 + t * 0.28;
                    if (t < ticks) {
                        crates.get(i).handle().teleport(center.clone()
                                .add(Math.cos(a) * r, share * 5.0 - 1.5, Math.sin(a) * r));
                    } else {
                        fling(crates.get(i), new Vector(Math.cos(a) * 0.8, 0.5, Math.sin(a) * 0.8));
                    }
                }
                if (t++ >= ticks) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public static void blink(Location at) {
        List<Player> v = viewers(at);
        if (v.isEmpty()) return;
        Location center = at.clone().add(0, 1, 0);
        for (int i = 0, n = count(8); i < n; i++) {
            Vector dir = Vector.getRandom().subtract(new Vector(0.5, 0.5, 0.5)).normalize().multiply(0.3);
            debris(center, RNG.nextBoolean() ? Material.OBSIDIAN : Material.CRYING_OBSIDIAN, dir, 0.3f, v);
        }
    }

    public static void burst(Location at, Material type, int pieces) {
        List<Player> v = viewers(at);
        if (v.isEmpty()) return;
        Material m = material(type);
        for (int i = 0, n = count(pieces); i < n; i++) {
            Vector vel = new Vector((RNG.nextDouble() - 0.5) * 0.4, 0.2 + RNG.nextDouble() * 0.2,
                    (RNG.nextDouble() - 0.5) * 0.4);
            debris(at, m, vel, 0.3f, v);
        }
    }

    public static void eruption(Location at, int phase) {
        List<Player> v = viewers(at);
        if (v.isEmpty()) return;
        for (int wave = 0; wave < 3; wave++) {
            final double r = 3.0 + wave * 2.8;
            final float height = 3.5f + phase - wave * 0.8f;
            later(wave * 3, () -> columns(at, r, height, v));
        }
        spray(at, 2.5, count(16 + phase * 6), 0.4, 0.8, 0.5f, v);
    }

    private static void columns(Location center, double r, float height, List<Player> v) {
        World w = center.getWorld();
        int n = count((int) Math.round(2 * Math.PI * r / 2.6));
        double offset = RNG.nextDouble() * 2 * Math.PI;
        for (int i = 0; i < n; i++) {
            double a = offset + 2 * Math.PI * i / n;
            Block ground = surface(w, center.getX() + Math.cos(a) * r, center.getY(), center.getZ() + Math.sin(a) * r);
            if (ground == null) continue;
            Material m = VOID_PALETTE[RNG.nextInt(VOID_PALETTE.length)];
            plate(ground, m, a, 1.3f + RNG.nextFloat() * 0.5f, height * (0.8f + RNG.nextFloat() * 0.4f), 16f, 16, v);
        }
    }

    private static void ring(Location center, double r, float width, float height, float tilt, int hold, List<Player> v) {
        ring(center, r, width, height, tilt, hold, v, null);
    }

    private static void ring(Location center, double r, float width, float height, float tilt, int hold, List<Player> v,
                             Material forced) {
        World w = center.getWorld();
        int n = Math.max(3, count((int) Math.round(2 * Math.PI * r / (width * 1.15))));
        double offset = RNG.nextDouble() * 2 * Math.PI;
        for (int i = 0; i < n; i++) {
            double a = offset + 2 * Math.PI * i / n;
            Block ground = surface(w, center.getX() + Math.cos(a) * r, center.getY(), center.getZ() + Math.sin(a) * r);
            if (ground == null) continue;
            float jitter = 0.85f + RNG.nextFloat() * 0.3f;
            Material m = forced != null ? forced : material(ground.getType());
            plate(ground, m, a, width * jitter, height * jitter, tilt, hold, v);
        }
    }

    private static void plate(Block ground, Material m, double angle, float width, float height, float tilt, int hold,
                              List<Player> v) {
        int rise = 2, sink = 8;
        Location buried = ground.getLocation().add(0.5, 1.0 - height, 0.5);
        Location raised = buried.clone().add(0, height * 0.7, 0);

        float yaw = 90f - (float) Math.toDegrees(angle);
        DisplayCrate crate = spawn(buried, m, width, height, tilt, yaw, 0f, rise + hold + sink + 4, v);
        if (crate == null) return;
        crate.timeline()
                .easing(Easing.EASE_OUT)
                .moveTo(raised, rise * 50L, TimeUnit.MILLISECONDS)
                .wait(hold * 50L, TimeUnit.MILLISECONDS)
                .easing(Easing.EASE_IN)
                .moveTo(buried, sink * 50L, TimeUnit.MILLISECONDS)
                .play();
    }

    private static void spray(Location center, double spread, int pieces, double speed, double lift, float size,
                              List<Player> v) {
        World w = center.getWorld();
        for (int i = 0; i < pieces; i++) {
            double a = RNG.nextDouble() * 2 * Math.PI;
            double d = RNG.nextDouble() * spread;
            double x = center.getX() + Math.cos(a) * d;
            double z = center.getZ() + Math.sin(a) * d;
            Block ground = surface(w, x, center.getY(), z);
            if (ground == null) continue;

            double power = speed * (0.6 + RNG.nextDouble() * 0.8);
            Vector vel = new Vector(Math.cos(a) * power, lift * (0.6 + RNG.nextDouble() * 0.6), Math.sin(a) * power);
            Location from = new Location(w, x, ground.getY() + 1.2, z);
            debris(from, material(ground.getType()), vel, size * (0.7f + RNG.nextFloat() * 0.8f), v);
        }
    }

    private static void debris(Location from, Material m, Vector velocity, float size, List<Player> v) {
        DisplayCrate crate = spawn(from, m, size, size, randomAngle(), randomAngle(), randomAngle(), 50, v);
        if (crate == null) return;
        crate.animate()
                .rotateTo(randomAngle(), randomAngle(), randomAngle())
                .duration(900, TimeUnit.MILLISECONDS)
                .play();
        crate.physics()
                .velocity(velocity)
                .gravity(0.07)
                .drag(0.01)
                .bounce(0.25)
                .onLand(EndBossEffects::shrink)
                .start();
    }

    private static void shrink(DisplayCrate crate) {
        crate.animate()
                .scaleTo(0.01f, 0.01f, 0.01f)
                .duration(500, TimeUnit.MILLISECONDS)
                .easing(Easing.EASE_IN)
                .onComplete(crate::remove)
                .play();
    }

    private static DisplayCrate spawn(Location at, Material m, float width, float height,
                                      float rx, float ry, float rz, int ttlTicks, List<Player> v) {
        return create(at, m, width, height, width, height / 2f, rx, ry, rz, ttlTicks, v);
    }

    private static DisplayCrate create(Location at, Material m, float sx, float sy, float sz, float lift,
                                       float rx, float ry, float rz, int ttlTicks, List<Player> v) {
        if (LIVE.size() >= maxDisplays) return null;
        try {
            BlockDisplayBuilder builder = BDApi.get().displays().create()
                    .at(at)
                    .block(m)
                    .scale(sx, sy, sz)
                    .translate(-0.5f, lift - 0.5f, -0.5f)
                    .rotate(rx, ry, rz);
            if (PacketBackendSupport.available()) {
                builder.backend(DisplayBackend.PACKET_EVENTS).viewers(v);
            } else {
                builder.backend(DisplayBackend.PAPER);
            }
            DisplayCrate crate = builder.spawn();
            crate.bukkitEntity().ifPresent(e -> {
                e.setPersistent(false);
                e.setTeleportDuration(2);
            });
            LIVE.add(new Live(crate, System.currentTimeMillis() + ttlTicks * 50L));
            return crate;
        } catch (RuntimeException ex) {
            if (!warned) {
                warned = true;
                plugin.getLogger().log(Level.WARNING, "Effet de bloc impossible à afficher", ex);
            }
            return null;
        }
    }

    private static boolean active() {
        return ready && enabled && density > 0;
    }

    static List<Player> viewers(Location at) {
        if (!active() || at.getWorld() == null) return List.of();
        return new ArrayList<>(at.getWorld().getNearbyPlayers(at, range));
    }

    static int count(int base) {
        return (int) Math.round(base * density);
    }

    private static float randomAngle() {
        return RNG.nextFloat() * 300f - 150f;
    }

    private static void later(long ticks, Runnable action) {
        plugin.getServer().getScheduler().runTaskLater(plugin, action, ticks);
    }

    public static Block surface(World w, double x, double y, double z) {
        Block b = w.getBlockAt((int) Math.floor(x), (int) Math.floor(y) + 3, (int) Math.floor(z));
        for (int i = 0; i < 9; i++) {
            Block below = b.getRelative(BlockFace.DOWN);
            if (b.isPassable() && below.getType().isSolid()) return below;
            b = below;
        }
        return null;
    }

    public static Material material(Material m) {
        return m.isBlock() && m.isOccluding() ? m : FALLBACK;
    }
}
