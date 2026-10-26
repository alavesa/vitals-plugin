package fi.alavesa.vitals;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sprint stamina (SCP:CB style). Drains while sprinting, refills while not; run it dry and you are winded
 * (no sprint until it climbs back over `recover-at`). Injuries, bleeding and lost health all make it drain
 * faster. Sprint is locked the vanilla way: the client refuses to sprint at food <= 6, so while sprint is
 * forbidden we hold food at 6 and otherwise keep it at >= 7 (same mechanism as Labra's SprintManager -
 * only ONE plugin may own this, so set `sprint.enabled: false` in Labra's config).
 */
public final class Stamina implements Runnable, Listener {

    static final int PERIOD = 2;
    private final VitalsPlugin plugin;
    private final Map<UUID, Double> pool = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> winded = new ConcurrentHashMap<>();

    public Stamina(VitalsPlugin plugin) { this.plugin = plugin; }

    private double max() { return plugin.getConfig().getDouble("stamina.max", 100); }
    public double value(Player p) { return pool.getOrDefault(p.getUniqueId(), max()); }
    public double fraction(Player p) { double m = max(); return m <= 0 ? 1 : Math.max(0, Math.min(1, value(p) / m)); }
    public boolean isWinded(Player p) { return winded.getOrDefault(p.getUniqueId(), false); }
    public void set(Player p, double v) { pool.put(p.getUniqueId(), Math.max(0, Math.min(max(), v))); if (v > 0) winded.put(p.getUniqueId(), false); }
    public void forget(UUID id) { pool.remove(id); winded.remove(id); }

    @Override
    public void run() {
        if (!plugin.getConfig().getBoolean("stamina.enabled", true)) return;
        double max = max();
        double drainPerTick = max / Math.max(0.1, plugin.getConfig().getDouble("stamina.sprint-seconds", 8) * 20);
        double regenPerTick = max / Math.max(0.1, plugin.getConfig().getDouble("stamina.regen-seconds", 6) * 20);
        double recoverAt = plugin.getConfig().getDouble("stamina.recover-at", 30);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!Injuries.affected(p)) { pool.put(id, max); winded.put(id, false); continue; }
            double s = pool.getOrDefault(id, max);
            boolean wind = winded.getOrDefault(id, false);
            boolean allowed = !wind && plugin.injuries().canSprint(p);

            boolean sprinting = p.isSprinting() && allowed;
            if (sprinting) {
                s -= drainPerTick * PERIOD * plugin.injuries().staminaDrainMultiplier(p);
                if (s <= 0) {
                    s = 0; wind = true; allowed = false;
                    p.setSprinting(false);
                    int slow = plugin.getConfig().getInt("stamina.winded-slowness-ticks", 40);
                    if (slow > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 1, true, false, false));
                    p.playSound(p.getLocation(), "minecraft:entity.player.breath", 0.8f, 0.8f);
                }
            } else {
                s = Math.min(max, s + regenPerTick * PERIOD);
            }
            if (wind && s >= recoverAt) { wind = false; allowed = plugin.injuries().canSprint(p); }

            // the vanilla food gate holds the lock (setSprinting(false) alone does not stick - the client re-sends it)
            if (!allowed) { if (p.isSprinting()) p.setSprinting(false); if (p.getFoodLevel() > 6) p.setFoodLevel(6); }
            else if (p.getFoodLevel() < 7) p.setFoodLevel(7);

            pool.put(id, s);
            winded.put(id, wind);
        }
    }

    @EventHandler
    public void onToggleSprint(PlayerToggleSprintEvent event) {
        if (!event.isSprinting() || !plugin.getConfig().getBoolean("stamina.enabled", true)) return;
        Player p = event.getPlayer();
        if (!Injuries.affected(p)) return;
        if (isWinded(p) || !plugin.injuries().canSprint(p)) event.setCancelled(true);
    }
}
