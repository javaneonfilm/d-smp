package com.cartroyale.shield;

import com.cartroyale.CartRoyale;
import com.cartroyale.util.Items;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BlocksAttacks;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SHIELD-1..3. A shield that stacks to 16. While a player blocks with it, the next hit from any
 * source (melee, projectile, explosion) is fully cancelled and one shield from the stack breaks.
 * Vanilla blocking only reduces some sources and only from the front, so the block-and-break
 * rule is done here (docs/DEVIATIONS.md).
 */
public final class OneTimeShield implements Listener {

    public static final String ID = "one_time_shield";

    private final CartRoyale plugin;
    /** One shield per hit: an explosion hits once per entity, but guard the same tick anyway. */
    private final Map<UUID, Long> lastBreak = new HashMap<>();

    public OneTimeShield(CartRoyale plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("UnstableApiUsage")
    public static ItemStack create(CartRoyale plugin, int amount) {
        ItemStack it = new ItemStack(Material.SHIELD);
        BlocksAttacks blocking = it.getData(DataComponentTypes.BLOCKS_ATTACKS);
        // Stackable items can't carry durability, so the shield's damage components go.
        it.unsetData(DataComponentTypes.MAX_DAMAGE);
        it.unsetData(DataComponentTypes.DAMAGE);
        it.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
        if (blocking != null) it.setData(DataComponentTypes.BLOCKS_ATTACKS, blocking);
        String model = plugin.getConfig().getString("shield.item-model", "cartzone:one_time_shield");
        if (plugin.getConfig().getBoolean("resource-pack.enabled", false) && model != null && !model.isBlank()) {
            it.setData(DataComponentTypes.ITEM_MODEL, Key.key(model));
        }
        it.setData(DataComponentTypes.ITEM_NAME, plugin.msg().item("items.one-time-shield.name"));
        it.setData(DataComponentTypes.LORE, io.papermc.paper.datacomponent.item.ItemLore.lore(plugin.msg().lore("items.one-time-shield.lore")));
        Items.tagged(it, ID);
        it.setAmount(Math.max(1, Math.min(16, amount)));
        return it;
    }

    public static boolean is(ItemStack it) {
        return Items.is(it, ID);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (is(p.getInventory().getItemInOffHand()) || is(p.getInventory().getItemInMainHand())) {
            plugin.debug("shield check " + p.getName() + ": blocking=" + p.isBlocking() + " handRaised=" + p.isHandRaised()
                + " activeItem=" + p.getActiveItem().getType() + " hand=" + p.getActiveItemHand() + " cause=" + e.getCause());
        }
        if (!p.isBlocking()) return;
        EquipmentSlot hand = p.getActiveItemHand();
        ItemStack used = hand == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (!is(used)) return;
        if (!blockable(e.getCause())) return;
        long tick = org.bukkit.Bukkit.getCurrentTick(); // the arena clock is frozen, so never use world time here
        Long prev = lastBreak.get(p.getUniqueId());
        e.setCancelled(true);
        if (prev != null && prev == tick) return;
        lastBreak.put(p.getUniqueId(), tick);
        used.setAmount(used.getAmount() - 1);
        if (hand == EquipmentSlot.OFF_HAND) p.getInventory().setItemInOffHand(used.getAmount() > 0 ? used : null);
        else p.getInventory().setItemInMainHand(used.getAmount() > 0 ? used : null);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
        if (used.getAmount() <= 0) p.clearActiveItem();
        plugin.msg().actionbar(p, "shield.broke", "left", Math.max(0, used.getAmount()));
        plugin.debug("One Time Shield blocked " + e.getCause() + " for " + p.getName() + ", " + Math.max(0, used.getAmount()) + " left");
    }

    private static boolean blockable(EntityDamageEvent.DamageCause c) {
        return switch (c) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, ENTITY_EXPLOSION, BLOCK_EXPLOSION, THORNS, SONIC_BOOM, MAGIC -> true;
            default -> false;
        };
    }
}
