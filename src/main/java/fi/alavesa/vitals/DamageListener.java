package fi.alavesa.vitals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** Turns vanilla damage into wounds, gates regeneration, and handles join/quit/death bookkeeping. */
public final class DamageListener implements Listener {

    private final VitalsPlugin plugin;

    public DamageListener(VitalsPlugin plugin) { this.plugin = plugin; }

    private FileConfiguration c() { return plugin.getConfig(); }
    private Injuries inj() { return plugin.injuries(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p) || !Injuries.affected(p)) return;
        double dmg = event.getFinalDamage();
        if (dmg <= 0) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        Entity attacker = event instanceof EntityDamageByEntityEvent by ? by.getDamager() : null;
        if (attacker instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) attacker = shooter;

        if (attacker != null && isCorrosive(attacker) || c().getStringList("injuries.corrosion.causes").stream().anyMatch(s -> s.equalsIgnoreCase(cause.name()))) {
            Wound w = new Wound(InjuryType.CORROSION, c().getDouble("injuries.corrosion.points", 1.5), 0, c().getDouble("injuries.corrosion.heal-per-second", 0.02), "body")
                .dot(c().getDouble("injuries.corrosion.damage-per-second", 0.5), c().getDouble("injuries.corrosion.seconds", 12));
            inj().add(p, w, "Something is eating into your skin!");
            return;
        }
        switch (cause) {
            case FALL -> {
                if (dmg >= c().getDouble("injuries.fall.fracture-min-damage", 6.0)) {
                    boolean had = inj().has(p, InjuryType.FRACTURE);
                    inj().add(p, new Wound(InjuryType.FRACTURE, c().getDouble("injuries.fall.fracture-points", 2.0), 0, 0, "legs"),
                        had ? null : "You feel your leg break under you. You can barely walk - find a splint.");
                } else if (dmg >= c().getDouble("injuries.fall.bruise-min-damage", 1.0)) {
                    inj().add(p, new Wound(InjuryType.BRUISE, dmg * c().getDouble("injuries.fall.bruise-points-per-damage", 0.15), 0, c().getDouble("injuries.fall.bruise-heal-per-second", 0.05), "legs"), null);
                }
            }
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> {
                boolean had = inj().has(p, InjuryType.BURN);
                inj().add(p, new Wound(InjuryType.BURN, dmg * c().getDouble("injuries.burn.points-per-damage", 0.3), 0, c().getDouble("injuries.burn.heal-per-second", 0.01), "body"),
                    had ? null : "You've been burned.");
            }
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE -> {
                if (attacker == null) return;
                boolean sharp = cause == EntityDamageEvent.DamageCause.PROJECTILE && !(attacker instanceof Player);
                if (attacker instanceof org.bukkit.entity.LivingEntity le && le.getEquipment() != null) sharp |= isSharp(le.getEquipment().getItemInMainHand());
                if (event instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Projectile pr) sharp = isSharpProjectile(pr);
                if (!sharp) return;
                inj().add(p, new Wound(InjuryType.CUT, dmg * c().getDouble("injuries.cut.points-per-damage", 0.1), dmg * c().getDouble("injuries.cut.bleed-per-damage", 0.04), c().getDouble("injuries.cut.heal-per-second", 0.02), "body"),
                    "You're cut and bleeding.");
            }
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> shrapnel(p, dmg);
            default -> { }
        }
    }

    /** Explosions (vanilla or Firearms frag): shrapnel cuts + a bruise. */
    void shrapnel(Player p, double dmg) {
        inj().add(p, new Wound(InjuryType.CUT, dmg * c().getDouble("injuries.explosion.points-per-damage", 0.12), dmg * c().getDouble("injuries.explosion.bleed-per-damage", 0.03), c().getDouble("injuries.explosion.heal-per-second", 0.01), "body"),
            "Shrapnel tears into you - you're bleeding.");
    }

    private boolean isCorrosive(Entity e) {
        for (String tag : c().getStringList("injuries.corrosion.entity-tags")) {
            String t = tag.toLowerCase(Locale.ROOT);
            for (String s : e.getScoreboardTags()) if (s.toLowerCase(Locale.ROOT).contains(t)) return true;
            String name = e.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).replace(" ", "").contains(t)) return true;
        }
        return false;
    }

    private static boolean isSharp(ItemStack it) {
        if (it == null) return false;
        String n = it.getType().name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || it.getType() == Material.TRIDENT || it.getType() == Material.SHEARS;
    }

    private static boolean isSharpProjectile(Projectile pr) {
        return pr instanceof org.bukkit.entity.AbstractArrow;   // arrows + tridents
    }

    // ------------------------------------------------------------------ regen

    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player p) || !Injuries.affected(p)) return;
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED && event.getRegainReason() != EntityRegainHealthEvent.RegainReason.REGEN) return;
        boolean wounded = inj().hasWounds(p);
        if (wounded ? !c().getBoolean("regen.vanilla-when-wounded", false) : !c().getBoolean("regen.vanilla-when-healthy", true)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ cure-all items (SCP-500 etc.)

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) { if (isCureAll(event.getItem())) cure(event.getPlayer()); }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() != null && isCureAll(event.getItem()) && inj().hasWounds(event.getPlayer())) cure(event.getPlayer());
    }

    private boolean isCureAll(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        for (String s : it.getItemMeta().getCustomModelDataComponent().getStrings())
            for (String want : c().getStringList("medical.cure-all-model-strings")) if (s.equalsIgnoreCase(want)) return true;
        return false;
    }

    private void cure(Player p) {
        if (!inj().hasWounds(p) && !inj().isLimping(p)) return;
        inj().cureAll(p);
        p.sendMessage(Component.text("Your wounds close. You feel whole again.", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { inj().load(event.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        inj().save(p);
        inj().clearModifiers(p);
        plugin.hud().forget(p.getUniqueId());
        plugin.stamina().forget(p.getUniqueId());
        plugin.medical().cancel(p, false);
        inj().forget(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (inj().bledOut(p)) {
            String msg = c().getString("bleeding.death-message", "%player% bled out");
            if (msg != null && !msg.isEmpty()) event.deathMessage(Component.text(msg.replace("%player%", p.getName())));
        }
        plugin.medical().cancel(p, false);
        inj().reset(p);
        plugin.stamina().set(p, c().getDouble("stamina.max", 100));
        plugin.hud().hide(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) { inj().reset(event.getPlayer()); }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> inj().refresh(event.getPlayer()));
    }
}
