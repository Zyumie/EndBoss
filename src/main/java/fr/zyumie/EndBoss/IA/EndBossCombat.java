package fr.zyumie.EndBoss.IA;

import fr.redsavant.bdapi.DisplayCrate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Exécution des capacités. Toutes les attaques lourdes sont TÉLÉGRAPHIÉES (anneaux de particules,
 * message d'action bar) : le boss est difficile mais lisible, un joueur attentif peut toujours réagir.
 */
public class EndBossCombat {

    private static final int METEOR_FALL_START = 14;
    private static final double METEOR_SPEED = 2.5;

    private static final int SLAM_WARN = 24;
    private static final int SLAM_APEX = 18;
    private static final int SLAM_HOVER = 5;
    private static final double SLAM_RADIUS = 6.0;
    private static final double SLAM_FALL_SPEED = 3.2;
    private static final int THEFT_PULL = 10;
    private static final int THEFT_HOLD = 30;
    private static final int THEFT_MAX_FLIGHT = 40;
    private static final int RAPTURE_RISE = 30;
    private static final int RAPTURE_PULL = 40;
    private static final int RAPTURE_HOLD = 30;
    private static final int RAPTURE_HEIGHT = 30;
    private static final double RAPTURE_RANGE = 100.0;
    private static final int SCRAMBLE_TICKS = 100;
    private static final int SWORD_WINDUP = 8;
    private static final int SWORD_MAX_FLIGHT = 70;
    private static final int SWORD_STUCK = 30;
    private static final int ROCK_RISE = 12;
    private static final int ROCK_STAGGER = 3;
    private static final int ROCK_MAX_FLIGHT = 60;
    private static final double ROCK_SPEED = 1.15;
    private static final int WHIRLPOOL_WARN = 16;
    private static final int WHIRLPOOL_TICKS = 54;
    private static final int WHIRLPOOL_PULSE = 4;
    private static final int WHIRLPOOL_HIT = 10;
    private static final double WHIRLPOOL_FINAL_RADIUS = 6.0;

    private record Rock(Location pos, Material type, float size, DisplayCrate crate) {
        void remove() {
            if (crate != null) crate.remove();
        }
    }

    private final EndBossBrain brain;
    private final WitherSkeleton boss;
    private final JavaPlugin plugin;
    private final Random rng = new Random();

    public EndBossCombat(EndBossBrain brain) {
        this.brain = brain;
        this.boss = brain.boss;
        this.plugin = brain.plugin;
    }

    public void execute(Ability a, Player t) {
        if (t == null || !brain.isAlive()) return;
        switch (a) {
            case LEAP -> leap(t);
            case DASH_STRIKE -> dashStrike(t, false);
            case SWORD_THROW -> swordThrow(t);
            case SHIELD_UP -> shieldUp();
            case BLINK -> blink(t);
            case MACE_SMASH -> maceSmash(t);
            case SHOCKWAVE -> shockwave();
            case SUMMON -> summon(t);
            case METEORS -> meteors(t);
            case BLOCK_BARRAGE -> blockBarrage(t);
            case SKY_SLAM -> skySlam(t);
            case ITEM_THEFT -> itemTheft(t);
            case SKY_RAPTURE -> skyRapture();
            case INVENTORY_SCRAMBLE -> inventoryScramble(t);
            case WHIRLPOOL -> whirlpool();
        }
    }

    /** Contre-attaque rapide après une garde réussie. */
    public void counterStrike(Player t) {
        dashStrike(t, true);
    }

    // ------------------------------------------------------------------ utilitaires

    private double dmg(double base) {
        return base * brain.phases().damageMultiplier() * brain.cfg().getDouble("boss.ability-damage", 1.0);
    }

    private void hurt(Player p, double amount) {
        if (p.isValid() && !p.isDead()) p.damage(amount, boss);
    }

    private void knock(Player p, Location from, double power) {
        Vector push = p.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (push.lengthSquared() < 0.0001) push = new Vector(0, 0, 1);
        push.normalize().multiply(power).setY(0.35 + power * 0.2);
        p.setVelocity(push);
    }

    /** Sol sous une position (télégraphes d'attaque). */
    private Location ground(Location loc) {
        Location g = loc.clone();
        for (int i = 0; i < 10 && g.getBlock().isPassable(); i++) g.subtract(0, 1, 0);
        g.setY(g.getBlockY() + 1.05);
        return g;
    }

    private void ring(Location c, double radius, Color color, float size, int points) {
        World w = c.getWorld();
        Particle.DustOptions opt = new Particle.DustOptions(color, size);
        for (int i = 0; i < points; i++) {
            double ang = 2 * Math.PI * i / points;
            w.spawnParticle(Particle.DUST, c.getX() + Math.cos(ang) * radius, c.getY() + 0.1,
                    c.getZ() + Math.sin(ang) * radius, 1, 0, 0, 0, 0, opt);
        }
    }

    /** Dégâts de zone avec atténuation selon la distance. Les boucliers des joueurs fonctionnent normalement. */
    private void impact(Location c, double radius, double baseDamage, double knockback) {
        for (Player p : c.getWorld().getNearbyPlayers(c, radius)) {
            if (!brain.isValidTarget(p)) continue;
            double d = p.getLocation().distance(c);
            double falloff = 1.0 - (d / radius) * 0.5;
            hurt(p, dmg(baseDamage) * falloff);
            knock(p, c, knockback);
        }
    }

    private BlockData crackData(Location loc) {
        Block under = loc.clone().subtract(0, 1, 0).getBlock();
        return under.getType().isAir() ? Material.STONE.createBlockData() : under.getBlockData();
    }

    private void smashFx(Location loc) {
        World w = loc.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 2);
        w.spawnParticle(Particle.BLOCK, loc, 60, 2.5, 0.2, 2.5, 0.1, crackData(loc));
        w.playSound(loc, Sound.ITEM_MACE_SMASH_GROUND, 3.0f, 0.6f);
        w.playSound(loc, Sound.ENTITY_GENERIC_BIG_FALL, 2.5f, 0.4f);
    }

    /** Exécute {@code action} quand le boss a touché le sol (ou après maxTicks). */
    private void afterLanding(int minTicks, int maxTicks, Runnable action) {
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive()) {
                    cancel();
                    return;
                }
                if (ticks >= minTicks && (boss.isOnGround() || boss.isInWater() || ticks >= maxTicks)) {
                    cancel();
                    action.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ------------------------------------------------------------------ capacités

    /** Bond vers la position PRÉDITE de la cible : rattrape un joueur qui kite. */
    private void leap(Player t) {
        brain.busy(18);
        brain.startCooldown(Ability.LEAP);
        Location from = boss.getLocation();
        Vector d = brain.predict(t, 10).toVector().subtract(from.toVector());
        double h = Math.hypot(d.getX(), d.getZ());
        if (h < 0.5) return;

        Vector v = new Vector(d.getX() / h, 0, d.getZ() / h).multiply(Math.min(1.6, 0.25 + h * 0.085));
        v.setY(0.62 + Math.min(0.45, h * 0.018));
        boss.setVelocity(v);
        from.getWorld().playSound(from, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.5f, 0.8f);
        from.getWorld().spawnParticle(Particle.CLOUD, from, 12, 0.4, 0.1, 0.4, 0.05);
        EndBossEffects.takeoff(from);

        afterLanding(6, 35, () -> {
            Location land = boss.getLocation();
            impact(land, 3.5, 9.0, 0.7);
            land.getWorld().spawnParticle(Particle.EXPLOSION, land, 1);
            land.getWorld().playSound(land, Sound.ENTITY_GENERIC_BIG_FALL, 1.2f, 0.7f);
            EndBossEffects.landing(land, 3.5);
        });
    }

    private void dashStrike(Player t, boolean counter) {
        brain.busy(14);
        if (!counter) brain.startCooldown(Ability.DASH_STRIKE);
        boolean swimming = brain.water().active();
        Vector dir = t.getLocation().toVector().subtract(boss.getLocation().toVector());
        if (!swimming) dir.setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize().multiply(1.25);
        if (!swimming) dir.setY(0.2);
        boss.setVelocity(dir);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 0.6f);
        EndBossEffects.dashTrail(boss, 6);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!brain.isAlive() || !t.isOnline() || t.isDead()) return;
            Location bl = boss.getLocation();
            if (t.getLocation().distanceSquared(bl) < 16) {
                hurt(t, dmg(counter ? 20 : 14));
                knock(t, bl, 0.9);
                bl.getWorld().spawnParticle(Particle.SWEEP_ATTACK, t.getLocation().add(0, 1, 0), 3, 0.4, 0.3, 0.4, 0);
                bl.getWorld().playSound(bl, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 0.8f);
            }
        }, 5L);
    }

    /** Épée volante à tête chercheuse limitée : esquivable en changeant de direction. 3 épées en phase 3. */
    private void swordThrow(Player t) {
        brain.busy(14 + SWORD_WINDUP);
        brain.startCooldown(Ability.SWORD_THROW);
        int count = brain.phases().current() >= 3 ? 3 : 1;
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.5f, 1.4f);
        for (int i = 0; i < count; i++) {
            final double angle = (i - (count - 1) / 2.0) * 0.30;
            Bukkit.getScheduler().runTaskLater(plugin, () -> launchSword(t, angle), i * 4L);
        }
    }

    private void launchSword(Player t, double spread) {
        if (!brain.isAlive() || !t.isOnline()) return;
        World w = boss.getWorld();
        Location hold = boss.getEyeLocation().add(0, 1.4, 0);
        Vector side = boss.getEyeLocation().getDirection().setY(0).rotateAroundY(Math.PI / 2);
        if (side.lengthSquared() > 0.01) hold.add(side.normalize().multiply(spread * 4));
        Vector up = new Vector(0, 1, 0);

        EndBossEffects.BlockSword sword = EndBossEffects.sword(hold, SWORD_WINDUP + SWORD_MAX_FLIGHT + SWORD_STUCK + 10);
        sword.aim(hold, up);

        new BukkitRunnable() {
            final Location pos = hold.clone();
            Vector vel;
            int ticks = 0;

            private Vector toTarget() {
                return t.getEyeLocation().toVector().subtract(pos.toVector()).normalize().rotateAroundY(spread);
            }

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive() || !t.isOnline() || ticks > SWORD_WINDUP + SWORD_MAX_FLIGHT) {
                    sword.remove();
                    cancel();
                    return;
                }
                if (ticks < SWORD_WINDUP) {
                    double p = ticks / (double) SWORD_WINDUP;
                    sword.aim(pos, up.clone().multiply(1 - p).add(toTarget().multiply(p)));
                    w.spawnParticle(Particle.REVERSE_PORTAL, pos, 4, 0.2, 0.4, 0.2, 0.02);
                    return;
                }
                if (vel == null) {
                    vel = toTarget().multiply(0.9);
                    w.playSound(pos, Sound.ITEM_TRIDENT_THROW, 1.8f, 0.6f);
                }
                Vector want = brain.predict(t, 3).add(0, 1.0, 0).toVector().subtract(pos.toVector());
                if (want.lengthSquared() > 0.01) want.normalize().multiply(1.1);
                vel = vel.multiply(0.84).add(want.multiply(0.16));
                if (vel.length() > 1.1) vel.normalize().multiply(1.1);

                Location next = pos.clone().add(vel);
                if (!next.getBlock().isPassable()) {
                    w.spawnParticle(Particle.EXPLOSION, pos, 1);
                    w.playSound(pos, Sound.BLOCK_ANVIL_LAND, 1.2f, 0.6f);
                    EndBossEffects.burst(pos, next.getBlock().getType(), 5);
                    Bukkit.getScheduler().runTaskLater(plugin, sword::remove, SWORD_STUCK);
                    cancel();
                    return;
                }
                pos.setX(next.getX());
                pos.setY(next.getY());
                pos.setZ(next.getZ());
                sword.aim(pos, vel);
                w.spawnParticle(Particle.CRIT, pos, 3, 0.1, 0.1, 0.1, 0.05);

                for (Player p : w.getNearbyPlayers(pos, 1.2)) {
                    if (!brain.isValidTarget(p)) continue;
                    hurt(p, dmg(13));
                    w.spawnParticle(Particle.EXPLOSION, pos, 1);
                    w.playSound(pos, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 1.0f);
                    EndBossEffects.burst(pos, Material.AMETHYST_BLOCK, 5);
                    sword.remove();
                    cancel();
                    return;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void whirlpool() {
        int total = WHIRLPOOL_WARN + WHIRLPOOL_TICKS;
        brain.busy(total + 10);
        brain.startCooldown(Ability.WHIRLPOOL);

        World w = boss.getWorld();
        Location center = boss.getLocation().add(0, 1, 0);
        double radius = brain.water().whirlpoolRadius();
        double damage = brain.water().whirlpoolDamage();

        w.playSound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2.0f, 0.6f);
        w.playSound(center, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 2.5f, 0.7f);
        EndBossEffects.whirlpool(center, radius, total);
        for (Player p : w.getNearbyPlayers(center, radius)) {
            if (brain.isValidTarget(p) && p.isInWater()) {
                p.sendActionBar(Component.text("⚠ TOURBILLON ! SORS DE L'EAU !", NamedTextColor.AQUA));
            }
        }

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive()) {
                    cancel();
                    return;
                }
                boss.setVelocity(new Vector(0, 0, 0));
                ring(center, radius * (1.0 - ticks / (double) total), Color.AQUA, 2.0f, 28);
                w.spawnParticle(Particle.BUBBLE_COLUMN_UP, center, 30, radius * 0.4, 1.2, radius * 0.4, 0.1);
                if (ticks <= WHIRLPOOL_WARN) return;

                for (Player p : w.getNearbyPlayers(center, radius)) {
                    if (!brain.isValidTarget(p) || !p.isInWater()) continue;
                    if (ticks % WHIRLPOOL_PULSE == 0) drag(p, center);
                    if (ticks % WHIRLPOOL_HIT == 0) hurt(p, dmg(damage));
                }
                if (ticks >= total) {
                    cancel();
                    impact(center, WHIRLPOOL_FINAL_RADIUS, 12.0, 1.2);
                    w.spawnParticle(Particle.SPLASH, center, 200, 3, 1, 3, 0.4);
                    w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.4f);
                    EndBossEffects.burst(center, Material.PRISMARINE, 16);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void drag(Player p, Location center) {
        Vector in = center.toVector().subtract(p.getLocation().toVector());
        double y = Math.max(-0.2, Math.min(0.2, in.getY() * 0.08));
        in.setY(0);
        if (in.lengthSquared() < 0.01) return;
        in.normalize();
        Vector around = new Vector(-in.getZ(), 0, in.getX());
        p.setVelocity(in.multiply(0.32).add(around.multiply(0.24)).setY(y));
    }

    private void shieldUp() {
        brain.startCooldown(Ability.SHIELD_UP);
        brain.raiseShield(40 + rng.nextInt(25));
    }

    /** Téléportation dans le dos de la cible, avec un court télégraphe à l'arrivée. */
    private void blink(Player t) {
        Location dest = findBlinkSpot(t);
        if (dest == null) return;
        brain.busy(14);
        brain.startCooldown(Ability.BLINK);

        World w = boss.getWorld();
        Location from = boss.getLocation();
        w.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 2.0f, 0.6f);
        w.spawnParticle(Particle.REVERSE_PORTAL, from.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
        EndBossEffects.blink(from);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive()) {
                    cancel();
                    return;
                }
                w.spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 25, 0.4, 0.9, 0.4, 0.3);
                if (ticks >= 8) {
                    cancel();
                    Location arrive = dest.clone();
                    arrive.setDirection(t.getLocation().toVector().subtract(arrive.toVector()));
                    boss.teleport(arrive);
                    boss.setVelocity(new Vector(0, 0, 0));
                    w.playSound(arrive, Sound.ENTITY_ENDERMAN_TELEPORT, 2.0f, 0.5f);
                    EndBossEffects.blink(arrive);
                    if (t.isOnline() && !t.isDead() && t.getLocation().distanceSquared(arrive) < 25) {
                        hurt(t, dmg(10));
                        knock(t, arrive, 0.8);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Location findBlinkSpot(Player t) {
        Location tl = t.getLocation();
        Vector look = tl.getDirection().setY(0);
        if (look.lengthSquared() < 0.01) look = new Vector(1, 0, 0);
        look.normalize();
        int[] angles = {180, 150, -150, 110, -110, 70, -70, 0}; // 180° = dans le dos du joueur
        double[] radii = {2.8, 3.6, 4.6};
        for (double r : radii) {
            for (int a : angles) {
                Vector v = look.clone().rotateAroundY(Math.toRadians(a)).multiply(r);
                Location spot = standable(tl.clone().add(v));
                if (spot != null) return spot;
            }
        }
        return null;
    }

    private Location standable(Location c) {
        int h = (int) Math.ceil(boss.getHeight());
        for (int off : new int[]{0, 1, -1, 2, -2}) {
            Location l = c.clone().add(0, off, 0);
            Block feet = l.getBlock();
            boolean clear = true;
            for (int i = 0; i < h; i++) {
                if (!feet.getRelative(0, i, 0).isPassable()) {
                    clear = false;
                    break;
                }
            }
            if (clear && !feet.getRelative(0, -1, 0).isPassable()) {
                return new Location(l.getWorld(), l.getBlockX() + 0.5, l.getBlockY(), l.getBlockZ() + 0.5);
            }
        }
        return null;
    }

    /**
     * Saut, suspension en l'air avec anneau rouge au sol (la zone est verrouillée),
     * puis plongeon. Le joueur a ~0,5 s pour sortir de la zone.
     */
    private void maceSmash(Player t) {
        brain.busy(70);
        brain.startCooldown(Ability.MACE_SMASH);
        int phase = brain.phases().current();
        World w = boss.getWorld();
        w.playSound(boss.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 0.4f);
        t.sendActionBar(Component.text("⚠ MACE SMASH !", NamedTextColor.DARK_RED));

        Vector toward = t.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (toward.lengthSquared() > 0.01) toward.normalize().multiply(0.25);
        boss.setVelocity(new Vector(toward.getX(), 1.05 + 0.1 * phase, toward.getZ()));

        double radius = phase >= 3 ? 8.0 : 6.5;
        new BukkitRunnable() {
            int ticks = 0;
            Location lock;
            boolean slammed;

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive()) {
                    boss.setGravity(true);
                    cancel();
                    return;
                }
                if (ticks == 12) {
                    lock = ground(brain.predict(t, 8));
                    boss.setGravity(false);
                    boss.setVelocity(new Vector(0, 0, 0));
                    EndBossEffects.smashCharge(lock, radius, 10);
                }
                if (ticks > 12 && !slammed) {
                    ring(lock, radius, Color.RED, 1.6f, 36);
                    ring(lock, radius * Math.min(1.0, (ticks - 12) / 10.0), Color.ORANGE, 1.2f, 24);
                    if (ticks == 22) {
                        slammed = true;
                        boss.setGravity(true);
                        Vector v = lock.toVector().subtract(boss.getLocation().toVector());
                        if (v.lengthSquared() > 0.01) v.normalize().multiply(2.2);
                        boss.setVelocity(v);
                    }
                } else if (slammed && (boss.isOnGround() || ticks >= 42)) {
                    cancel();
                    Location loc = boss.getLocation();
                    impact(loc, radius, 28.0, 1.3);
                    smashFx(loc);
                    EndBossEffects.smash(loc, radius);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Onde de choc circulaire : on l'évite en SAUTANT au passage de l'anneau. */
    private void shockwave() {
        brain.busy(34);
        brain.startCooldown(Ability.SHOCKWAVE);
        double radius = brain.phases().current() >= 3 ? 13 : 10;
        Location center = boss.getLocation().clone();
        World w = center.getWorld();
        w.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 0.7f);
        for (Player p : w.getNearbyPlayers(center, radius + 6)) {
            p.sendActionBar(Component.text("⚠ SAUTEZ !", NamedTextColor.GOLD));
        }
        Set<Player> hit = new HashSet<>();

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive()) {
                    cancel();
                    return;
                }
                if (ticks <= 18) {
                    ring(center, radius, Color.RED, 1.4f, 40);
                    ring(center, radius * ticks / 18.0, Color.ORANGE, 1.0f, 28);
                } else if (ticks <= 30) {
                    if (ticks == 19) w.playSound(center, Sound.ITEM_MACE_SMASH_GROUND, 3.0f, 0.5f);
                    double r = radius * (ticks - 18) / 12.0;
                    ring(center, r, Color.WHITE, 1.8f, 48);
                    if (ticks % 2 == 1) EndBossEffects.shockRing(center, r);
                    for (Player p : w.getNearbyPlayers(center, radius + 2)) {
                        if (hit.contains(p) || !brain.isValidTarget(p)) continue;
                        Location pl = p.getLocation();
                        double d = Math.hypot(pl.getX() - center.getX(), pl.getZ() - center.getZ());
                        if (Math.abs(d - r) > 1.2) continue;
                        if (pl.getY() - center.getY() > 0.8) continue; // saut réussi
                        hit.add(p);
                        hurt(p, dmg(15));
                        knock(p, center, 1.1);
                    }
                } else {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Invoque des Vex (durée de vie limitée, plafonnés par boss.ai.minion-limit). */
    private void summon(Player t) {
        int room = brain.cfg().getInt("boss.ai.minion-limit", 4) - brain.minionCount();
        if (room <= 0) return;
        brain.busy(30);
        brain.startCooldown(Ability.SUMMON);

        int n = Math.min(room, brain.phases().current() >= 3 ? 3 : 2);
        World w = boss.getWorld();
        w.playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2.0f, 0.6f);
        for (int i = 0; i < n; i++) {
            Location l = boss.getLocation().clone()
                    .add((rng.nextDouble() - 0.5) * 4, 1.5, (rng.nextDouble() - 0.5) * 4);
            Vex vex = w.spawn(l, Vex.class, v -> {
                v.setCustomName("§5Éclat du Vide");
                v.setTarget(t);
                v.setPersistent(false);
            });
            brain.addMinion(vex);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 20, 0.3, 0.3, 0.3, 0.05);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (vex.isValid()) vex.remove();
            }, 400L);
        }
    }

    private void skySlam(Player t) {
        brain.busy(SLAM_WARN + 4);
        brain.startCooldown(Ability.SKY_SLAM);
        boss.getPathfinder().stopPathfinding();

        Location lock = ground(t.getLocation());
        World w = lock.getWorld();
        w.playSound(boss.getLocation(), Sound.ENTITY_WARDEN_ROAR, 2.0f, 0.8f);
        w.playSound(lock, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 2.0f, 0.5f);
        t.sendActionBar(Component.text("⚠ LE SOL !", NamedTextColor.DARK_RED));
        EndBossEffects.spikeTips(lock, SLAM_RADIUS, SLAM_WARN);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive()) {
                    cancel();
                    return;
                }
                ring(lock, SLAM_RADIUS, Color.RED, 1.5f, 48);
                ring(lock, SLAM_RADIUS * ticks / SLAM_WARN, Color.ORANGE, 1.1f, 32);
                if (ticks >= SLAM_WARN) {
                    cancel();
                    erupt(lock);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void erupt(Location lock) {
        World w = lock.getWorld();
        EndBossEffects.spikes(lock, SLAM_RADIUS);
        w.playSound(lock, Sound.BLOCK_POINTED_DRIPSTONE_BREAK, 3.0f, 0.5f);
        w.playSound(lock, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.2f);

        List<Player> launched = new ArrayList<>();
        for (Player p : w.getNearbyPlayers(lock, SLAM_RADIUS + 3)) {
            if (!brain.isValidTarget(p)) continue;
            Location pl = p.getLocation();
            if (Math.hypot(pl.getX() - lock.getX(), pl.getZ() - lock.getZ()) > SLAM_RADIUS) continue;
            if (Math.abs(pl.getY() - lock.getY()) > 3) continue;
            hurt(p, dmg(6));
            p.setVelocity(new Vector(0, 2.3, 0));
            launched.add(p);
        }
        if (launched.isEmpty()) return;

        brain.busy(SLAM_APEX + SLAM_HOVER + 30);
        Bukkit.getScheduler().runTaskLater(plugin, () -> strike(launched), SLAM_APEX);
    }

    private void strike(List<Player> launched) {
        launched.removeIf(p -> !brain.isValidTarget(p));
        if (!brain.isAlive()) {
            protect(launched);
            return;
        }
        if (launched.isEmpty()) return;

        Player top = launched.get(0);
        for (Player p : launched) {
            if (p.getLocation().getY() > top.getLocation().getY()) top = p;
        }
        Location above = top.getLocation().clone();
        for (int i = 0; i < 3 && above.clone().add(0, boss.getHeight() + 1, 0).getBlock().isPassable(); i++) {
            above.add(0, 1, 0);
        }

        EndBossEffects.blink(boss.getLocation());
        boss.setGravity(false);
        boss.teleport(above);
        boss.setVelocity(new Vector(0, 0, 0));
        EndBossEffects.blink(above);
        above.getWorld().playSound(above, Sound.ENTITY_ENDERMAN_TELEPORT, 2.5f, 0.5f);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            slamDown(launched, 20);
        }, SLAM_HOVER);
    }

    private void slamDown(List<Player> victims, double impactDamage) {
        boss.setGravity(true);
        if (!brain.isAlive()) {
            protect(victims);
            return;
        }
        boss.setVelocity(new Vector(0, -3.0, 0));
        boss.getWorld().playSound(boss.getLocation(), Sound.ITEM_MACE_SMASH_AIR, 3.0f, 0.5f);
        for (Player p : victims) dive(p, impactDamage);
        afterLanding(2, 40, () -> {
            Location land = boss.getLocation();
            smashFx(land);
            EndBossEffects.slamImpact(land, 6.0);
        });
    }

    private void protect(List<Player> players) {
        for (Player p : players) {
            if (p.isOnline()) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, false, false));
        }
    }

    private void dive(Player p, double impactDamage) {
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (!brain.isValidTarget(p) || ticks > 40) {
                    cancel();
                    return;
                }
                p.setFallDistance(0f);
                if (ticks > 1 && (p.isOnGround() || p.isInWater())) {
                    cancel();
                    Location at = p.getLocation();
                    hurt(p, dmg(impactDamage));
                    knock(p, at, 0.6);
                    at.getWorld().playSound(at, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 3.0f, 0.7f);
                    EndBossEffects.slamImpact(at, 4.0);
                    return;
                }
                p.setVelocity(new Vector(0, -SLAM_FALL_SPEED, 0));
                EndBossEffects.burst(p.getLocation(), Material.DRIPSTONE_BLOCK, 2);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void skyRapture() {
        Location base = boss.getLocation().clone();
        World w = base.getWorld();
        int height = 0;
        while (height < RAPTURE_HEIGHT && base.clone().add(0, height + 3, 0).getBlock().isPassable()) height++;
        if (height < 12) return;

        int takeAt = RAPTURE_RISE + RAPTURE_PULL;
        int dropAt = takeAt + RAPTURE_HOLD;
        brain.busy(dropAt + 30);
        brain.startCooldown(Ability.SKY_RAPTURE);
        boss.getPathfinder().stopPathfinding();
        boss.setGravity(false);
        boss.setVelocity(new Vector(0, 0, 0));

        Location sky = base.clone().add(0, height, 0);
        Location heart = sky.clone().add(0, 1, 0);
        w.playSound(base, Sound.ENTITY_WITHER_SPAWN, 4.0f, 0.5f);
        for (Player p : targetsIn(base)) {
            p.sendActionBar(Component.text("⚠ LE CIEL T'APPELLE !", NamedTextColor.DARK_PURPLE));
        }

        List<Player> caught = new ArrayList<>();
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive()) {
                    boss.setGravity(true);
                    protect(caught);
                    cancel();
                    return;
                }
                if (ticks <= RAPTURE_RISE) {
                    double p = Math.sin(Math.PI / 2 * ticks / RAPTURE_RISE);
                    boss.teleport(base.clone().add(0, sky.getY() * p - base.getY() * p, 0));
                } else if (ticks < takeAt) {
                    if (ticks % 2 == 0) {
                        for (Player p : targetsIn(base)) EndBossEffects.pullStream(p.getLocation(), heart);
                    }
                    if (ticks % 10 == 0) w.playSound(sky, Sound.BLOCK_BEACON_AMBIENT, 4.0f, 0.5f);
                } else if (ticks == takeAt) {
                    caught.addAll(targetsIn(base));
                    if (caught.isEmpty()) {
                        boss.setGravity(true);
                        cancel();
                        return;
                    }
                    for (int i = 0; i < caught.size(); i++) pull(caught.get(i), sky, 2 * Math.PI * i / caught.size());
                    EndBossEffects.orbit(heart, 10.0, 24, RAPTURE_HOLD);
                    w.playSound(sky, Sound.ENTITY_ENDERMAN_TELEPORT, 4.0f, 0.5f);
                } else if (ticks < dropAt) {
                    caught.removeIf(p -> !brain.isValidTarget(p));
                    for (Player p : caught) {
                        p.setFallDistance(0f);
                        p.setVelocity(new Vector(0, 0.04, 0));
                    }
                } else {
                    cancel();
                    slamDown(caught, 14);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private List<Player> targetsIn(Location center) {
        List<Player> found = new ArrayList<>();
        for (Player p : center.getWorld().getNearbyPlayers(center, RAPTURE_RANGE)) {
            if (brain.isValidTarget(p)) found.add(p);
        }
        return found;
    }

    private void pull(Player p, Location sky, double angle) {
        EndBossEffects.blink(p.getLocation());
        Location at = sky.clone().add(Math.cos(angle) * 6, 0, Math.sin(angle) * 6);
        at.setDirection(sky.toVector().subtract(at.toVector()));
        p.teleport(at);
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(0f);
        EndBossEffects.blink(at);
    }

    private void inventoryScramble(Player t) {
        if (!ScrambledInventories.scramble(t)) return;
        brain.busy(10);
        brain.startCooldown(Ability.INVENTORY_SCRAMBLE);
        Location at = t.getLocation();
        at.getWorld().playSound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 2.0f, 0.5f);
        at.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 2.0f, 0.7f);
        t.sendActionBar(Component.text("⚠ TON INVENTAIRE EST EN DÉSORDRE !", NamedTextColor.LIGHT_PURPLE));
        EndBossEffects.blink(at);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (t.isOnline()) ScrambledInventories.restore(t);
        }, SCRAMBLE_TICKS);
    }

    private void itemTheft(Player t) {
        ItemStack stolen = StolenItems.steal(t);
        if (stolen == null) return;
        brain.busy(THEFT_HOLD);
        brain.startCooldown(Ability.ITEM_THEFT);

        World w = boss.getWorld();
        Location start = t.getEyeLocation();
        w.playSound(start, Sound.ENTITY_ALLAY_ITEM_TAKEN, 2.0f, 0.5f);
        w.playSound(boss.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 2.0f, 0.5f);
        t.sendActionBar(Component.text("⚠ Il t'a volé ton objet !", NamedTextColor.LIGHT_PURPLE));
        EndBossEffects.blink(t.getLocation());

        ItemDisplay disp = w.spawn(start, ItemDisplay.class, d -> {
            d.setItemStack(stolen);
            d.setGlowing(true);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            Transformation tr = d.getTransformation();
            tr.getScale().set(1.8f);
            d.setTransformation(tr);
        });

        new BukkitRunnable() {
            final Location pos = start.clone();
            int ticks = 0;

            private void finish() {
                disp.remove();
                cancel();
            }

            private void moveTo(Vector step) {
                pos.add(step);
                pos.setYaw(ticks * 35f);
                disp.teleport(pos);
            }

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive() || !StolenItems.isHolding(t)) {
                    if (t.isOnline()) StolenItems.giveBack(t);
                    finish();
                    return;
                }
                if (ticks <= THEFT_HOLD) {
                    Location above = boss.getEyeLocation().add(0, 1.8, 0);
                    double pull = ticks <= THEFT_PULL ? 1.0 / (THEFT_PULL - ticks + 1) : 1.0;
                    moveTo(above.toVector().subtract(pos.toVector()).multiply(pull));
                    w.spawnParticle(Particle.ENCHANT, pos, 6, 0.3, 0.3, 0.3, 0.5);
                    if (ticks == THEFT_HOLD) w.playSound(pos, Sound.ITEM_TRIDENT_THROW, 2.0f, 0.5f);
                    return;
                }

                Vector to = t.getEyeLocation().toVector().subtract(pos.toVector());
                if (to.length() < 1.7) {
                    StolenItems.giveBack(t);
                    if (brain.isValidTarget(t)) {
                        hurt(t, dmg(12));
                        knock(t, pos, 0.7);
                    }
                    w.spawnParticle(Particle.EXPLOSION, pos, 1);
                    w.playSound(pos, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.6f, 0.7f);
                    EndBossEffects.burst(pos, Material.AMETHYST_BLOCK, 6);
                    finish();
                    return;
                }
                if (ticks > THEFT_HOLD + THEFT_MAX_FLIGHT) {
                    StolenItems.giveBack(t);
                    finish();
                    return;
                }
                moveTo(to.normalize().multiply(1.4));
                w.spawnParticle(Particle.CRIT, pos, 3, 0.1, 0.1, 0.1, 0.05);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void blockBarrage(Player t) {
        int phase = brain.phases().current();
        int count = 3 + phase;
        int gap = 14 - phase * 2;
        int launchAt = (count - 1) * ROCK_STAGGER + ROCK_RISE + 4;
        int ttl = launchAt + count * gap + ROCK_MAX_FLIGHT;

        brain.busy(launchAt + count * gap);
        brain.startCooldown(Ability.BLOCK_BARRAGE);
        boss.getPathfinder().stopPathfinding();
        boss.setVelocity(new Vector(0, 0, 0));

        Location center = boss.getLocation().clone();
        Vector to = t.getLocation().toVector().subtract(center.toVector());
        double facing = Math.atan2(to.getZ(), to.getX());
        double reach = 2.4 + boss.getWidth();

        boss.getWorld().playSound(center, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 2.0f, 0.5f);
        t.sendActionBar(Component.text("⚠ BLOCS !", NamedTextColor.GOLD));

        List<Rock> rocks = new ArrayList<>();
        new BukkitRunnable() {
            int ticks = 0;
            int thrown = 0;

            private void abort() {
                for (int i = thrown; i < rocks.size(); i++) rocks.get(i).remove();
                cancel();
            }

            @Override
            public void run() {
                if (!brain.isAlive()) {
                    abort();
                    return;
                }
                if (rocks.size() < count && ticks % ROCK_STAGGER == 0) {
                    double angle = facing + (rocks.size() - (count - 1) / 2.0) * 0.55;
                    rocks.add(raiseRock(center, angle, reach, ttl));
                }
                if (ticks >= launchAt && (ticks - launchAt) % gap == 0) {
                    Player aim = brain.isValidTarget(t) ? t : brain.target();
                    if (aim == null) {
                        abort();
                        return;
                    }
                    launchRock(rocks.get(thrown++), aim);
                    if (thrown == count) {
                        cancel();
                        return;
                    }
                }
                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Rock raiseRock(Location center, double angle, double reach, int ttl) {
        World w = center.getWorld();
        double x = center.getX() + Math.cos(angle) * reach;
        double z = center.getZ() + Math.sin(angle) * reach;
        Block ground = EndBossEffects.surface(w, x, center.getY(), z);
        Material type = EndBossEffects.material(ground != null ? ground.getType() : Material.AIR);
        float size = 1.0f + rng.nextFloat() * 0.5f;

        double groundY = ground != null ? ground.getY() + 1.0 : center.getY();
        Location buried = new Location(w, x, groundY - size, z);
        Location hover = new Location(w, x, center.getY() + 2.2 + rng.nextDouble() * 1.2, z);

        w.playSound(buried, Sound.BLOCK_STONE_BREAK, 1.5f, 0.6f);
        w.spawnParticle(Particle.BLOCK, x, groundY, z, 15, 0.5, 0.1, 0.5, 0.05, type.createBlockData());
        return new Rock(hover, type, size, EndBossEffects.raiseRock(buried, hover, type, size, ROCK_RISE, ttl));
    }

    private void launchRock(Rock rock, Player t) {
        World w = rock.pos().getWorld();
        Location base = rock.pos().clone();
        Vector offset = new Vector(0, rock.size() / 2.0, 0);
        Location start = base.clone().add(offset);

        double flight = t.getLocation().distance(start) / ROCK_SPEED;
        Vector dir = brain.predict(t, flight).add(0, 0.9, 0).toVector().subtract(start.toVector());
        if (dir.lengthSquared() < 0.01) dir = new Vector(0, -1, 0);
        Vector vel = dir.normalize().multiply(ROCK_SPEED);

        w.playSound(start, Sound.ITEM_TRIDENT_THROW, 1.6f, 0.5f);
        EndBossEffects.spin(rock.crate(), 20);

        new BukkitRunnable() {
            int ticks = 0;

            private void shatter(Location at) {
                w.playSound(at, Sound.BLOCK_DEEPSLATE_BREAK, 2.0f, 0.6f);
                w.spawnParticle(Particle.BLOCK, at, 25, 0.5, 0.5, 0.5, 0.1, rock.type().createBlockData());
                EndBossEffects.burst(at, rock.type(), 6);
                rock.remove();
                cancel();
            }

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive() || ticks > ROCK_MAX_FLIGHT) {
                    rock.remove();
                    cancel();
                    return;
                }
                base.add(vel);
                if (rock.crate() != null) rock.crate().handle().teleport(base);
                Location c = base.clone().add(offset);
                w.spawnParticle(Particle.BLOCK, c, 3, 0.3, 0.3, 0.3, 0, rock.type().createBlockData());

                if (!c.getBlock().isPassable()) {
                    shatter(c);
                    return;
                }
                for (Player p : w.getNearbyPlayers(c, 1.3 + rock.size() / 2.0)) {
                    if (!brain.isValidTarget(p)) continue;
                    hurt(p, dmg(11));
                    knock(p, c, 0.8);
                    shatter(c);
                    return;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Pluie de météores : le premier vise la position prédite, les autres bouchent les échappatoires. */
    private void meteors(Player t) {
        brain.busy(26);
        brain.startCooldown(Ability.METEORS);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.6f);
        t.sendActionBar(Component.text("☄ MÉTÉORES !", NamedTextColor.GOLD));
        for (int i = 0; i < 6; i++) {
            Location base = i == 0
                    ? brain.predict(t, 16)
                    : t.getLocation().clone().add((rng.nextDouble() - 0.5) * 14, 0, (rng.nextDouble() - 0.5) * 14);
            Location g = ground(base);
            Bukkit.getScheduler().runTaskLater(plugin, () -> meteor(g), i * 7L);
        }
    }

    private void meteor(Location g) {
        if (!brain.isAlive()) return;
        World w = g.getWorld();
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (!brain.isAlive()) {
                    cancel();
                    return;
                }
                if (ticks < 26) {
                    if (ticks % 2 == 0) ring(g, 3.2, Color.ORANGE, 1.4f, 24);
                    if (ticks == METEOR_FALL_START) {
                        EndBossEffects.meteor(g, (26 - ticks) * METEOR_SPEED, 26 - ticks);
                    }
                    if (ticks > METEOR_FALL_START) {
                        double height = (26 - ticks) * METEOR_SPEED;
                        w.spawnParticle(Particle.FLAME, g.getX(), g.getY() + height, g.getZ(), 6, 0.25, 0.25, 0.25, 0.01);
                    }
                } else {
                    cancel();
                    w.spawnParticle(Particle.EXPLOSION_EMITTER, g, 1);
                    w.spawnParticle(Particle.FLAME, g, 40, 1.5, 0.3, 1.5, 0.08);
                    w.playSound(g, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.8f);
                    EndBossEffects.landing(g, 3.4);
                    impact(g, 3.4, 17.0, 0.9);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
