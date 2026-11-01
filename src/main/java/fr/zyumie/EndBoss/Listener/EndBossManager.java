package fr.zyumie.EndBoss.Listener;

import fr.zyumie.EndBoss.EndBossRegistry;
import fr.zyumie.EndBoss.IA.EndBossBrain;
import fr.zyumie.EndBoss.IA.EndBossCinematics;
import fr.zyumie.EndBoss.Main;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public class EndBossManager {

    public static WitherSkeleton spawnEndBoss(Main plugin, Location loc, String profile) {
        String profileName = profile == null || profile.isBlank() ? "normal" : profile.toLowerCase();
        FileConfiguration cfg = plugin.profile(profileName);
        WitherSkeleton boss = (WitherSkeleton) loc.getWorld().spawnEntity(loc, EntityType.WITHER_SKELETON);

        boss.getPersistentDataContainer().set(EndBossRegistry.key(), PersistentDataType.BYTE, (byte) 1);
        boss.getPersistentDataContainer().set(EndBossRegistry.profileKey(), PersistentDataType.STRING, profileName);
        boss.setCustomName(ChatColor.translateAlternateColorCodes('&', cfg.getString("boss.name", "&4EndBoss")));
        boss.setCustomNameVisible(true);
        boss.setRemoveWhenFarAway(false);
        boss.setPersistent(true);
        boss.setCanPickupItems(false);

        double maxHealth = cfg.getDouble("boss.health", 800.0);
        EndBossBrain.setAttribute(boss, Attribute.MAX_HEALTH, maxHealth);
        double allowed = boss.getAttribute(Attribute.MAX_HEALTH).getValue();
        if (allowed < maxHealth) {
            plugin.getLogger().warning("Vie du boss limitée à " + allowed + " au lieu de " + maxHealth
                    + " : augmente settings.attribute.maxHealth.max dans spigot.yml.");
        }
        boss.setHealth(allowed);

        EntityEquipment eq = boss.getEquipment();
        if (eq != null) {
            eq.setHelmet(new ItemStack(material(cfg.getString("boss.equipment.helmet"), Material.DRAGON_HEAD)));
            eq.setChestplate(armor(cfg.getString("boss.equipment.chestplate"), Material.NETHERITE_CHESTPLATE));
            eq.setLeggings(armor(cfg.getString("boss.equipment.leggings"), Material.NETHERITE_LEGGINGS));
            eq.setBoots(armor(cfg.getString("boss.equipment.boots"), Material.NETHERITE_BOOTS));

            ItemStack weapon = new ItemStack(material(cfg.getString("boss.equipment.weapon"), Material.MACE));
            weapon.addUnsafeEnchantment(Enchantment.SHARPNESS, 7);
            weapon.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            eq.setItemInMainHand(weapon);
            eq.setItemInOffHand(new ItemStack(material(cfg.getString("boss.equipment.offhand"), Material.SHIELD)));

            eq.setHelmetDropChance(0.0f);
            eq.setChestplateDropChance(0.0f);
            eq.setLeggingsDropChance(0.0f);
            eq.setBootsDropChance(0.0f);
            eq.setItemInMainHandDropChance(0.0f);
            eq.setItemInOffHandDropChance(0.0f);
        }

        EndBossRegistry.setBossAlive(plugin, true);
        EndBossBrain brain = EndBossRegistry.register(plugin, boss);

        loc.getWorld().spawnParticle(Particle.REVERSE_PORTAL, loc.clone().add(0, 1, 0), 120, 1, 1.5, 1, 0.4);
        loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_SPAWN, 2.0f, 0.6f);
        EndBossCinematics.intro(brain);
        return boss;
    }

    /** Un nom de matériau invalide dans Boss.yml ne fait plus planter le spawn. */
    private static Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name);
        return m != null ? m : fallback;
    }

    private static ItemStack armor(String name, Material fallback) {
        ItemStack item = new ItemStack(material(name, fallback));
        item.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        return item;
    }
}
