package fi.alavesa.vitals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bandage (stops bleeding), First Aid Kit (treats wounds), Splint (fixes a broken leg). Right-click starts a
 * timed treatment; moving away or switching items interrupts it, the condition bar shows the progress.
 * Items come from RP Engine (content/vitals/items) when it runs, otherwise they are plain tagged items.
 */
public final class MedicalItems implements Listener, Runnable {

    public static final List<String> IDS = List.of("bandage", "first_aid_kit", "splint");

    public static String lore(String id) {
        return switch (id) {
            case "bandage" -> "Hold right-click: stops bleeding";
            case "first_aid_kit" -> "Hold right-click: treats wounds";
            case "splint" -> "Hold right-click: fixes a broken leg";
            default -> "";
        };
    }

    public static final class Treatment {
        public final String id, name; final int total; int left; final Location start; final int slot;
        Treatment(String id, String name, int total, Location start, int slot) { this.id = id; this.name = name; this.total = total; this.left = total; this.start = start; this.slot = slot; }
        public double progress() { return total <= 0 ? 1 : 1 - (double) left / total; }
    }

    private final VitalsPlugin plugin;
    private final NamespacedKey itemKey;
    private final Map<UUID, Treatment> active = new ConcurrentHashMap<>();

    public MedicalItems(VitalsPlugin plugin) { this.plugin = plugin; itemKey = new NamespacedKey(plugin, "item"); }

    public Treatment treatment(Player p) { return active.get(p.getUniqueId()); }

    // ------------------------------------------------------------------ items

    public ItemStack create(String id, int amount) {
        if (!IDS.contains(id)) return null;
        var c = plugin.getConfig().getConfigurationSection("medical." + id);
        String name = c == null ? id : c.getString("name", id);
        ItemStack it = plugin.rpe().createItem(id, amount).orElse(null);
        if (it == null) {
            Material mat = Material.matchMaterial(c == null ? "PAPER" : c.getString("material", "PAPER"));
            it = new ItemStack(mat == null ? Material.PAPER : mat, Math.max(1, amount));
            ItemMeta meta = it.getItemMeta();
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(lore(id), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            var cmd = meta.getCustomModelDataComponent();
            cmd.setStrings(List.of("vitals_" + id));
            meta.setCustomModelDataComponent(cmd);
            it.setItemMeta(meta);
        }
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    public String idOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (id != null) return id;
        id = plugin.rpe().itemIdOf(it);
        return id != null && IDS.contains(id) ? id : null;
    }

    // ------------------------------------------------------------------ use

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        String id = idOf(event.getItem());
        if (id == null) return;
        event.setCancelled(true);
        if (active.containsKey(p.getUniqueId())) return;
        if (!Injuries.affected(p)) { p.sendMessage(Component.text("Nothing to treat.", NamedTextColor.GRAY)); return; }
        Injuries inj = plugin.injuries();
        String fail = switch (id) {
            case "bandage" -> inj.bleedRate(p) > 0 ? null : "You aren't bleeding.";
            case "splint" -> inj.has(p, InjuryType.FRACTURE) ? null : "Your legs are fine.";
            default -> inj.hasWounds(p) || Injuries.healthFraction(p) < 1 ? null : "You're not hurt.";
        };
        if (fail != null) { p.sendMessage(Component.text(fail, NamedTextColor.GRAY)); return; }
        double seconds = plugin.getConfig().getDouble("medical." + id + ".seconds", 2.0);
        String name = plainName(id);
        active.put(p.getUniqueId(), new Treatment(id, name, Math.max(1, (int) Math.round(seconds * 20)), p.getLocation(), p.getInventory().getHeldItemSlot()));
        p.playSound(p.getLocation(), "minecraft:item.armor.equip_leather", 0.8f, 1.2f);
        p.sendMessage(Component.text("Applying " + name.toLowerCase(Locale.ROOT) + "... hold still.", NamedTextColor.GRAY));
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) { cancel(event.getPlayer(), true); }

    public void cancel(Player p, boolean tell) {
        if (active.remove(p.getUniqueId()) != null && tell) p.sendMessage(Component.text("Treatment interrupted.", NamedTextColor.RED));
    }

    /** Every 5 ticks: advance treatments, interrupt the ones that moved / swapped items, apply the finished ones. */
    @Override
    public void run() {
        for (UUID id : active.keySet().toArray(new UUID[0])) {
            Player p = Bukkit.getPlayer(id);
            Treatment t = active.get(id);
            if (p == null || t == null) { active.remove(id); continue; }
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!t.id.equals(idOf(hand)) || p.getInventory().getHeldItemSlot() != t.slot || p.getLocation().distanceSquared(t.start) > 1.0 || p.isDead()) { cancel(p, true); continue; }
            t.left -= Injuries.PERIOD;
            if (t.left % 20 == 0) p.playSound(p.getLocation(), "minecraft:item.armor.equip_leather", 0.4f, 1.6f);
            if (t.left > 0) continue;
            active.remove(id);
            hand.setAmount(hand.getAmount() - 1);
            apply(p, t.id);
        }
    }

    private void apply(Player p, String id) {
        Injuries inj = plugin.injuries();
        double heal = plugin.getConfig().getDouble("medical." + id + ".heal-hp", 0);
        switch (id) {
            case "bandage" -> { inj.bandage(p); p.sendMessage(Component.text("The bleeding stops.", NamedTextColor.GREEN)); }
            case "first_aid_kit" -> {
                inj.firstAid(p, plugin.getConfig().getDouble("medical.first_aid_kit.points", 3.0), plugin.getConfig().getBoolean("medical.first_aid_kit.fixes-fractures", false));
                p.sendMessage(Component.text(inj.hasWounds(p) ? "You patch yourself up. " + inj.describe(p) + "." : "You patch yourself up. You feel much better.", NamedTextColor.GREEN));
            }
            case "splint" -> { inj.splint(p); p.sendMessage(Component.text("Your leg is splinted. You can walk again.", NamedTextColor.GREEN)); }
            default -> { }
        }
        if (heal > 0) {
            var a = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            p.setHealth(Math.min(a == null ? 20 : a.getValue(), p.getHealth() + heal));
        }
        p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 0.5f, 1.8f);
        inj.refresh(p);
    }

    private String plainName(String id) {
        String n = plugin.getConfig().getString("medical." + id + ".name", id);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(n).children().isEmpty()
            ? n.replaceAll("&[0-9a-fk-or]", "") : n.replaceAll("&[0-9a-fk-or]", "");
    }
}
