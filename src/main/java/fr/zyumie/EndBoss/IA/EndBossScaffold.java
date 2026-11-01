package fr.zyumie.EndBoss.IA;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.util.Vector;

/**
 * Le boss modifie le terrain pour atteindre sa cible, mais tout passe par BlockRestorer :
 * les blocs posés disparaissent et les blocs cassés reviennent après {@code boss.griefing.restore-seconds}.
 * Les conteneurs, blocs incassables et matériaux protégés ne sont jamais touchés.
 */
public class EndBossScaffold {

    private final EndBossBrain brain;
    private final WitherSkeleton boss;
    private long lastClear, lastShatter, lastCap;

    public EndBossScaffold(EndBossBrain brain) {
        this.brain = brain;
        this.boss = brain.boss;
    }

    private boolean canPlace() { return brain.cfg().getBoolean("boss.griefing.place-blocks", true); }
    private boolean canBreak() { return brain.cfg().getBoolean("boss.griefing.break-blocks", true); }
    private int ttl() { return Math.max(40, brain.cfg().getInt("boss.griefing.restore-seconds", 25) * 20); }

    private boolean place(Block b, Material m) {
        if (!canPlace() || !(b.isEmpty() || b.isReplaceable())) return false;
        if (b.getY() < b.getWorld().getMinHeight() || b.getY() >= b.getWorld().getMaxHeight()) return false;
        BlockRestorer.apply(b, m, ttl());
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().spawnParticle(Particle.BLOCK, c, 12, 0.3, 0.3, 0.3, m.createBlockData());
        b.getWorld().playSound(c, Sound.BLOCK_STONE_PLACE, 0.7f, 0.9f);
        return true;
    }

    private boolean breakBlock(Block b) {
        if (!canBreak() || BlockRestorer.isProtected(b)) return false;
        BlockData data = b.getBlockData();
        BlockRestorer.apply(b, Material.AIR, ttl());
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().spawnParticle(Particle.BLOCK, c, 12, 0.3, 0.3, 0.3, data);
        EndBossEffects.burst(c, data.getMaterial(), 3);
        b.getWorld().playSound(c, Sound.BLOCK_STONE_BREAK, 0.8f, 0.7f);
        return true;
    }

    private Vector horizontalDir(Player t) {
        Vector d = t.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (d.lengthSquared() < 0.01) return null;
        return d.normalize();
    }

    /** Monte en pillar : saute, pose un bloc sous ses pieds en l'air, perce le plafond. */
    public void pillarUp(Player t) {
        if (!canPlace()) return;
        Location bl = boss.getLocation();
        int h = (int) Math.ceil(boss.getHeight());

        Block ceiling = bl.clone().add(0, h, 0).getBlock();
        if (!ceiling.isPassable()) breakBlock(ceiling);

        if (boss.isOnGround()) {
            Vector v = boss.getVelocity();
            boss.setVelocity(new Vector(v.getX(), 0.52, v.getZ()));
            return;
        }
        Block under = bl.clone().subtract(0, 1, 0).getBlock();
        if (under.isPassable() && boss.getVelocity().getY() < 0.3) place(under, Material.OBSIDIAN);
    }

    /** Pose un pont de blackstone devant lui quand le vide lui barre la route. */
    public void bridgeToward(Player t) {
        if (!canPlace() || !boss.isOnGround()) return;
        Vector dir = horizontalDir(t);
        if (dir == null) return;
        Location base = boss.getLocation().clone().subtract(0, 1, 0);
        for (double d : new double[]{0.9, 1.7}) {
            place(base.clone().add(dir.clone().multiply(d)).getBlock(), Material.POLISHED_BLACKSTONE);
        }
    }

    /** Perce un tunnel à sa taille (tient compte de l'échelle de phase) droit devant lui. */
    public void clearPath(Player t) {
        if (!canBreak()) return;
        long now = brain.now();
        if (now - lastClear < 6) return;
        lastClear = now;

        Vector dir = horizontalDir(t);
        if (dir == null) return;
        Vector perp = new Vector(-dir.getZ(), 0, dir.getX());
        int h = (int) Math.ceil(boss.getHeight());
        double reach = boss.getWidth() * 0.5 + 0.9;
        double[] offsets = boss.getWidth() > 1.0
                ? new double[]{-boss.getWidth() * 0.4, 0, boss.getWidth() * 0.4}
                : new double[]{0};

        Location base = boss.getLocation();
        for (double off : offsets) {
            Location col = base.clone().add(dir.clone().multiply(reach)).add(perp.clone().multiply(off));
            for (int i = 0; i < h; i++) breakBlock(col.clone().add(0, i, 0).getBlock());
        }
    }

    /** Anti-pillar : casse le bloc sur lequel se tient un joueur qui monte une tour. */
    public void shatterBelow(Player t) {
        if (!canBreak()) return;
        long now = brain.now();
        if (now - lastShatter < 20) return;
        Location p = t.getLocation().clone().subtract(0, 0.3, 0);
        for (int i = 0; i < 6; i++) {
            Block b = p.clone().subtract(0, i, 0).getBlock();
            if (!b.isPassable()) {
                if (breakBlock(b)) lastShatter = now;
                return;
            }
        }
    }

    /** Anti-pillar : pose un plafond temporaire pour empêcher le joueur de monter plus haut. */
    public void capHead(Player t) {
        if (!canPlace()) return;
        long now = brain.now();
        if (now - lastCap < 80) return;
        Block cap = t.getLocation().clone().add(0, 2, 0).getBlock();
        if (place(cap, Material.OBSIDIAN)) lastCap = now;
    }
}
