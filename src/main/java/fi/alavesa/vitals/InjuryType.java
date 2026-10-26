package fi.alavesa.vitals;

/** The kinds of wound a player can carry. Labels are what the HUD / status print. */
public enum InjuryType {
    GUNSHOT("Gunshot wound"),
    CUT("Laceration"),
    FRACTURE("Broken leg"),
    BRUISE("Bruised"),
    BURN("Burns"),
    CORROSION("Corroded");

    public final String label;
    InjuryType(String label) { this.label = label; }
}
