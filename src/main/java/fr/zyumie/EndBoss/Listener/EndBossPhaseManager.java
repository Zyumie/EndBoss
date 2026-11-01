package fr.zyumie.EndBoss.Listener;

import fr.zyumie.EndBoss.IA.Ability;
import fr.zyumie.EndBoss.IA.EndBossEffects;
import fr.zyumie.EndBoss.IA.EndBossBrain;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Phases du boss (pilotées par Boss.yml). Chaque phase définit : échelle, vitesse, dégâts,
 * vitesse de recharge, suffixe de nom et liste des capacités débloquées.
 * Le passage à une phase supérieure déclenche une transition (invulnérabilité courte, onde de choc, titre).
 */
public class EndBossPhaseManager {

    private static final double[] DEF_THRESHOLD = {0, 1.0, 0.66, 0.33};
    private static final double[] DEF_SCALE = {0, 1.0, 1.35, 1.75};
    private static final double[] DEF_SPEED = {0, 1.0, 1.12, 1.25};
    private static final double[] DEF_DAMAGE = {0, 1.0, 1.25, 1.5};
    private static final double[] DEF_COOLDOWN = {0, 1.0, 0.85, 0.65};
    private static final String[] DEF_SUFFIX = {
            "", " &7[Phase 1 - Éveil]", " &e[Phase 2 - Courroux]", " &4[Phase 3 - Calamité Finale]"
    };
    private static final String[][] DEF_ABILITIES = {
            {},
            {"LEAP", "DASH_STRIKE", "SWORD_THROW", "SHIELD_UP", "BLINK", "BLOCK_BARRAGE"},
            {"LEAP", "DASH_STRIKE", "SWORD_THROW", "SHIELD_UP", "BLINK", "MACE_SMASH", "SHOCKWAVE", "SUMMON", "BLOCK_BARRAGE",
                    "SKY_SLAM", "ITEM_THEFT", "SKY_RAPTURE", "INVENTORY_SCRAMBLE"},
            {"LEAP", "DASH_STRIKE", "SWORD_THROW", "SHIELD_UP", "BLINK", "MACE_SMASH", "SHOCKWAVE", "SUMMON", "METEORS", "BLOCK_BARRAGE",
                    "SKY_SLAM", "ITEM_THEFT", "SKY_RAPTURE", "INVENTORY_SCRAMBLE"}
    };

    private final EndBossBrain brain;
    private final Set<Ability> abilities = EnumSet.noneOf(Ability.class);
    private int current = 0;
    private static final double[] DEF_REGEN = {0, 0.0, 1.5, 2.5}; // PV par seconde
    private double speedMult = 1.0, damageMult = 1.0, cooldownMult = 1.0, regen = 0.0;
    private String suffix = "";
    private String lastOverhead = "";

    public EndBossPhaseManager(EndBossBrain brain) {
        this.brain = brain;
    }

    public int current() { return current; }
    public Set<Ability> abilities() { return abilities; }
    public double speedMultiplier() { return speedMult; }
    public double damageMultiplier() { return damageMult; }
    public double cooldownMultiplier() { return cooldownMult; }
    public double regenPerSecond() { return regen; }

    /** Au-dessus de la tête : cœur + nombre de cœurs restants (nom et phase restent dans la BossBar). */
    public void updateOverheadName() {
        String fmt = brain.cfg().getString("boss.display.overhead-format", "&c❤ &f{hearts}");
        double hp = brain.boss.getHealth();
        String s = fmt.replace("{hearts}", String.valueOf((int) Math.ceil(hp / 2.0)))
                .replace("{hp}", String.valueOf((int) Math.ceil(hp)))
                .replace("{totems}", String.valueOf(brain.totemsLeft()));
        s = ChatColor.translateAlternateColorCodes('&', s);
        if (!s.equals(lastOverhead)) {
            lastOverhead = s;
            brain.boss.setCustomName(s);
            brain.boss.setCustomNameVisible(true);
        }
    }

    public String displayName() {
        String base = brain.cfg().getString("boss.name", "&4EndBoss");
        String name = ChatColor.translateAlternateColorCodes('&', base) + suffix;
        int totems = brain.totemsLeft();
        if (totems > 0 && brain.cfg().getBoolean("boss.totems.display", true)) {
            String format = brain.cfg().getString("boss.totems.display-format", " &6[Totems : {left}]");
            name += ChatColor.translateAlternateColorCodes('&', format.replace("{left}", String.valueOf(totems)));
        }
        return name;
    }

    /** Impose une phase (totem d'immortalité) avec la transition complète. */
    public void forcePhase(int phase) {
        enter(phase, true);
    }

    /** Fraction de vie au milieu de la plage de la phase : là où le boss revient après un totem. */
    public double reviveFraction(int phase) {
        double top = threshold(phase);
        double bottom = phase >= 3 ? 0.0 : threshold(phase + 1);
        return (top + bottom) / 2.0;
    }

    /** Vérifie la phase selon les PV. Appelé à chaque tick du cerveau. */
    public void update() {
        double hp = brain.healthFraction();
        int wanted = hp <= threshold(3) ? 3 : hp <= threshold(2) ? 2 : 1;
        // Pas de retour en arrière en plein combat (la régénération ferait rejouer les transitions) :
        // on ne redescend de phase que si le boss est quasiment full vie.
        if (wanted < current && hp < 0.99) return;
        if (wanted != current) enter(wanted, current != 0 && wanted > current);
    }

    /** Relit la config pour la phase en cours (après /endboss reload). */
    public void refresh() {
        if (current != 0) enter(current, false);
    }

    private double threshold(int phase) {
        return brain.cfg().getDouble("boss.phases.phase-" + phase + ".health-threshold", DEF_THRESHOLD[phase]);
    }

    private void enter(int phase, boolean animate) {
        current = phase;
        FileConfiguration cfg = brain.cfg();
        String p = "boss.phases.phase-" + phase + ".";

        speedMult = cfg.getDouble(p + "speed-multiplier", DEF_SPEED[phase]);
        damageMult = cfg.getDouble(p + "damage-multiplier", DEF_DAMAGE[phase]);
        cooldownMult = cfg.getDouble(p + "cooldown-multiplier", DEF_COOLDOWN[phase]);
        regen = cfg.getDouble(p + "regen-per-second", DEF_REGEN[phase]);
        suffix = ChatColor.translateAlternateColorCodes('&', cfg.getString(p + "name-suffix", DEF_SUFFIX[phase]));

        abilities.clear();
        List<String> names = cfg.getStringList(p + "abilities");
        if (names.isEmpty()) names = Arrays.asList(DEF_ABILITIES[phase]);
        for (String n : names) {
            Ability a = Ability.parse(n);
            if (a != null) abilities.add(a);
        }

        applyStats(cfg, p);

        updateOverheadName();

        if (animate) transition(phase);
    }

    private void applyStats(FileConfiguration cfg, String p) {
        WitherSkeleton boss = brain.boss;
        EndBossBrain.setAttribute(boss, Attribute.MOVEMENT_SPEED, cfg.getDouble("boss.speed", 0.36) * speedMult);
        EndBossBrain.setAttribute(boss, Attribute.ATTACK_DAMAGE, cfg.getDouble("boss.damage", 10.0) * damageMult);
        EndBossBrain.setAttribute(boss, Attribute.SCALE, cfg.getDouble(p + "scale", DEF_SCALE[current]));
        EndBossBrain.setAttribute(boss, Attribute.FOLLOW_RANGE, cfg.getDouble("boss.ai.sight-range", 48.0) + 16.0);
        EndBossBrain.setAttribute(boss, Attribute.KNOCKBACK_RESISTANCE, 0.85);
        EndBossBrain.setAttribute(boss, Attribute.STEP_HEIGHT, 1.1);
        EndBossBrain.setAttribute(boss, Attribute.SAFE_FALL_DISTANCE, 64.0);
        brain.water().apply();
    }

    private void transition(int phase) {
        WitherSkeleton boss = brain.boss;
        World w = boss.getWorld();
        Location loc = boss.getLocation();

        brain.busy(60);
        boss.setInvulnerable(true);
        boss.setVelocity(new Vector(0, 0.7, 0));

        w.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 3.0f, 0.5f);
        w.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 1.5f, 0.7f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, loc.clone().add(0, 1, 0), 3, 1, 1, 1, 0);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, loc.clone().add(0, 1, 0), 120, 1.2, 1.5, 1.2, 0.4);
        EndBossEffects.eruption(loc, phase);

        double range = brain.cfg().getDouble("boss.ai.bar-range", 64.0);
        for (Player pl : w.getNearbyPlayers(loc, range)) {
            pl.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "PHASE " + phase, suffix.trim(), 5, 40, 10);
            if (pl.getLocation().distanceSquared(loc) < 100) {
                Vector push = pl.getLocation().toVector().subtract(loc.toVector()).setY(0);
                if (push.lengthSquared() < 0.01) push = new Vector(0, 0, 1);
                push.normalize().multiply(1.2).setY(0.6);
                pl.setVelocity(push);
            }
        }

        Bukkit.getScheduler().runTaskLater(brain.plugin, () -> boss.setInvulnerable(false), 60L);
    }
}
