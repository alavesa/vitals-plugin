package fi.alavesa.vitals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SCP:CB health model. Vanilla health is the blood/HP pool; on top of it every player carries a list of
 * {@link Wound}s. Wounds bleed (hp drains until bandaged), slow you down (walk speed shrinks with lost health
 * AND with injury points), block sprinting (low health, broken leg), and tint the screen when you are close
 * to passing out. Nothing heals by itself except scratches, bruises and (very slowly) burns - like the game,
 * you need a first aid kit. Ticked every 5 ticks (0.25 s).
 */
public final class Injuries implements Runnable {

    static final int PERIOD = 5;
    private static final double DT = PERIOD / 20.0;

    public static final class State {
        public final List<Wound> wounds = new ArrayList<>();
        double appliedSpeed = Double.NaN, appliedJump = Double.NaN;
        public int limpTicks;
        boolean bledOut;
        int hurtSoundCooldown, heartbeat;
        double regenAccum;
    }

    private final VitalsPlugin plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final NamespacedKey speedKey, jumpKey, saveKey;

    public Injuries(VitalsPlugin plugin) {
        this.plugin = plugin;
        speedKey = new NamespacedKey(plugin, "injury_speed");
        jumpKey = new NamespacedKey(plugin, "injury_jump");
        saveKey = new NamespacedKey(plugin, "state");
    }

    public State state(Player p) { return states.computeIfAbsent(p.getUniqueId(), k -> new State()); }
    public boolean hasWounds(Player p) { State s = states.get(p.getUniqueId()); return s != null && !s.wounds.isEmpty(); }
    public double points(Player p) { double t = 0; for (Wound w : state(p).wounds) t += w.severity; return t; }
    public double bleedRate(Player p) { double t = 0; for (Wound w : state(p).wounds) t += w.bleed; return t; }
    public boolean has(Player p, InjuryType type) { for (Wound w : state(p).wounds) if (w.type == type) return true; return false; }
    public boolean isLimping(Player p) { return state(p).limpTicks > 0; }
    public boolean bledOut(Player p) { State s = states.get(p.getUniqueId()); return s != null && s.bledOut; }

    static boolean affected(Player p) {
        return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.hasPermission("vitals.immune") && !p.isDead();
    }

    static double healthFraction(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        double max = a == null ? 20 : a.getValue();
        return max <= 0 ? 1 : Math.max(0, Math.min(1, p.getHealth() / max));
    }

    // ------------------------------------------------------------------ wounds

    /** Adds a wound (merging into an existing wound of the same type for burns / corrosion) and tells the player. */
    public void add(Player p, Wound wound, String message) {
        if (!affected(p) || wound == null || wound.severity <= 0) return;
        State s = state(p);
        if (wound.type == InjuryType.BURN || wound.type == InjuryType.CORROSION || wound.type == InjuryType.FRACTURE) {
            for (Wound w : s.wounds) {
                if (w.type != wound.type) continue;
                double cap = wound.type == InjuryType.BURN ? cfg().getDouble("injuries.burn.max-points", 3.0) : Double.MAX_VALUE;
                w.severity = Math.min(cap, w.severity + wound.severity);
                w.bleed = Math.max(w.bleed, wound.bleed);
                w.dot = Math.max(w.dot, wound.dot); w.dotSeconds = Math.max(w.dotSeconds, wound.dotSeconds);
                if (message != null && wound.type != InjuryType.BURN) p.sendMessage(Component.text(message, NamedTextColor.RED));
                refresh(p);
                return;
            }
        }
        s.wounds.add(wound);
        if (message != null) p.sendMessage(Component.text(message, NamedTextColor.RED));
        refresh(p);
    }

    public void limp(Player p, int ticks) { State s = state(p); s.limpTicks = Math.max(s.limpTicks, ticks); }

    /** Bandage: every wound stops bleeding. */
    public int bandage(Player p) {
        int n = 0;
        for (Wound w : state(p).wounds) if (w.bleed > 0) { w.bleed = 0; n++; }
        refresh(p);
        return n;
    }

    /** First aid kit: stops bleeding and acid, and takes `points` severity off every wound. */
    public void firstAid(Player p, double points, boolean fixesFractures) {
        State s = state(p);
        for (var it = s.wounds.iterator(); it.hasNext(); ) {
            Wound w = it.next();
            w.bleed = 0; w.dot = 0; w.dotSeconds = 0;
            if (w.type == InjuryType.FRACTURE && !fixesFractures) continue;
            w.severity -= points;
            if (w.severity <= 0.001) it.remove();
        }
        s.limpTicks = 0;
        refresh(p);
    }

    public boolean splint(Player p) {
        boolean any = state(p).wounds.removeIf(w -> w.type == InjuryType.FRACTURE);
        refresh(p);
        return any;
    }

    public void cureAll(Player p) {
        State s = state(p);
        s.wounds.clear(); s.limpTicks = 0; s.bledOut = false;
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(a == null ? 20 : a.getValue());
        refresh(p);
    }

    public void reset(Player p) {
        State s = state(p);
        s.wounds.clear(); s.limpTicks = 0; s.bledOut = false;
        clearModifiers(p);
        s.appliedSpeed = Double.NaN; s.appliedJump = Double.NaN;
        save(p);
    }

    public void forget(UUID id) { states.remove(id); }

    // ------------------------------------------------------------------ derived

    public boolean canSprint(Player p) {
        if (!affected(p)) return true;
        State s = state(p);
        if (healthFraction(p) < cfg().getDouble("sprint.min-health", 0.3)) return false;
        if (s.limpTicks > 0) return false;
        if (cfg().getBoolean("sprint.blocked-by-fracture", true) && has(p, InjuryType.FRACTURE)) return false;
        return true;
    }

    public double staminaDrainMultiplier(Player p) {
        if (!affected(p)) return 1;
        double m = 1 + points(p) * cfg().getDouble("stamina.injury-drain-per-point", 0.25);
        if (bleedRate(p) > 0) m += cfg().getDouble("stamina.bleeding-drain-bonus", 0.5);
        m += (1 - healthFraction(p)) * cfg().getDouble("stamina.low-health-drain", 1.0);
        return Math.max(0.1, m);
    }

    public double speedFactor(Player p) {
        FileConfiguration c = cfg();
        double lost = 1 - healthFraction(p);
        double f = 1 - c.getDouble("speed.lost-health.max-penalty", 0.5) * Math.pow(lost, c.getDouble("speed.lost-health.curve", 1.4));
        double pts = Math.min(points(p), c.getDouble("speed.injury.cap", 5));
        f *= 1 - pts * c.getDouble("speed.injury.per-point", 0.08);
        if (has(p, InjuryType.FRACTURE)) f *= c.getDouble("speed.fracture-factor", 0.6);
        if (state(p).limpTicks > 0) f *= c.getDouble("speed.limp-factor", 0.8);
        return Math.max(c.getDouble("speed.min-factor", 0.25), Math.min(1, f));
    }

    public String describe(Player p) {
        State s = state(p);
        if (s.wounds.isEmpty() && s.limpTicks <= 0) return "No injuries";
        List<String> parts = new ArrayList<>();
        if (bleedRate(p) > 0) parts.add("Bleeding");
        if (s.limpTicks > 0) parts.add("Limping");
        for (Wound w : s.wounds) parts.add(w.label() + (w.dot > 0 ? " (burning)" : ""));
        return String.join(" · ", parts);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (!affected(p)) { if (s != null && !Double.isNaN(s.appliedSpeed)) { clearModifiers(p); s.appliedSpeed = Double.NaN; s.appliedJump = Double.NaN; } continue; }
            if (s == null) { refresh(p); continue; }
            tick(p, s);
        }
    }

    private void tick(Player p, State s) {
        FileConfiguration c = cfg();
        boolean changed = false;
        double bleed = 0, dot = 0;
        double clotBelow = c.getDouble("bleeding.clot-below", 1.0), clotRate = c.getDouble("bleeding.clot-rate", 0.03);
        for (var it = s.wounds.iterator(); it.hasNext(); ) {
            Wound w = it.next();
            if (w.bleed > 0 && w.severity < clotBelow) { w.bleed = Math.max(0, w.bleed - clotRate * DT); if (w.bleed == 0) changed = true; }
            bleed += w.bleed;
            if (w.dot > 0 && w.dotSeconds > 0) { dot += w.dot; w.dotSeconds -= DT; if (w.dotSeconds <= 0) { w.dot = 0; changed = true; } }
            if (w.heal > 0) { w.severity -= w.heal * DT; if (w.severity <= 0.001) { it.remove(); changed = true; } }
        }
        if (s.limpTicks > 0) { s.limpTicks -= PERIOD; if (s.limpTicks <= 0) { s.limpTicks = 0; changed = true; p.sendMessage(Component.text("Your leg feels steadier.", NamedTextColor.GRAY)); } }

        double drain = (bleed + dot) * DT;
        if (drain > 0) {
            double hp = p.getHealth();
            if (hp - drain <= 0.001) {
                s.bledOut = true;
                p.setNoDamageTicks(0);
                p.damage(Math.max(drain, hp + 1));
                if (!p.isDead() && p.getHealth() > 0) p.setHealth(0);
                return;
            }
            p.setHealth(hp - drain);
            if (bleed > 0 && c.getBoolean("bleeding.particles", true) && Bukkit.getCurrentTick() % 10 == 0)
                p.getWorld().spawnParticle(Particle.BLOCK, p.getLocation().add(0, 0.9, 0), 4, 0.2, 0.3, 0.2, 0.02, Material.REDSTONE_BLOCK.createBlockData());
            if (s.hurtSoundCooldown-- <= 0) {
                s.hurtSoundCooldown = (int) (c.getDouble("bleeding.hurt-sound-every-seconds", 4) * 20 / PERIOD);
                p.playSound(p.getLocation(), dot > 0 ? "minecraft:entity.player.hurt_on_fire" : "minecraft:entity.player.hurt", 0.5f, 0.9f);
            }
        } else if (s.wounds.isEmpty()) {
            double regen = c.getDouble("regen.hp-per-second", 0);
            if (regen > 0 && healthFraction(p) < 1) {
                AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
                p.setHealth(Math.min(a == null ? 20 : a.getValue(), p.getHealth() + regen * DT));
            }
        }

        // close to passing out: tunnel vision, nausea, heartbeat
        double hf = healthFraction(p);
        if (hf < c.getDouble("effects.darkness-below", 0.35)) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, true, false, false));
        if (hf < c.getDouble("effects.nausea-below", 0.2)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 70, 0, true, false, false));
            if (c.getBoolean("effects.heartbeat", true) && (s.heartbeat += PERIOD) >= 30) { s.heartbeat = 0; p.playSound(p.getLocation(), "minecraft:entity.warden.heartbeat", 0.9f, 1.0f); }
        }

        if (!canSprint(p) && p.isSprinting()) p.setSprinting(false);
        applyModifiers(p, s);
        if (changed) save(p);
    }

    /** Re-evaluate speed/jump after anything changed. Safe to call often. */
    public void refresh(Player p) {
        State s = state(p);
        if (!affected(p)) { clearModifiers(p); s.appliedSpeed = Double.NaN; s.appliedJump = Double.NaN; return; }
        applyModifiers(p, s);
        save(p);
    }

    private void applyModifiers(Player p, State s) {
        double speed = speedFactor(p);
        double jump = has(p, InjuryType.FRACTURE) ? cfg().getDouble("speed.fracture-jump-factor", 0.3) : 1.0;
        if (Math.abs(speed - s.appliedSpeed) > 0.004) { set(p.getAttribute(Attribute.MOVEMENT_SPEED), speedKey, speed - 1); s.appliedSpeed = speed; }
        if (Math.abs(jump - s.appliedJump) > 0.004) { set(p.getAttribute(Attribute.JUMP_STRENGTH), jumpKey, jump - 1); s.appliedJump = jump; }
    }

    private void set(AttributeInstance inst, NamespacedKey key, double amount) {
        if (inst == null) return;
        try { inst.removeModifier(key); } catch (Throwable ignored) { }
        if (Math.abs(amount) < 0.002) return;
        inst.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    public void clearModifiers(Player p) {
        set(p.getAttribute(Attribute.MOVEMENT_SPEED), speedKey, 0);
        set(p.getAttribute(Attribute.JUMP_STRENGTH), jumpKey, 0);
    }

    // ------------------------------------------------------------------ persistence (player PDC)

    public void save(Player p) {
        State s = states.get(p.getUniqueId());
        var pdc = p.getPersistentDataContainer();
        if (s == null || (s.wounds.isEmpty() && s.limpTicks <= 0)) { pdc.remove(saveKey); return; }
        StringBuilder sb = new StringBuilder("limp=").append(s.limpTicks);
        for (Wound w : s.wounds) sb.append('\n').append(w.serialize());
        pdc.set(saveKey, PersistentDataType.STRING, sb.toString());
    }

    public void load(Player p) {
        State s = state(p);
        s.wounds.clear(); s.limpTicks = 0; s.appliedSpeed = Double.NaN; s.appliedJump = Double.NaN;
        String raw = p.getPersistentDataContainer().get(saveKey, PersistentDataType.STRING);
        if (raw != null) {
            for (String line : raw.split("\n")) {
                if (line.startsWith("limp=")) { try { s.limpTicks = Integer.parseInt(line.substring(5)); } catch (NumberFormatException ignored) { } continue; }
                Wound w = Wound.parse(line);
                if (w != null) s.wounds.add(w);
            }
        }
        refresh(p);
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }
}
