package fr.zyumie.EndBoss.IA;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class StolenItems {

    private record Theft(ItemStack item, int slot) {}

    private static final Map<UUID, Theft> STOLEN = new HashMap<>();

    private StolenItems() {}

    public static ItemStack steal(Player p) {
        if (STOLEN.containsKey(p.getUniqueId())) return null;
        PlayerInventory inv = p.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (hand.getType().isAir()) return null;
        ItemStack item = hand.clone();
        STOLEN.put(p.getUniqueId(), new Theft(item, inv.getHeldItemSlot()));
        inv.setItemInMainHand(null);
        return item.clone();
    }

    public static boolean isHolding(Player p) {
        return STOLEN.containsKey(p.getUniqueId());
    }

    public static void giveBack(Player p) {
        Theft theft = STOLEN.remove(p.getUniqueId());
        if (theft == null) return;
        PlayerInventory inv = p.getInventory();
        ItemStack inSlot = inv.getItem(theft.slot());
        if (inSlot == null || inSlot.getType() == Material.AIR) {
            inv.setItem(theft.slot(), theft.item());
            return;
        }
        for (ItemStack left : inv.addItem(theft.item()).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    public static void onDeath(PlayerDeathEvent event) {
        Player p = event.getPlayer();
        if (event.getKeepInventory()) {
            giveBack(p);
            return;
        }
        Theft theft = STOLEN.remove(p.getUniqueId());
        if (theft != null) event.getDrops().add(theft.item());
    }

    public static void restoreAll() {
        for (UUID id : new ArrayList<>(STOLEN.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) giveBack(p);
        }
    }
}
