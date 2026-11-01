package fr.zyumie.EndBoss.IA;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ScrambledInventories {

    private static final Map<UUID, List<Integer>> SCRAMBLED = new HashMap<>();

    private ScrambledInventories() {}

    public static boolean scramble(Player p) {
        if (SCRAMBLED.containsKey(p.getUniqueId())) return false;
        PlayerInventory inv = p.getInventory();
        ItemStack[] items = inv.getStorageContents();
        int filled = 0;
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) filled++;
        }
        if (filled < 2) return false;

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.length; i++) order.add(i);
        Collections.shuffle(order);

        ItemStack[] shuffled = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) shuffled[order.get(i)] = items[i];
        inv.setStorageContents(shuffled);
        p.updateInventory();
        SCRAMBLED.put(p.getUniqueId(), order);
        return true;
    }

    public static void restore(Player p) {
        List<Integer> order = SCRAMBLED.remove(p.getUniqueId());
        if (order == null) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] items = inv.getStorageContents();
        ItemStack[] restored = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) restored[i] = items[order.get(i)];
        inv.setStorageContents(restored);
        p.updateInventory();
    }

    public static void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) restore(event.getPlayer());
        else SCRAMBLED.remove(event.getPlayer().getUniqueId());
    }

    public static void restoreAll() {
        for (UUID id : new ArrayList<>(SCRAMBLED.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restore(p);
        }
    }
}
