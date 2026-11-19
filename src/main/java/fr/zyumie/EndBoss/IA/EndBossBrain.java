package fr.zyumie.EndBoss.IA;

import fr.zyumie.EndBoss.EndBossBar;
import fr.zyumie.EndBoss.EndBossRegistry;
import fr.zyumie.EndBoss.Main;
import fr.zyumie.EndBoss.Listener.EndBossPhaseManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Cerveau d'un boss. Un cerveau = un boss, tické par EndBossAIController.
 *
 * Pipeline à chaque tick :
 *   1. Perception  : choix de la cible via la table de menace (dégâts subis, distance, vue, vie basse, hystérésis)
 *   2. Apprentissage : profil du joueur (kiting, pillar, élytra) qui oriente les décisions
 *   3. Réflexes    : esquive des projectiles, bouclier
 *   4. Décision    : chaque capacité reçoit un score d'utilité selon la situation, la meilleure part (avec anti-répétition)
 *   5. Mouvement   : pathfinding, orbite, déblayage, ponts, pillar, anti-pillar
 */
public class EndBossBrain {

    public final JavaPlugin plugin;
    public final WitherSkeleton boss;

    private final ThreatTable threat = new ThreatTable();
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final Map<UUID, Double> damageDealt = new HashMap<>();
    private final Map<Ability, Long> cooldowns = new EnumMap<>(Ability.class);
    private final List<Entity> minions = new ArrayList<>();
    private final Random rng = new Random();
    private final int interval;
    private final String profileName;

    private final EndBossBar bar;
    private final EndBossMovement movement;
    private final EndBossCombat combat;
    private final EndBossScaffold scaffold;
    private final EndBossPhaseManager phases;
    private final EndBossWater water;

    private Player target;
    private long now, busyUntil, nextDecision, shieldUntil, shieldDisabledUntil;
    private long lastHurt = -1000;
    private long idleTicks;
    private int shieldBlocks;
    private int totemsLeft;
    private boolean shieldWasUp, shutdown, cinematic;
    private Ability lastAbility;
    private Location lastSafe;

    /** Contexte de décision, calculé une fois par décision. */
    private record Ctx(double dist, boolean los, double dy, boolean ground, double kite, double pillar,
                       double aerial, int crowd, boolean hurt, boolean bow, int phase,
                       boolean stationary, boolean unreachable) {}

    public EndBossBrain(JavaPlugin plugin, WitherSkeleton boss) {
        this.plugin = plugin;
        this.boss = boss;
        this.profileName = boss.getPersistentDataContainer()
                .getOrDefault(EndBossRegistry.profileKey(), PersistentDataType.STRING, "normal");

        // Totems : lus sur l'entité pour ne pas se réinitialiser quand le chunk se recharge
        Integer stored = boss.getPersistentDataContainer().get(EndBossRegistry.totemKey(), PersistentDataType.INTEGER);
        if (stored == null) {
            stored = cfg().getBoolean("boss.totems.enabled", false) ? cfg().getInt("boss.totems.count", 3) : 0;
            boss.getPersistentDataContainer().set(EndBossRegistry.totemKey(), PersistentDataType.INTEGER, stored);
        }
        this.totemsLeft = stored;
        this.interval = Math.max(1, plugin.getConfig().getInt("boss.ai.tick-interval", 2));
        this.bar = new EndBossBar("EndBoss", cfg().getDouble("boss.ai.bar-range", 64.0));
        this.scaffold = new EndBossScaffold(this);
        this.movement = new EndBossMovement(this);
        this.combat = new EndBossCombat(this);
        this.water = new EndBossWater(this);
        this.phases = new EndBossPhaseManager(this);
        this.lastSafe = boss.getLocation().clone();
        this.phases.update(); // applique la phase initiale : stats, nom, capacités
    }

    // ------------------------------------------------------------------ accès

    /** Config du profil de ce boss (Boss.yml, Impossible.yml...). */
    public FileConfiguration cfg() { return ((Main) plugin).profile(profileName); }
    public int totemsLeft() { return totemsLeft; }
    public EndBossPhaseManager phases() { return phases; }
    public EndBossMovement movement() { return movement; }
    public EndBossCombat combat() { return combat; }
    public EndBossScaffold scaffold() { return scaffold; }
    public EndBossWater water() { return water; }
    public Player target() { return target; }
    public long now() { return now; }
    public double skill() { return cfg().getDouble("boss.ai.skill", 0.75); }
    public boolean isAlive() { return !shutdown && boss.isValid() && !boss.isDead(); }
    public boolean isBusy() { return now < busyUntil; }
    public void busy(int ticks) { busyUntil = Math.max(busyUntil, now + ticks); }

    public double maxHealth() {
        AttributeInstance a = boss.getAttribute(Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : 800.0;
    }

    public double healthFraction() {
        return Math.max(0.0, Math.min(1.0, boss.getHealth() / maxHealth()));
    }

    public static void setAttribute(LivingEntity e, Attribute attribute, double value) {
        AttributeInstance inst = e.getAttribute(attribute);
        if (inst != null) inst.setBaseValue(value);
    }

    public PlayerProfile profile(Player p) {
        return profiles.computeIfAbsent(p.getUniqueId(), id -> new PlayerProfile());
    }

    public boolean isValidTarget(Player p) {
        if (p == null || !p.isOnline() || p.isDead() || p.isInvulnerable()) return false;
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    /** Position estimée du joueur dans {@code ticksAhead} ticks (vitesse réelle observée). */
    public Location predict(Player p, double ticksAhead) {
        Vector v = profile(p).velocity();
        v.setY(v.getY() * 0.3);
        double len = v.length();
        if (len * ticksAhead > 10.0) v.multiply(10.0 / (len * ticksAhead));
        return p.getLocation().clone().add(v.multiply(ticksAhead));
    }

    // ------------------------------------------------------------------ cooldowns

    public boolean ready(Ability a) {
        return now >= cooldowns.getOrDefault(a, 0L);
    }

    public void startCooldown(Ability a) {
        double jitter = 0.9 + rng.nextDouble() * 0.2;
        cooldowns.put(a, now + Math.round(a.cooldown * phases.cooldownMultiplier() * water.cooldownMultiplier() * jitter));
    }

    // ------------------------------------------------------------------ minions

    public int minionCount() {
        minions.removeIf(e -> !e.isValid());
        return minions.size();
    }

    public void addMinion(Entity e) {
        minions.add(e);
    }

    // ------------------------------------------------------------------ bouclier

    public boolean isShielding() { return now < shieldUntil; }
    public boolean canShield() { return now >= shieldDisabledUntil && !isShielding(); }

    public void raiseShield(int ticks) {
        shieldUntil = now + ticks;
        shieldWasUp = true;
        shieldBlocks = 0;
        boss.getWorld().playSound(boss.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.2f, 0.7f);
    }

    /** Une hache brise la garde : le boss est étourdi, c'est la fenêtre pour le punir. */
    public void disableShield(int ticks) {
        shieldUntil = now;
        shieldWasUp = false;
        shieldBlocks = 0;
        shieldDisabledUntil = now + ticks;
        boss.getWorld().playSound(boss.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.5f, 0.8f);
        boss.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, false));
        movement.onHurt();
    }

    public void onShieldBlock() {
        shieldBlocks++;
    }

    public Vector facing() {
        Vector d = boss.getEyeLocation().getDirection().setY(0);
        if (d.lengthSquared() < 0.0001) {
            double yaw = Math.toRadians(boss.getLocation().getYaw());
            d = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        return d.normalize();
    }

    public boolean isInFront(Location from) {
        Vector to = from.toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (to.lengthSquared() < 0.01) return true;
        return facing().dot(to.normalize()) > 0.1;
    }

    private void endShield() {
        shieldWasUp = false;
        if (shieldBlocks > 0 && target != null && isAlive()) combat.counterStrike(target); // contre-attaque après une garde réussie
        shieldBlocks = 0;
    }

    private void shieldParticles() {
        Location base = boss.getLocation();
        Location front = base.clone().add(facing().multiply(boss.getWidth() + 0.3)).add(0, boss.getHeight() * 0.55, 0);
        boss.getWorld().spawnParticle(Particle.ENCHANTED_HIT, front, 4, 0.25, 0.4, 0.25, 0.02);
    }

    // ------------------------------------------------------------------ événements

    public void onDamaged(Player attacker, double dmg, boolean melee, boolean blocked) {
        lastHurt = now;
        threat.add(attacker.getUniqueId(), dmg * 2.0 + 5.0);
        if (!blocked) damageDealt.merge(attacker.getUniqueId(), dmg, Double::sum);
        if (melee) profile(attacker).recordMelee();
        movement.onHurt();

        // réflexe : se couvrir quand on se fait frapper
        if (canShield() && !isBusy() && phases.abilities().contains(Ability.SHIELD_UP) && ready(Ability.SHIELD_UP)
                && rng.nextDouble() < 0.28 * (0.5 + skill())) {
            combat.execute(Ability.SHIELD_UP, attacker);
        }
    }

    public List<String> topDamagers(int n) {
        List<String> lines = new ArrayList<>();
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(damageDealt.entrySet());
        sorted.sort(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()));
        for (int i = 0; i < Math.min(n, sorted.size()); i++) {
            Map.Entry<UUID, Double> e = sorted.get(i);
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            lines.add("§7#" + (i + 1) + " §f" + (name != null ? name : "???") + " §8- §c" + Math.round(e.getValue()) + " dégâts");
        }
        return lines;
    }

    /**
     * Totem d'immortalité : annule un coup mortel, soigne le boss et le ramène à la phase
     * {@code boss.totems.revive-to-phase} (ex. 3 vies). Retourne false s'il n'y a plus de totem.
     */
    public boolean useTotem() {
        if (totemsLeft <= 0 || !cfg().getBoolean("boss.totems.enabled", false)) return false;
        totemsLeft--;
        boss.getPersistentDataContainer().set(EndBossRegistry.totemKey(), PersistentDataType.INTEGER, totemsLeft);

        int phase = Math.max(1, Math.min(3, cfg().getInt("boss.totems.revive-to-phase", 2)));
        boss.setHealth(Math.max(1.0, Math.min(maxHealth(), maxHealth() * phases.reviveFraction(phase))));
        phases.forcePhase(phase);

        // on repart de zéro : plus de garde, cooldowns remis à neuf
        cooldowns.clear();
        shieldUntil = now;
        shieldWasUp = false;
        shieldBlocks = 0;

        Location loc = boss.getLocation();
        boss.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, loc.clone().add(0, 1, 0), 200, 1.2, 1.5, 1.2, 0.6);
        boss.getWorld().playSound(loc, Sound.ITEM_TOTEM_USE, 3.0f, 0.7f);

        String msg = ChatColor.translateAlternateColorCodes('&', cfg()
                .getString("boss.totems.message", "&6&l✦ TOTEM D'IMMORTALITÉ &e({left} restant(s))")
                .replace("{left}", String.valueOf(totemsLeft)));
        for (Player p : boss.getWorld().getNearbyPlayers(loc, cfg().getDouble("boss.ai.bar-range", 64.0))) {
            p.sendTitle(msg, "", 5, 50, 15);
        }
        return true;
    }

    /** Chute dans le vide (ponts détruits, etc.) : retour au dernier point sûr. */
    public void rescue() {
        if (lastSafe == null) return;
        boss.teleport(lastSafe);
        boss.setVelocity(new Vector(0, 0, 0));
        boss.getWorld().playSound(lastSafe, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.6f);
    }

    public void beginCinematic(int ticks) {
        cinematic = true;
        busy(ticks);
        boss.setInvulnerable(true);
        boss.setAI(false);
        boss.setGravity(false);
    }

    public void endCinematic() {
        if (!cinematic) return;
        cinematic = false;
        boss.setInvulnerable(false);
        boss.setAI(true);
        boss.setGravity(true);
    }

    public void shutdown() {
        endCinematic();
        shutdown = true;
        bar.remove();
        boss.setGravity(true);
        for (Entity m : minions) {
            if (m.isValid()) m.remove();
        }
        minions.clear();
    }

    // ------------------------------------------------------------------ boucle principale

    public void tick() {
        now += interval;
        if (!isAlive()) return;
        if (boss.isOnGround()) lastSafe = boss.getLocation().clone();
        water.tick();

        threat.decay();
        target = pickTarget();
        boss.setTarget(target);
        phases.update();
        bar.update(boss, phases.current(), phases.displayName(), maxHealth());
        phases.updateOverheadName();
        regen();

        if (target == null) {
            idle();
            return;
        }
        idleTicks = 0;

        PlayerProfile prof = profile(target);
        prof.observe(target, boss.getLocation(), now, interval);

        double dist = boss.getLocation().distance(target.getLocation());
        boolean los = boss.hasLineOfSight(target);

        if (shieldWasUp && now >= shieldUntil) endShield();
        if (isShielding()) shieldParticles();

        // anti-pillar : on bloque l'échappée verticale d'un joueur qui monte une tour
        if (prof.pillar() > 0.4 && dist < 7) scaffold.capHead(target);

        movement.dodgeProjectiles();

        if (!isBusy() && now >= nextDecision) decide(prof, dist, los);

        movement.tick(target, dist, los, prof);
    }

    private void idle() {
        movement.idle();
        idleTicks += interval;
        if (idleTicks > 100) threat.clear();
    }

    /**
     * Régénération : légère en phase 2/3 (reste tuable si on frappe en continu) et rapide
     * quand personne ne l'a touché depuis quelques secondes (il récupère si on le laisse tranquille).
     */
    private void regen() {
        if (!cfg().getBoolean("boss.regeneration.enabled", false)) return;
        double max = maxHealth();
        double hp = boss.getHealth();
        if (hp >= max) return;
        double secs = interval / 20.0;
        double amount = phases.regenPerSecond() * secs;
        double restDelay = cfg().getDouble("boss.ai.rest-regen-delay", 8.0) * 20.0;
        if (now - lastHurt > restDelay) {
            amount += max * cfg().getDouble("boss.ai.rest-regen-percent", 3.0) / 100.0 * secs;
        }
        if (amount > 0) boss.setHealth(Math.min(max, hp + amount));
    }

    private Player pickTarget() {
        double range = cfg().getDouble("boss.ai.sight-range", 48.0);
        Player best = null;
        double bestScore = -1e9;
        for (Player p : boss.getWorld().getNearbyPlayers(boss.getLocation(), range)) {
            if (!isValidTarget(p)) continue;
            double dist = p.getLocation().distance(boss.getLocation());
            double score = threat.get(p.getUniqueId()) * 1.5;
            score += (range - dist) * 0.6;
            if (boss.hasLineOfSight(p)) score += 5.0;
            score += (20.0 - Math.min(20.0, p.getHealth())) * 0.4;  // achève les joueurs affaiblis
            if (p.equals(target)) score += 6.0;                       // hystérésis : pas de ping-pong entre cibles
            if (p.isBlocking()) score -= 1.5;
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ décision

    private void decide(PlayerProfile prof, double dist, boolean los) {
        boolean adaptive = cfg().getBoolean("boss.ai.adaptive-learning", true);
        Location bl = boss.getLocation();
        Location tl = target.getLocation();

        int crowd = 0;
        for (Player p : boss.getWorld().getNearbyPlayers(bl, 9)) {
            if (isValidTarget(p)) crowd++;
        }
        Material held = target.getInventory().getItemInMainHand().getType();
        boolean bow = target.isHandRaised()
                && (held == Material.BOW || held == Material.CROSSBOW || held == Material.TRIDENT);

        Ctx c = new Ctx(dist, los, tl.getY() - bl.getY(), boss.isOnGround() || water.active(),
                adaptive ? prof.kite() : 0, adaptive ? prof.pillar() : 0, adaptive ? prof.aerial() : 0,
                crowd, now - lastHurt < 50, bow, phases.current(), prof.isStationary(), movement.isUnreachable());

        Set<Ability> options = EnumSet.copyOf(phases.abilities());
        if (water.whirlpoolEnabled()) options.add(Ability.WHIRLPOOL);

        Ability best = null;
        double bestScore = 18.0;
        for (Ability a : options) {
            if (!ready(a)) continue;
            double s = score(a, c);
            if (a == lastAbility) s *= 0.55;               // anti-répétition : on varie les attaques
            s *= 0.85 + rng.nextDouble() * 0.30;           // un peu d'imprévisibilité
            if (s > bestScore) {
                bestScore = s;
                best = a;
            }
        }

        if (best == null) {
            nextDecision = now + 6;
            return;
        }
        lastAbility = best;
        int gap = c.phase() >= 3 ? 24 : c.phase() == 2 ? 34 : 46;   // plus la phase est avancée, moins il respire
        nextDecision = now + Math.round((gap + rng.nextInt(15)) * water.cooldownMultiplier());
        combat.execute(best, target);
    }

    private double score(Ability a, Ctx c) {
        return switch (a) {
            case LEAP -> (c.ground() && c.dist() > 7 && c.dist() < 26)
                    ? 30 + (c.dist() - 7) * 1.8 + c.kite() * 30 + (c.los() ? 0 : -15) + (movement.isStuck() ? 20 : 0)
                    : 0;
            case DASH_STRIKE -> (c.ground() && c.los() && c.dist() > 3.2 && c.dist() < 9)
                    ? 42 + c.kite() * 12
                    : 0;
            case SWORD_THROW -> (c.los() && c.dist() > 6 && c.dist() < 32)
                    ? 26 + c.kite() * 40 + (c.dist() > 14 ? 14 : 0) + (c.bow() ? 10 : 0)
                    : 0;
            case SHIELD_UP -> (canShield() && c.dist() < 9 && (c.bow() || c.hurt()))
                    ? (c.bow() ? 70 : 38)
                    : 0;
            case BLINK -> blinkScore(c);
            case MACE_SMASH -> (c.ground() && c.dist() > 2 && c.dist() < 16)
                    ? 34 + c.phase() * 7 + (c.stationary() ? 16 : 0) + (c.dist() < 8 ? 8 : 0)
                    : 0;
            case SHOCKWAVE -> (c.ground() && (c.crowd() >= 2 || c.dist() < 5))
                    ? 36 + c.crowd() * 12 + (c.hurt() ? 10 : 0)
                    : 0;
            case SUMMON -> minionCount() == 0 ? 30 : 0;
            case METEORS -> (c.dist() > 5 && c.dist() < 32)
                    ? 36 + c.kite() * 25 + c.aerial() * 25 + (c.stationary() ? 14 : 0)
                    : 0;
            case BLOCK_BARRAGE -> (c.ground() && c.los() && c.dist() > 5 && c.dist() < 28)
                    ? 38 + c.kite() * 20 + (c.stationary() ? 10 : 0)
                    : 0;
            case SKY_SLAM -> (c.ground() && Math.abs(c.dy()) < 3 && c.dist() > 2 && c.dist() < 24)
                    ? 62 + c.phase() * 4 + (c.stationary() ? 14 : 0)
                    : 0;
            case ITEM_THEFT -> (c.los() && c.dist() < 18
                    && !target.getInventory().getItemInMainHand().getType().isAir()
                    && rng.nextDouble() < (c.phase() >= 3 ? 0.12 : 0.04))
                    ? 90
                    : 0;
            case SKY_RAPTURE -> (c.ground() && rng.nextDouble() < 0.05) ? 95 : 0;
            case INVENTORY_SCRAMBLE -> (c.los() && c.dist() < 24 && rng.nextDouble() < 0.08) ? 88 : 0;
            case WHIRLPOOL -> (water.whirlpoolEnabled() && target.isInWater() && c.dist() < water.whirlpoolRadius())
                    ? 78 + c.phase() * 4
                    : 0;
        };
    }

    private double blinkScore(Ctx c) {
        double s = 0;
        if (c.unreachable() || movement.isStuck()) s = 70;                    // coincé : il se téléporte
        if (c.dist() > 28) s = Math.max(s, 55);                               // cible trop loin
        if (c.kite() > 0.7 && c.dist() > 12) s = Math.max(s, 50);            // joueur qui kite
        if (c.pillar() > 0.5 && c.dy() > 3) s = Math.max(s, 60);             // joueur perché sur une tour
        if (s == 0 && c.phase() >= 2 && c.dist() > 8 && c.dist() < 22 && rng.nextDouble() < 0.07) s = 28; // embuscade
        return s;
    }
}
