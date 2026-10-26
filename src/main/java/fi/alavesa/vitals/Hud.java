package fi.alavesa.vitals;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The on-screen bars. Each one is a per-player Adventure boss bar: an isolated row at the top of the screen
 * that never shares a line with anything else (action bar, titles, scoreboards) and simply stacks with any
 * other plugin's boss bars. The stamina bar is always up for survival/adventure players; the condition bar
 * appears only while you carry wounds or are applying a medical item.
 */
public final class Hud implements Runnable {

    private final VitalsPlugin plugin;
    private final Map<UUID, BossBar> stamina = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> condition = new ConcurrentHashMap<>();
    private final NamespacedKey offKey;

    public Hud(VitalsPlugin plugin) { this.plugin = plugin; offKey = new NamespacedKey(plugin, "hud_off"); }

    public boolean hidden(Player p) { return p.getPersistentDataContainer().has(offKey, PersistentDataType.BYTE); }

    public void toggle(Player p, boolean on) {
        if (on) p.getPersistentDataContainer().remove(offKey);
        else { p.getPersistentDataContainer().set(offKey, PersistentDataType.BYTE, (byte) 1); hide(p); }
    }

    @Override
    public void run() {
        boolean enabled = plugin.getConfig().getBoolean("hud.enabled", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!enabled || hidden(p) || !Injuries.affected(p)) { hide(p); continue; }
            updateStamina(p);
            updateCondition(p);
        }
        for (UUID id : stamina.keySet().toArray(new UUID[0])) if (Bukkit.getPlayer(id) == null) forget(id);
    }

    private void updateStamina(Player p) {
        if (!plugin.getConfig().getBoolean("hud.stamina.enabled", true) || !plugin.getConfig().getBoolean("stamina.enabled", true)) { remove(stamina, p); return; }
        double frac = plugin.stamina().fraction(p);
        boolean winded = plugin.stamina().isWinded(p);
        boolean locked = !plugin.injuries().canSprint(p);
        BossBar.Color color = winded || locked ? BossBar.Color.RED : frac > 0.5 ? BossBar.Color.GREEN : frac > 0.25 ? BossBar.Color.YELLOW : BossBar.Color.RED;
        String label = plugin.getConfig().getString("hud.stamina.label", "Stamina");
        if (winded) label = "Winded"; else if (locked) label = "Can't run";
        String text = plugin.rpe().format(plugin.getConfig().getString("hud.stamina.icon", "")) + " " + label;
        if (plugin.getConfig().getBoolean("hud.stamina.show-percent", true)) text += " " + Math.round(frac * 100) + "%";
        BossBar bar = stamina.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(Component.text(text.trim(), NamedTextColor.WHITE), (float) frac, color, overlay("hud.stamina.overlay"));
            stamina.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(Component.text(text.trim(), NamedTextColor.WHITE)).progress((float) frac).color(color);
        }
    }

    private void updateCondition(Player p) {
        if (!plugin.getConfig().getBoolean("hud.injuries.enabled", true)) { remove(condition, p); return; }
        MedicalItems.Treatment t = plugin.medical().treatment(p);
        boolean wounded = plugin.injuries().hasWounds(p) || plugin.injuries().isLimping(p);
        if (t == null && !wounded) { remove(condition, p); return; }
        String text; float progress; BossBar.Color color;
        if (t != null) {
            text = "Applying " + t.name + "...";
            progress = (float) Math.max(0, Math.min(1, t.progress()));
            color = BossBar.Color.WHITE;
        } else {
            double pts = plugin.injuries().points(p);
            boolean bleeding = plugin.injuries().bleedRate(p) > 0;
            text = plugin.rpe().format(plugin.getConfig().getString("hud.injuries.icon", "")) + " " + plugin.injuries().describe(p);
            progress = (float) Math.max(0, Math.min(1, 1 - pts / Math.max(1, plugin.getConfig().getDouble("speed.injury.cap", 5))));
            color = bleeding ? BossBar.Color.RED : BossBar.Color.PINK;
        }
        BossBar bar = condition.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(Component.text(text.trim(), NamedTextColor.WHITE), progress, color, BossBar.Overlay.PROGRESS);
            condition.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(Component.text(text.trim(), NamedTextColor.WHITE)).progress(progress).color(color);
        }
    }

    private BossBar.Overlay overlay(String path) {
        try { return BossBar.Overlay.valueOf(plugin.getConfig().getString(path, "PROGRESS").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return BossBar.Overlay.PROGRESS; }
    }

    private void remove(Map<UUID, BossBar> map, Player p) {
        BossBar bar = map.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    public void hide(Player p) { remove(stamina, p); remove(condition, p); }

    public void forget(UUID id) { stamina.remove(id); condition.remove(id); }

    public void hideAll() { for (Player p : Bukkit.getOnlinePlayers()) hide(p); stamina.clear(); condition.clear(); }
}
