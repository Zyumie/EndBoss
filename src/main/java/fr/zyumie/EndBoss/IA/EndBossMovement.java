package fr.zyumie.EndBoss.IA;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * Déplacements du boss :
 *  - pathfinding Paper vers la cible, orbite (strafe) en mêlée
 *  - détection de blocage et de chemin inaccessible
 *  - déblayage, ponts et pillar via EndBossScaffold
 *  - esquive des projectiles
 */
public class EndBossMovement {

    private final EndBossBrain brain;
    private final WitherSkeleton boss;
    private final Random rng = new Random();

    private Location lastPos;
    private long lastStuckCheck, lastPathCheck, strafeSwitchAt, lastDodge;
    private int stuckTicks;
    private double strafeDir = 1.0;
    private boolean unreachable;

    public EndBossMovement(EndBossBrain brain) {
        this.brain = brain;
        this.boss = brain.boss;
    }

    public boolean isStuck() { return stuckTicks >= 20; }
    public boolean isUnreachable() { return unreachable; }

    public void onHurt() {
        if (rng.nextDouble() < 0.5) strafeDir = -strafeDir;
    }

    public void idle() {
        boss.getPathfinder().stopPathfinding();
        stuckTicks = 0;
        unreachable = false;
    }

    public void tick(Player t, double dist, boolean los, PlayerProfile prof) {
        long now = brain.now();
        boss.lookAt(t.getEyeLocation());
        if (brain.isBusy()) return; // une capacité scriptée contrôle le boss

        Location bl = boss.getLocation();
        Location tl = t.getLocation();
        double dy = tl.getY() - bl.getY();
        double horiz = Math.hypot(tl.getX() - bl.getX(), tl.getZ() - bl.getZ());

        // --- détection de blocage (toutes les 10 ticks)
        if (now - lastStuckCheck >= 10) {
            if (lastPos != null && lastPos.getWorld() != null && lastPos.getWorld().equals(bl.getWorld())
                    && dist > 3.5 && bl.distance(lastPos) < 0.4) {
                stuckTicks += 10;
            } else {
                stuckTicks = Math.max(0, stuckTicks - 10);
            }
            lastPos = bl.clone();
            lastStuckCheck = now;
        }

        // --- le chemin vers la cible existe-t-il ? (toutes les 20 ticks)
        if (now - lastPathCheck >= 20) {
            lastPathCheck = now;
            var result = boss.getPathfinder().findPath(tl);
            unreachable = result == null || !result.canReachFinalPoint();
        }

        if (brain.water().swim(t, dist)) {
            if (stuckTicks >= 20) brain.scaffold().clearPath(t);
            return;
        }

        boolean shielding = brain.isShielding();

        // --- cible perchée en hauteur et inaccessible : anti-pillar puis pillar
        if (dy > 2.2 && horiz < 9 && unreachable) {
            if (prof.isPillaring() && horiz < 6) brain.scaffold().shatterBelow(t);
            brain.scaffold().pillarUp(t);
            boss.getPathfinder().moveTo(tl, 1.1);
            return;
        }

        // --- cible inaccessible au sol (vide, trou...) : pont + marche directe
        if (unreachable && dist > 3.0 && boss.isOnGround() && !(brain.water().enabled() && t.isInWater())) {
            if (stuckTicks >= 20) brain.scaffold().clearPath(t);
            brain.scaffold().bridgeToward(t);
            Vector dir = tl.toVector().subtract(bl.toVector()).setY(0);
            if (dir.lengthSquared() > 0.01) {
                dir.normalize().multiply(0.26);
                Vector v = boss.getVelocity();
                boss.setVelocity(new Vector(dir.getX(), v.getY(), dir.getZ()));
            }
            return;
        }

        // --- coincé contre un mur : on le casse (restauré ensuite)
        if (stuckTicks >= 20) brain.scaffold().clearPath(t);

        // --- mêlée : orbite en spirale autour de la cible, comme un joueur PvP
        if (dist > 2.6 && dist < 7 && !shielding) {
            if (now >= strafeSwitchAt) {
                if (rng.nextBoolean()) strafeDir = -strafeDir;
                strafeSwitchAt = now + 30 + rng.nextInt(50);
            }
            Vector fromTarget = bl.toVector().subtract(tl.toVector()).setY(0);
            if (fromTarget.lengthSquared() < 0.01) fromTarget = new Vector(1, 0, 0);
            double radius = Math.max(1.8, dist - 1.2);
            fromTarget.normalize().rotateAroundY(Math.toRadians(40 * strafeDir)).multiply(radius);
            boss.getPathfinder().moveTo(tl.clone().add(fromTarget), 1.25);
            return;
        }

        boss.getPathfinder().moveTo(tl, shielding ? 0.55 : 1.15);
    }

    /** Esquive latérale (ou garde) quand une flèche / un projectile de joueur fonce sur le boss. */
    public void dodgeProjectiles() {
        if (!brain.cfg().getBoolean("boss.ai.dodge-projectiles", true)) return;
        long now = brain.now();
        if (now - lastDodge < 30 || brain.isBusy()) return;

        Location center = boss.getLocation().add(0, boss.getHeight() / 2.0, 0);
        for (Entity e : boss.getNearbyEntities(10, 8, 10)) {
            if (!(e instanceof Projectile proj) || !(proj.getShooter() instanceof Player)) continue;

            Vector v = proj.getVelocity();
            if (v.lengthSquared() < 0.1) continue;
            Vector toBoss = center.toVector().subtract(proj.getLocation().toVector());
            double d = toBoss.length();
            if (d < 1.0 || d > 10.0) continue;
            if (v.clone().normalize().dot(toBoss.clone().normalize()) < 0.92) continue; // ne vise pas le boss

            lastDodge = now;
            if (rng.nextDouble() > brain.skill()) return; // raté : le niveau de skill est configurable

            if (brain.canShield() && rng.nextDouble() < 0.4) {
                brain.raiseShield(30);
                return;
            }
            Vector side = new Vector(-v.getZ(), 0, v.getX());
            if (side.lengthSquared() < 0.001) return;
            side.normalize().multiply(rng.nextBoolean() ? 0.85 : -0.85).setY(0.3);
            boss.setVelocity(side);
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.4f);
            return;
        }
    }
}
