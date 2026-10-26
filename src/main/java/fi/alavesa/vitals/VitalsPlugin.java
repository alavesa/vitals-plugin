package fi.alavesa.vitals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Vitals - SCP: Containment Breach style stamina and injuries.
 * Stamina (boss bar) + wounds (gunshot / fall / burn / corrosion / cuts, bleeding, broken legs), walk speed
 * that drops with lost health, sprint lock, medical items delivered through RP Engine.
 */
public final class VitalsPlugin extends JavaPlugin implements Listener {

    private RpEngineBridge rpe;
    private Hud hud;
    private Stamina stamina;
    private Injuries injuries;
    private MedicalItems medical;
    private DamageListener damageListener;
    private final List<String> startupWarnings = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        duplicateJarGuard();
        rpe = new RpEngineBridge(this);
        hud = new Hud(this);
        stamina = new Stamina(this);
        injuries = new Injuries(this);
        medical = new MedicalItems(this);
        damageListener = new DamageListener(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(stamina, this);
        pm.registerEvents(medical, this);
        pm.registerEvents(damageListener, this);
        pm.registerEvents(this, this);
        hookFirearms();

        var sch = getServer().getScheduler();
        sch.runTaskTimer(this, stamina, 20L, Stamina.PERIOD);
        sch.runTaskTimer(this, injuries, 22L, Injuries.PERIOD);
        sch.runTaskTimer(this, medical, 23L, Injuries.PERIOD);
        sch.runTaskTimer(this, hud, 24L, 5L);
        sch.runTaskTimer(this, () -> { for (Player p : Bukkit.getOnlinePlayers()) injuries.save(p); }, 600L, 600L);

        getCommand("vitals").setExecutor(new VitalsCommand(this));
        for (Player p : Bukkit.getOnlinePlayers()) injuries.load(p);

        if (getConfig().getBoolean("rpengine.install-content", true) && rpe.installContent()) {
            getLogger().info("Wrote the RP Engine content pack to plugins/RPEngine/content/vitals");
            if (getConfig().getBoolean("rpengine.reload-after-install", true)) rpe.reloadLater();
        }
        getLogger().info("RP Engine: " + (rpe.present() ? "present - medical items + HUD icons come from content/vitals" : "not found - plain items, no icons"));
        if (pm.getPlugin("Labra") != null && !labraSprintOff()) {
            String w = "Labra's own sprint stamina is still ON - both plugins would fight over sprinting. Set `sprint:\n  enabled: false` in plugins/Labra/config.yml (Labra 0.57.1+) and restart.";
            getLogger().warning(w); startupWarnings.add(w);
        }
        getLogger().info("Vitals " + getPluginMeta().getVersion() + " enabled");
    }

    private boolean labraSprintOff() {
        File f = new File(getDataFolder().getParentFile(), "Labra" + File.separator + "config.yml");
        if (!f.exists()) return false;
        return !org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f).getBoolean("sprint.enabled", true);
    }

    private void hookFirearms() {
        if (getServer().getPluginManager().getPlugin("Firearms") == null) { getLogger().info("Firearms not found - no gunshot wounds (vanilla damage still counts)"); return; }
        try {
            Class.forName("fi.alavesa.firearms.api.FirearmsHitEvent");
            getServer().getPluginManager().registerEvents(new FirearmsHook(this), this);
            getLogger().info("Firearms found - gunshot wounds via FirearmsHitEvent");
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            String w = "Firearms is too old for gunshot wounds - needs Firearms 0.18.2+ (FirearmsHitEvent).";
            getLogger().warning(w); startupWarnings.add(w);
        }
    }

    /** Paper loads only the first of two same-named jars - warn loudly, the family keeps tripping over this. */
    private void duplicateJarGuard() {
        File dir = getDataFolder().getParentFile();
        File[] jars = dir == null ? null : dir.listFiles((d, n) -> n.toLowerCase().startsWith("vitals-") && n.toLowerCase().endsWith(".jar"));
        if (jars != null && jars.length > 1) {
            StringBuilder sb = new StringBuilder("MULTIPLE Vitals jars in plugins/: ");
            for (File j : jars) sb.append(j.getName()).append(' ');
            sb.append("- delete the old one, Paper loads only the first by name!");
            getLogger().severe(sb.toString()); startupWarnings.add(sb.toString());
        }
    }

    @EventHandler
    public void onAdminJoin(PlayerJoinEvent event) {
        if (!startupWarnings.isEmpty() && event.getPlayer().hasPermission("vitals.admin"))
            for (String w : startupWarnings) event.getPlayer().sendMessage(Component.text("[Vitals] " + w, NamedTextColor.RED));
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) { injuries.save(p); injuries.clearModifiers(p); medical.cancel(p, false); }
        hud.hideAll();
    }

    public RpEngineBridge rpe() { return rpe; }
    public Hud hud() { return hud; }
    public Stamina stamina() { return stamina; }
    public Injuries injuries() { return injuries; }
    public MedicalItems medical() { return medical; }
    public DamageListener damageListener() { return damageListener; }
}
