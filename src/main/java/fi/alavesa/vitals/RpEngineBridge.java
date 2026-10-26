package fi.alavesa.vitals;

import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.ContentId;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Optional RP Engine integration. When RP Engine is installed, Vitals ships a content pack
 * (plugins/RPEngine/content/vitals): the three medical items as RP Engine items (so their textures /
 * .bbmodels live in assets/ and can be replaced by the modeler) and two font icons for the HUD bars.
 * Everything degrades to plain items and plain text when RP Engine is missing.
 */
public final class RpEngineBridge {

    public static final String NS = "vitals";
    private final VitalsPlugin plugin;
    private RPEnginePlugin engine;

    public RpEngineBridge(VitalsPlugin plugin) { this.plugin = plugin; }

    public boolean present() { return engine() != null; }

    public RPEnginePlugin engine() {
        if (engine != null && engine.isEnabled()) return engine;
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("RPEngine");
            if (p instanceof RPEnginePlugin e && e.isEnabled()) { engine = e; return e; }
        } catch (Throwable ignored) { }
        return null;
    }

    /** ":vitals:stamina:" -> the icon glyph (RP Engine), or stripped away without it. */
    public String format(String text) {
        if (text == null || text.isEmpty()) return "";
        RPEnginePlugin e = engine();
        if (e != null) { try { return e.icons().formatNow(text); } catch (Throwable ignored) { } }
        return text.replaceAll(":[a-z0-9_.-]+:[a-z0-9_./-]+:", "").trim();
    }

    public Optional<ItemStack> createItem(String id, int amount) {
        RPEnginePlugin e = engine();
        if (e == null) return Optional.empty();
        try { return ContentId.of(NS, id).flatMap(cid -> e.items().create(cid, Math.max(1, amount))); } catch (Throwable t) { return Optional.empty(); }
    }

    public String itemIdOf(ItemStack stack) {
        RPEnginePlugin e = engine();
        if (e == null || stack == null) return null;
        try {
            Optional<ContentId> id = e.items().idOf(stack);
            if (id.isPresent() && NS.equals(id.get().namespace())) return id.get().path();
        } catch (Throwable ignored) { }
        return null;
    }

    // ------------------------------------------------------------------ content pack

    /** Writes the content folder once (never overwrites files the modeler edited). Returns true if something was written. */
    public boolean installContent() {
        File rpe = new File(plugin.getDataFolder().getParentFile(), "RPEngine");
        if (!rpe.isDirectory() && !present()) return false;
        File root = new File(rpe, "content" + File.separator + NS);
        boolean wrote = false;
        try {
            wrote |= writeIfMissing(new File(root, "pack.yml"),
                "name: Vitals\ndescription: Medical items and HUD icons for the Vitals plugin\nversion: " + plugin.getPluginMeta().getVersion() + "\n");
            wrote |= writeIfMissing(new File(root, "items/vitals.yml"), itemsYaml());
            wrote |= writeIfMissing(new File(root, "fonts/icons.yml"),
                "# HUD icons used in the Vitals boss bars (:vitals:stamina: / :vitals:injury:)\n"
                + "stamina:\n  file: stamina     # assets/textures/font/stamina.png\n  height: 8\n  ascent: 7\n"
                + "injury:\n  file: injury\n  height: 8\n  ascent: 7\n");
            wrote |= writePng(new File(root, "assets/textures/item/bandage.png"), Textures.bandage());
            wrote |= writePng(new File(root, "assets/textures/item/first_aid_kit.png"), Textures.firstAidKit());
            wrote |= writePng(new File(root, "assets/textures/item/splint.png"), Textures.splint());
            wrote |= writePng(new File(root, "assets/textures/font/stamina.png"), Textures.staminaIcon());
            wrote |= writePng(new File(root, "assets/textures/font/injury.png"), Textures.injuryIcon());
            wrote |= writeIfMissing(new File(root, "README.txt"),
                "Vitals content pack. Replace the PNGs in assets/textures/item to retexture the medical items, or give an item a\n"
                + "Blockbench model: put it in assets/models/<name>.bbmodel and add `model: <name>` to items/vitals.yml.\n"
                + "Run /rp reload (or /vitals reload) afterwards. Vitals never overwrites files that already exist here.\n");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write the RP Engine content folder: " + e.getMessage());
        }
        return wrote;
    }

    private String itemsYaml() {
        StringBuilder sb = new StringBuilder("# Vitals medical items. material must match plugins/Vitals/config.yml medical.<id>.material\n");
        for (String id : MedicalItems.IDS) {
            var c = plugin.getConfig().getConfigurationSection("medical." + id);
            String mat = c == null ? "PAPER" : c.getString("material", "PAPER");
            String name = c == null ? id : c.getString("name", id);
            sb.append(id).append(":\n  material: ").append(mat).append("\n  name: \"").append(name.replace("\"", "'")).append("\"\n")
              .append("  lore: [\"&7").append(MedicalItems.lore(id)).append("\"]\n")
              .append("  texture: item/").append(id).append("\n  stack: 16\n");
        }
        return sb.toString();
    }

    private static boolean writeIfMissing(File f, String content) throws IOException {
        if (f.exists()) return false;
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
        return true;
    }

    private static boolean writePng(File f, BufferedImage img) throws IOException {
        if (f.exists()) return false;
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
        return true;
    }

    /** Ask RP Engine to rebuild + resend (main thread, can take seconds on a big bundle) - only after a fresh install. */
    public void reloadLater() {
        RPEnginePlugin e = engine();
        if (e == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try { plugin.getLogger().info("Asking RP Engine to rebuild its packs (new vitals content)..."); e.reload(); }
            catch (Throwable t) { plugin.getLogger().warning("RP Engine reload failed: " + t); }
        }, 100L);
    }
}
