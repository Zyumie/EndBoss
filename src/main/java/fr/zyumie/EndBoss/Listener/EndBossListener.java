package fr.zyumie.EndBoss.Listener;

import fr.zyumie.EndBoss.EndBossRegistry;
import fr.zyumie.EndBoss.IA.EndBossBrain;
import fr.zyumie.EndBoss.IA.EndBossCinematics;
import fr.zyumie.EndBoss.IA.ScrambledInventories;
import fr.zyumie.EndBoss.IA.StolenItems;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class EndBossListener implements Listener {

    private final JavaPlugin plugin;

    public EndBossListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    /** Dégâts reçus : réduction globale, garde active (face seulement), bris de garde à la hache, menace. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossDamaged(EntityDamageByEntityEvent event) {
        EndBossBrain brain = EndBossRegistry.get(event.getEntity());
        if (brain == null) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) return;

        boolean melee = event.getDamager() instanceof Player;
        double dmg = event.getDamage() * brain.cfg().getDouble("boss.damage-taken-multiplier", 0.6) * brain.water().damageTaken();
        boolean blocked = false;

        if (brain.isShielding() && brain.isInFront(event.getDamager().getLocation())) {
            blocked = true;
            dmg *= brain.cfg().getDouble("boss.shield.damage-through", 0.12);
            Location at = brain.boss.getLocation().add(0, 1, 0);
            brain.boss.getWorld().playSound(at, Sound.ITEM_SHIELD_BLOCK, 1.2f, 1.0f);
            brain.boss.getWorld().spawnParticle(Particle.CRIT, at, 10, 0.3, 0.3, 0.3, 0.1);
            brain.onShieldBlock();

            if (melee && Tag.ITEMS_AXES.isTagged(attacker.getInventory().getItemInMainHand().getType())) {
                brain.disableShield(100); // une hache brise la garde
            }
        }

        event.setDamage(dmg);
        brain.onDamaged(attacker, dmg, melee, blocked);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossDeals(EntityDamageByEntityEvent event) {
        EndBossBrain brain = EndBossRegistry.get(event.getDamager());
        if (brain != null) event.setDamage(event.getDamage() * brain.water().damageDealt());
    }

    /** Totem d'immortalité : un coup mortel est annulé tant qu'il reste des totems. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossLethal(EntityDamageEvent event) {
        EndBossBrain brain = EndBossRegistry.get(event.getEntity());
        if (brain == null) return;
        if (event.getFinalDamage() >= brain.boss.getHealth() && brain.useTotem()) {
            event.setCancelled(true);
        }
    }

    /** Le boss garde la cible choisie par son cerveau (les goals vanilla ne changent plus de cible). */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        EndBossBrain brain = EndBossRegistry.get(event.getEntity());
        if (brain == null) return;
        LivingEntity wanted = event.getTarget();
        if (wanted != null && brain.target() != null && !wanted.equals(brain.target())) {
            event.setCancelled(true);
        }
    }

    /** Immunités : étouffement dans ses propres blocs, chutes, vide (retour au dernier point sûr). */
    @EventHandler
    public void onBossEnvironmentDamage(EntityDamageEvent event) {
        EndBossBrain brain = EndBossRegistry.get(event.getEntity());
        if (brain == null) return;
        switch (event.getCause()) {
            case SUFFOCATION, FALL, FLY_INTO_WALL, CRAMMING, DROWNING -> event.setCancelled(true);
            case VOID -> {
                event.setCancelled(true);
                brain.rescue();
            }
            default -> { }
        }
    }

    /** Alimente le profil adaptatif : le boss remarque les joueurs qui tirent à distance. */
    @EventHandler
    public void onShoot(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player p)) return;
        for (EndBossBrain b : EndBossRegistry.all()) {
            if (b.boss.getWorld().equals(p.getWorld()) && b.boss.getLocation().distanceSquared(p.getLocation()) < 4096) {
                b.profile(p).recordShot();
            }
        }
    }

    /** Reprise en main d'un boss dont le chunk vient d'être rechargé (ou après un redémarrage). */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (e instanceof WitherSkeleton ws && EndBossRegistry.hasTag(ws) && !ws.isDead()) {
                EndBossRegistry.register(plugin, ws);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        StolenItems.giveBack(event.getPlayer());
        ScrambledInventories.restore(event.getPlayer());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        StolenItems.onDeath(event);
        ScrambledInventories.onDeath(event);
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (EndBossRegistry.isBoss(event.getEntered())) event.setCancelled(true);
    }

    @EventHandler
    public void onNetherPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
        if (!plugin.getConfig().getBoolean("boss.dimension-lock.enabled", true)) return;
        if (event.getFrom().getWorld().getEnvironment() != World.Environment.NORMAL) return;

        if (EndBossRegistry.isBossAlive()) {
            event.setCancelled(true);
            String msg = plugin.getConfig().getString("boss.dimension-lock.message", "&cLe Nether est scellé !");
            event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.5f);
        }
    }

    @EventHandler
    public void onBossPortal(EntityPortalEvent event) {
        if (EndBossRegistry.isBoss(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!plugin.getConfig().getBoolean("boss.end-lock.enabled", true)) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) return;

        if (EndBossRegistry.isBossAlive()) {
            event.setCancelled(true);
            String msg = plugin.getConfig().getString("boss.end-lock.message", "&cPortail de l'End verrouillé !");
            event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.5f);
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        EndBossBrain brain = EndBossRegistry.get(event.getEntity());
        if (brain == null) return;

        // Aucun loot vanilla, uniquement ceux définis dans Boss.yml
        event.getDrops().clear();
        ConfigurationSection drops = brain.cfg().getConfigurationSection("boss.drops");
        if (drops != null) {
            for (String key : drops.getKeys(false)) {
                Material mat = Material.matchMaterial(drops.getString(key + ".material", "DIAMOND"));
                int amount = drops.getInt(key + ".amount", 1);
                if (mat != null) event.getDrops().add(new ItemStack(mat, amount));
            }
        }

        java.util.List<String> top = brain.topDamagers(3);
        Location loc = event.getEntity().getLocation();

        // Supprime la BossBar et les minions, puis libère le portail
        EndBossRegistry.unregister(event.getEntity().getUniqueId());
        EndBossRegistry.setBossAlive(plugin, false);

        loc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, loc.clone().add(0, 1, 0), 150, 1, 1, 1, 0.5);
        loc.getWorld().playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 2.0f, 0.5f);
        EndBossCinematics.death(loc);

        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        Bukkit.broadcast(legacy.deserialize("§4§l[EndBoss] Le Gardien du Vide a succombé ! Le portail de l'End est enfin déverrouillé !"));
        if (!top.isEmpty()) {
            Bukkit.broadcast(legacy.deserialize("§6§lMeilleurs combattants :"));
            for (String line : top) Bukkit.broadcast(legacy.deserialize(line));
        }
    }
}
