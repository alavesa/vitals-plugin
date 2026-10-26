package fi.alavesa.vitals;

import java.util.Locale;

/**
 * One wound. {@code severity} is the SCP:CB "Injuries" scale: 0.5 = a scratch, 1 = one pistol round,
 * 3+ = you are in real trouble. {@code bleed} is hp lost per second; {@code heal} is how many severity
 * points fade per second on their own (0 = stays until treated); {@code dot} is direct damage per
 * second for {@code dotSeconds} (acid eating you).
 */
public final class Wound {
    public final InjuryType type;
    public double severity;
    public double bleed;
    public double heal;
    public double dot;
    public double dotSeconds;
    public final String zone;

    public Wound(InjuryType type, double severity, double bleed, double heal, String zone) {
        this.type = type; this.severity = severity; this.bleed = bleed; this.heal = heal; this.zone = zone == null ? "" : zone;
    }

    public Wound dot(double perSecond, double seconds) { this.dot = perSecond; this.dotSeconds = seconds; return this; }

    public String label() {
        return zone.isEmpty() || zone.equals("body") ? type.label : type.label + " (" + zone + ")";
    }

    public String serialize() {
        return String.format(Locale.ROOT, "%s;%.4f;%.4f;%.4f;%.4f;%.2f;%s", type.name(), severity, bleed, heal, dot, dotSeconds, zone);
    }

    public static Wound parse(String s) {
        try {
            String[] p = s.split(";", -1);
            Wound w = new Wound(InjuryType.valueOf(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), p.length > 6 ? p[6] : "");
            w.dot = Double.parseDouble(p[4]); w.dotSeconds = Double.parseDouble(p[5]);
            return w;
        } catch (Exception e) { return null; }
    }
}
