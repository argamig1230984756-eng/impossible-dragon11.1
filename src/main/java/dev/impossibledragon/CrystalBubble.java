package dev.impossibledragon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Java Edition crystal shield: a translucent violet sphere with lightning inside, plus the glowing
 * rune circle on the ground. Both are resource-pack models on ItemDisplays (no glass).
 * Bedrock players get the same look from the Bedrock pack's end crystal model instead.
 */
final class CrystalBubble {

    private final ModelManager mgr;
    private final ItemDisplay shell;
    private final ItemDisplay rune;
    private final float shellScale;
    private final float runeScale;
    private final Map<UUID, Set<UUID>> shown = new HashMap<>();
    private int calls = 0;
    private float angle = 0;

    CrystalBubble(ModelManager mgr, Location center, int shellCmd, float shellK, int runeCmd, float runeK, float scale) {
        this.mgr = mgr;
        this.shellScale = shellK * scale;
        this.runeScale = runeK * scale;
        World w = center.getWorld();
        this.shell = make(w, center, shellCmd, shellScale);
        this.rune = make(w, center.clone().add(0, -0.97, 0), runeCmd, runeScale);
    }

    private ItemDisplay make(World w, Location at, int cmd, float s) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(cmd);
        stack.setItemMeta(meta);
        return w.spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setViewRange(0.9f);
            e.setShadowRadius(0f);
            e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s), new Quaternionf()));
        });
    }

    /** called every 10 ticks */
    void tick(List<Player> players) {
        Set<UUID> online = new HashSet<>();
        for (Player p : players) {
            online.add(p.getUniqueId());
            if (mgr.isBedrock(p)) continue;
            Set<UUID> s = shown.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            for (Entity e : new Entity[]{shell, rune}) {
                if (e.isValid() && s.add(e.getUniqueId())) p.showEntity(mgr.plugin(), e);
            }
        }
        shown.keySet().retainAll(online);

        if (++calls % 4 == 0) { // slow spin, interpolated by the client
            angle += (float) (Math.PI * 2 / 3);
            spin(shell, shellScale, angle);
            spin(rune, runeScale, -angle);
        }
    }

    private void spin(ItemDisplay d, float s, float a) {
        if (!d.isValid()) return;
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(a), new Vector3f(s), new Quaternionf()));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(40);
    }

    void remove() {
        if (shell.isValid()) shell.remove();
        if (rune.isValid()) rune.remove();
        shown.clear();
    }
}
