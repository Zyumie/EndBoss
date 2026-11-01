package fr.zyumie.EndBoss.IA;

import fr.zyumie.EndBoss.EndBossRegistry;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

/**
 * Une seule tâche pour tous les boss. Plus de scan de toutes les entités de tous les mondes :
 * on itère simplement sur les cerveaux du registre.
 */
public class EndBossAIController {

    private static BukkitTask task;

    public static void startAI(JavaPlugin plugin) {
        stop();
        int interval = Math.max(1, plugin.getConfig().getInt("boss.ai.tick-interval", 2));

        task = new BukkitRunnable() {
            @Override
            public void run() {
                BlockRestorer.tick();
                EndBossEffects.tick();

                for (EndBossBrain brain : EndBossRegistry.all()) {
                    try {
                        // Mort : le listener a déjà tout géré. Invalide : chunk déchargé, EntitiesLoadEvent le reprendra.
                        if (brain.boss.isDead() || !brain.boss.isValid()) {
                            EndBossRegistry.unregister(brain.boss.getUniqueId());
                            continue;
                        }
                        brain.tick();
                    } catch (Throwable ex) {
                        plugin.getLogger().log(Level.WARNING, "Erreur dans l'IA de l'EndBoss", ex);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, interval);
    }

    public static void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
