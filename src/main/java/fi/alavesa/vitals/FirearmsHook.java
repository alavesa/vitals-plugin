package fi.alavesa.vitals;

import fi.alavesa.firearms.api.FirearmsHitEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Gunshot wounds from the Firearms plugin (0.18.2+). Loaded only when Firearms is present - this class
 * references its event class directly. Severity scales with the damage dealt and the round's caliber;
 * leg hits make you limp, head hits make you reel.
 */
public final class FirearmsHook implements Listener {

    private final VitalsPlugin plugin;

    public FirearmsHook(VitalsPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onHit(FirearmsHitEvent event) {
        if (!(event.victim() instanceof Player p) || !Injuries.affected(p) || event.damage() <= 0) return;
        var c = plugin.getConfig();
        if (event.kind() == FirearmsHitEvent.Kind.FRAG) { plugin.damageListener().shrapnel(p, event.damage()); return; }
        double caliber = c.getDouble("injuries.gunshot.caliber." + event.ammoId().toLowerCase(), 1.0);
        double severity = event.damage() / Math.max(0.1, c.getDouble("injuries.gunshot.damage-per-point", 4.0)) * caliber;
        double bleed = severity * c.getDouble("injuries.gunshot.bleed-per-point", 0.25);
        String zone = event.zone();
        boolean first = !plugin.injuries().has(p, InjuryType.GUNSHOT);
        plugin.injuries().add(p, new Wound(InjuryType.GUNSHOT, severity, bleed, 0, zone),
            first ? "You've been shot" + (zone.equals("legs") ? " in the leg" : zone.equals("head") ? " in the head" : "") + ". You're bleeding - bandage it!" : null);
        if (zone.equals("legs")) plugin.injuries().limp(p, (int) (c.getDouble("injuries.gunshot.legs-limp-seconds", 20) * 20));
        if (zone.equals("head")) {
            int t = (int) (c.getDouble("injuries.gunshot.head-nausea-seconds", 5) * 20);
            if (t > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, t, 0, true, false, false));
        }
        if (!first) p.sendMessage(Component.text("You've been shot again.", NamedTextColor.RED));
    }
}
