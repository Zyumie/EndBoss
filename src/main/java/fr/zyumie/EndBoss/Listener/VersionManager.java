package fr.zyumie.EndBoss.Listener;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Vérifie au démarrage si une nouvelle version est disponible sur Modrinth.
 * Notifie les OPs à leur connexion si une mise à jour existe.
 */
public class VersionManager implements Listener {

    private static final String MODRINTH_API =
            "https://api.modrinth.com/v2/project/SYzeRHyC/version?version_type=release";
    private static final String MODRINTH_URL =
            "https://modrinth.com/plugin/guardianofnether";

    private final JavaPlugin plugin;
    private String latestVersion = null; // null = vérification pas encore terminée

    public VersionManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Lance la vérification en async au démarrage.
     * À appeler depuis Main.onEnable().
     */
    public void checkAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                @SuppressWarnings("deprecation")
                HttpURLConnection con = (HttpURLConnection) new URL(MODRINTH_API).openConnection();
                con.setRequestProperty("User-Agent", plugin.getName() + "/" +
                        plugin.getDescription().getVersion());
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);

                JsonArray versions = JsonParser
                        .parseReader(new InputStreamReader(con.getInputStream()))
                        .getAsJsonArray();

                if (versions.isEmpty()) return;

                latestVersion = versions.get(0).getAsJsonObject()
                        .get("version_number").getAsString();

                String current = plugin.getDescription().getVersion();

                if (!current.equals(latestVersion)) {
                    plugin.getLogger().warning(
                            "Nouvelle version disponible : " + latestVersion +
                                    " (actuelle : " + current + ") — " + MODRINTH_URL);
                } else {
                    plugin.getLogger().info("Vous utilisez la dernière version (" + current + ") !");
                }

            } catch (Exception e) {
                plugin.getLogger().info("Impossible de vérifier les mises à jour : " + e.getMessage());
            }
        });
    }

    /**
     * Notifie l'OP à sa connexion si une mise à jour est disponible.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (latestVersion == null) return;

        Player player = event.getPlayer();
        if (!player.isOp()) return;

        String current = plugin.getDescription().getVersion();
        if (current.equals(latestVersion)) return;

        // Délai d'1 tick pour que le joueur soit bien connecté avant le message
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&8[&6Guardian&fOf&4Nether&8] &aMise à jour disponible : &e" + latestVersion +
                                " &7(actuelle : " + current + ")\n" +
                                "&9" + MODRINTH_URL
                )), 20L);
    }
}