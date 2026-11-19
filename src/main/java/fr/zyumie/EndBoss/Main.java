package fr.zyumie.EndBoss;

import fr.zyumie.EndBoss.IA.BlockRestorer;
import fr.zyumie.EndBoss.IA.EndBossAIController;
import fr.zyumie.EndBoss.IA.EndBossBrain;
import fr.zyumie.EndBoss.IA.EndBossCinematics;
import fr.zyumie.EndBoss.IA.EndBossEffects;
import fr.zyumie.EndBoss.IA.ScrambledInventories;
import fr.zyumie.EndBoss.IA.StolenItems;
import fr.zyumie.EndBoss.Listener.EndBossListener;
import fr.zyumie.EndBoss.Listener.EndBossManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main extends JavaPlugin implements CommandExecutor, TabCompleter {

    private FileConfiguration bossConfig;
    private final Map<String, FileConfiguration> profiles = new HashMap<>();

    @Override
    public void onEnable() {
        reloadConfig(); // charge Boss.yml
        if (!new File(getDataFolder(), "Impossible.yml").exists()) saveResource("Impossible.yml", false);
        EndBossRegistry.init(this);
        BlockRestorer.init(this);
        EndBossEffects.init(this);
        EndBossCinematics.init(this);

        if (getCommand("endboss") != null) {
            getCommand("endboss").setExecutor(this);
            getCommand("endboss").setTabCompleter(this);
        }

        getServer().getPluginManager().registerEvents(new EndBossListener(this), this);
        EndBossRegistry.scan(this);          // reprend les boss déjà chargés
        EndBossAIController.startAI(this);

        getLogger().info("EndBoss activé avec succès !");
    }

    @Override
    public void onDisable() {
        EndBossAIController.stop();
        StolenItems.restoreAll();
        ScrambledInventories.restoreAll();
        EndBossRegistry.shutdownAll();
        BlockRestorer.restoreAll(); // rien ne reste cassé / posé après un arrêt ou un reload
        EndBossEffects.shutdown();
    }

    // ---- Boss.yml sert de configuration (avant : saveResource sans jamais le lire)

    @Override
    public FileConfiguration getConfig() {
        if (bossConfig == null) reloadConfig();
        return bossConfig;
    }

    @Override
    public void reloadConfig() {
        File file = new File(getDataFolder(), "Boss.yml");
        if (!file.exists()) saveResource("Boss.yml", false);
        bossConfig = YamlConfiguration.loadConfiguration(file);
        profiles.clear();

        // Les clés absentes d'un ancien Boss.yml reprennent la valeur par défaut du jar
        try (InputStream in = getResource("Boss.yml")) {
            if (in != null) {
                bossConfig.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ex) {
            getLogger().warning("Impossible de lire les valeurs par défaut de Boss.yml : " + ex.getMessage());
        }
    }

    /**
     * Profil de difficulté : "normal" = Boss.yml, "impossible" = Impossible.yml, ou tout autre fichier
     * Nom.yml placé dans le dossier du plugin. Les clés absentes reprennent les valeurs de Boss.yml.
     */
    public FileConfiguration profile(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("normal") || name.equalsIgnoreCase("boss")) {
            return getConfig();
        }
        String key = name.toLowerCase();
        FileConfiguration cached = profiles.get(key);
        if (cached != null) return cached;
        FileConfiguration loaded = loadProfile(key);
        profiles.put(key, loaded);
        return loaded;
    }

    private FileConfiguration loadProfile(String key) {
        if (!key.matches("[a-z0-9_-]+")) return getConfig();
        String fileName = Character.toUpperCase(key.charAt(0)) + key.substring(1) + ".yml";
        File file = new File(getDataFolder(), fileName);
        if (!file.exists() && getResource(fileName) != null) saveResource(fileName, false);
        if (!file.exists()) {
            getLogger().warning("Profil introuvable : " + fileName + " (Boss.yml utilisé à la place)");
            return getConfig();
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration jarDefaults = new YamlConfiguration();
        try (InputStream in = getResource(fileName)) {
            if (in != null) {
                jarDefaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            getLogger().warning("Impossible de lire " + fileName + " dans le jar : " + ex.getMessage());
        }
        jarDefaults.setDefaults(getConfig()); // jar du profil, puis Boss.yml
        cfg.setDefaults(jarDefaults);
        return cfg;
    }

    @Override
    public void saveConfig() {
        // Boss.yml est édité à la main : on ne l'écrase jamais.
    }

    // ---- Commandes

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("endboss.admin")) {
            sender.sendMessage(ChatColor.RED + "Vous n'avez pas la permission.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        switch (sub) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Seul un joueur peut exécuter cette commande.");
                    return true;
                }
                boolean force = false;
                String profileName = "normal";
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equalsIgnoreCase("force")) force = true;
                    else profileName = args[i].toLowerCase();
                }
                if (!force && getConfig().getBoolean("boss.single-instance", true) && EndBossRegistry.isBossAlive()) {
                    player.sendMessage(ChatColor.RED + "Un EndBoss est déjà actif. /endboss kill pour le retirer, ou /endboss spawn force.");
                    return true;
                }
                EndBossManager.spawnEndBoss(this, player.getLocation(), profileName);
                player.sendMessage(ChatColor.DARK_RED + "L'EndBoss ultime a été invoqué ! (profil : " + profileName + ")");
            }
            case "kill" -> {
                int removed = 0;
                for (EndBossBrain b : EndBossRegistry.all()) {
                    b.boss.remove();
                    EndBossRegistry.unregister(b.boss.getUniqueId());
                    removed++;
                }
                for (World w : Bukkit.getWorlds()) {
                    for (WitherSkeleton ws : w.getEntitiesByClass(WitherSkeleton.class)) {
                        if (EndBossRegistry.hasTag(ws)) {
                            ws.remove();
                            removed++;
                        }
                    }
                }
                EndBossRegistry.setBossAlive(this, false);
                sender.sendMessage(ChatColor.YELLOW + "EndBoss retiré(s) : " + removed + ". Portail déverrouillé.");
            }
            case "unlock" -> {
                EndBossRegistry.setBossAlive(this, false);
                sender.sendMessage(ChatColor.YELLOW + "Verrou du portail de l'End levé.");
            }
            case "reload" -> {
                reloadConfig();
                BlockRestorer.reload();
                EndBossEffects.reload();
                for (EndBossBrain b : EndBossRegistry.all()) b.phases().refresh();
                EndBossAIController.startAI(this);
                sender.sendMessage(ChatColor.GREEN + "Boss.yml rechargé.");
            }
            case "status" -> {
                Collection<EndBossBrain> brains = EndBossRegistry.all();
                if (brains.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "Aucun EndBoss chargé. Portail verrouillé : " + EndBossRegistry.isBossAlive());
                }
                for (EndBossBrain b : brains) {
                    sender.sendMessage(ChatColor.GOLD + "EndBoss " + ChatColor.WHITE
                            + Math.round(b.boss.getHealth()) + "/" + Math.round(b.maxHealth()) + " PV"
                            + " | phase " + b.phases().current()
                            + " | totems " + b.totemsLeft()
                            + " | cible : " + (b.target() != null ? b.target().getName() : "aucune"));
                }
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage : /endboss <spawn [impossible] [force]|kill|reload|status|unlock>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("spawn", "kill", "reload", "status", "unlock")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("spawn")) {
            for (String s : List.of("impossible", "normal", "force")) {
                if (s.startsWith(args[args.length - 1].toLowerCase())) out.add(s);
            }
        }
        return out;
    }
}
