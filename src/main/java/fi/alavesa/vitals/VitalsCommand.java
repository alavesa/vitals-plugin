package fi.alavesa.vitals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class VitalsCommand implements TabExecutor {

    private final VitalsPlugin plugin;

    public VitalsCommand(VitalsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) return status(s, s instanceof Player p ? p : null);
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "status", "info" -> { return status(s, a.length > 1 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player p ? p : null); }
            case "hud" -> {
                if (!(s instanceof Player p)) return err(s, "Players only.");
                boolean on = a.length < 2 || !a[1].equalsIgnoreCase("off");
                plugin.hud().toggle(p, on);
                return ok(s, "Vitals HUD " + (on ? "on" : "off") + ".");
            }
            case "version" -> { return ok(s, "Vitals " + plugin.getPluginMeta().getVersion() + " (jar: " + jarName() + ")"); }
            case "help" -> { return usage(s); }
            default -> { }
        }
        if (!s.hasPermission("vitals.admin")) return err(s, "No permission.");
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (a.length < 2) return err(s, "/vitals give <" + String.join("|", MedicalItems.IDS) + "> [player] [amount]");
                String id = a[1].toLowerCase(Locale.ROOT).replace('-', '_');
                if (id.equals("medkit") || id.equals("firstaid")) id = "first_aid_kit";
                Player target = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player p ? p : null;
                if (target == null) return err(s, "Player not found.");
                int n = 1; if (a.length > 3) try { n = Math.max(1, Integer.parseInt(a[3])); } catch (NumberFormatException e) { return err(s, "Amount?"); }
                ItemStack it = plugin.medical().create(id, n);
                if (it == null) return err(s, "Unknown item. Items: " + String.join(", ", MedicalItems.IDS));
                target.getInventory().addItem(it).values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                return ok(s, "Gave " + n + "x " + id + " to " + target.getName() + ".");
            }
            case "heal", "cure" -> {
                Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player p ? p : null;
                if (target == null) return err(s, "Player not found.");
                plugin.injuries().cureAll(target);
                plugin.stamina().set(target, plugin.getConfig().getDouble("stamina.max", 100));
                return ok(s, target.getName() + " fully healed.");
            }
            case "injure" -> {
                if (a.length < 3) return err(s, "/vitals injure <player> <gunshot|cut|fracture|bruise|burn|corrosion> [severity] [bleed hp/s] [zone]");
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) return err(s, "Player not found.");
                InjuryType type; try { type = InjuryType.valueOf(a[2].toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return err(s, "Unknown injury type."); }
                double sev = a.length > 3 ? Double.parseDouble(a[3]) : 1.0;
                double bleed = a.length > 4 ? Double.parseDouble(a[4]) : (type == InjuryType.GUNSHOT ? sev * plugin.getConfig().getDouble("injuries.gunshot.bleed-per-point", 0.25) : type == InjuryType.CUT ? 0.15 : 0);
                String zone = a.length > 5 ? a[5] : type == InjuryType.FRACTURE ? "legs" : "body";
                Wound w = new Wound(type, sev, bleed, type == InjuryType.BRUISE ? 0.05 : type == InjuryType.CUT ? 0.02 : 0, zone);
                if (type == InjuryType.CORROSION) w.dot(plugin.getConfig().getDouble("injuries.corrosion.damage-per-second", 0.5), plugin.getConfig().getDouble("injuries.corrosion.seconds", 12));
                plugin.injuries().add(target, w, "You've been injured (" + type.label.toLowerCase(Locale.ROOT) + ").");
                return ok(s, "Added " + type + " x" + sev + " to " + target.getName() + ". Now: " + plugin.injuries().describe(target));
            }
            case "stamina" -> {
                if (a.length < 3) return err(s, "/vitals stamina <player> <0-100>");
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) return err(s, "Player not found.");
                plugin.stamina().set(target, Double.parseDouble(a[2]));
                return ok(s, target.getName() + " stamina = " + Injuries.fmt(plugin.stamina().value(target)));
            }
            case "reload" -> {
                plugin.reloadConfig();
                if (plugin.getConfig().getBoolean("rpengine.install-content", true) && plugin.rpe().installContent()) plugin.rpe().reloadLater();
                for (Player p : Bukkit.getOnlinePlayers()) plugin.injuries().refresh(p);
                return ok(s, "Vitals config reloaded.");
            }
            default -> { return usage(s); }
        }
    }

    private boolean status(CommandSender s, Player p) {
        if (p == null) return err(s, "Player not found.");
        Injuries inj = plugin.injuries();
        s.sendMessage(Component.text("--- " + p.getName() + " ---", NamedTextColor.GOLD));
        s.sendMessage(Component.text("Health " + Math.round(Injuries.healthFraction(p) * 100) + "%  Stamina " + Math.round(plugin.stamina().fraction(p) * 100) + "%" + (plugin.stamina().isWinded(p) ? " (winded)" : ""), NamedTextColor.YELLOW));
        s.sendMessage(Component.text("Injuries: " + inj.describe(p) + "  (" + Injuries.fmt(inj.points(p)) + " pts, bleeding " + Injuries.fmt(inj.bleedRate(p)) + " hp/s)", NamedTextColor.RED));
        s.sendMessage(Component.text("Walk speed " + Math.round(inj.speedFactor(p) * 100) + "%, sprint " + (inj.canSprint(p) ? "allowed" : "blocked"), NamedTextColor.GRAY));
        return true;
    }

    private String jarName() { try { return new java.io.File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getName(); } catch (Exception e) { return "?"; } }

    private boolean usage(CommandSender s) {
        s.sendMessage(Component.text("/vitals status [player] | hud on|off | version", NamedTextColor.YELLOW));
        if (s.hasPermission("vitals.admin")) s.sendMessage(Component.text("/vitals give <bandage|first_aid_kit|splint> [player] [n] | heal [player] | injure <player> <type> [severity] [bleed] [zone] | stamina <player> <n> | reload", NamedTextColor.YELLOW));
        return true;
    }

    private boolean ok(CommandSender s, String m) { s.sendMessage(Component.text(m, NamedTextColor.GREEN)); return true; }
    private boolean err(CommandSender s, String m) { s.sendMessage(Component.text(m, NamedTextColor.RED)); return true; }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) return filter(Stream.of("status", "hud", "version", "give", "heal", "injure", "stamina", "reload"), a[0]);
        if (a.length == 2 && a[0].equalsIgnoreCase("give")) return filter(MedicalItems.IDS.stream(), a[1]);
        if (a.length == 2 && a[0].equalsIgnoreCase("hud")) return filter(Stream.of("on", "off"), a[1]);
        if (a.length == 3 && a[0].equalsIgnoreCase("injure")) return filter(Stream.of(InjuryType.values()).map(t -> t.name().toLowerCase(Locale.ROOT)), a[2]);
        if (a.length == 2 || a.length == 3) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), a[a.length - 1]);
        return List.of();
    }

    private static List<String> filter(Stream<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        opts.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).forEach(out::add);
        return out;
    }
}
