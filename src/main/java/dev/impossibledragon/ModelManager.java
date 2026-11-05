package dev.impossibledragon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the resource-pack models for JAVA players (ItemDisplays with custom models).
 * Bedrock players don't need anything from the plugin: the Bedrock pack replaces the entity models.
 */
public final class ModelManager implements Listener {

    static final int PERIOD = 2; // ticks between model updates

    private final ImpossibleDragon plugin;
    private final List<ModelInstance> instances = new ArrayList<>();
    private final Map<UUID, Boolean> bedrockCache = new HashMap<>();
    private final Map<String, ModelDef> defs = new HashMap<>();
    private JsonObject bubbleCfg = null;
    private JsonObject runeCfg = null;
    private final BukkitTask task;
    private long tick = 0;

    public ModelManager(ImpossibleDragon plugin) {
        this.plugin = plugin;
        try (InputStream in = plugin.getResource("models.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject fams = root.getAsJsonObject("families");
                for (String key : fams.keySet()) defs.put(key, new ModelDef(fams.getAsJsonObject(key)));
                bubbleCfg = root.getAsJsonObject("bubble");
                runeCfg = root.getAsJsonObject("rune");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not load models.json: " + ex);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, 2L, PERIOD);
    }

    ImpossibleDragon plugin() {
        return plugin;
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("models.enabled", true);
    }

    boolean flipFacing() {
        return plugin.getConfig().getBoolean("dragon.flip-facing", false);
    }

    Display.Brightness modelBrightness() {
        int b = Math.max(0, Math.min(15, plugin.getConfig().getInt("models.brightness", 11)));
        return new Display.Brightness(b, b);
    }

    boolean isBedrock(Player p) {
        return bedrockCache.computeIfAbsent(p.getUniqueId(), id -> {
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object inst = api.getMethod("getInstance").invoke(null);
                return (boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, id);
            } catch (Throwable ignored) {
                return id.getMostSignificantBits() == 0L;
            }
        });
    }

    // ------------------------------------------------------------------ resource pack for Java players

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        String url = plugin.getConfig().getString("models.java-resource-pack.url", "");
        if (url == null || url.isBlank()) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || isBedrock(p)) return;
            String sha = plugin.getConfig().getString("models.java-resource-pack.sha1", "");
            byte[] hash = null;
            if (sha != null && sha.length() == 40) {
                try {
                    hash = new byte[20];
                    for (int i = 0; i < 20; i++) hash[i] = (byte) Integer.parseInt(sha.substring(i * 2, i * 2 + 2), 16);
                } catch (NumberFormatException ex) {
                    hash = null;
                }
            }
            p.setResourcePack(url, hash, plugin.getConfig().getBoolean("models.java-resource-pack.required", false));
        }, 40L);
    }

    // ------------------------------------------------------------------ factories

    /**
     * Dragon models. Two modes:
     *  - models.dragon.enabled: the plugin draws a whole custom dragon (old mode).
     *  - models.three-heads.enabled (default): the vanilla dragon (Better Enderdragon texture) keeps its own
     *    middle head, and the plugin glues TWO extra necks + heads to it, so it has three heads.
     */
    public void attachDragon(Entity dragon) {
        if (!enabled()) return;
        ModelDef d = defs.get("dragon");
        if (d != null && plugin.getConfig().getBoolean("models.dragon.enabled", false)) {
            float scale = (float) plugin.getConfig().getDouble("models.dragon.scale", 0.85);
            float yo = (float) plugin.getConfig().getDouble("models.dragon.y-offset", 2.0);
            instances.add(new ModelInstance(this, dragon, d, ModelInstance.Mode.DRAGON, scale, yo, false));
            return;
        }
        ModelDef side = defs.get("sideheads");
        if (side != null && plugin.getConfig().getBoolean("models.three-heads.enabled", true)) {
            ModelInstance mi = new ModelInstance(this, dragon, side, ModelInstance.Mode.DRAGON, 1.0f, 0f, false);
            mi.triple = true; // side heads use the vanilla neck/head maths (see ModelInstance.updateTriple)
            instances.add(mi);
        }
    }

    public void detachDragon(Entity dragon) {
        for (ModelInstance m : instances) if (m.anchorIs(dragon)) m.remove();
    }

    /** Minions are endermites (replaced by the dragonling model); they get tougher stats. */
    public void attachMinion(Entity mob) {
        if (mob.getType() != EntityType.ENDERMITE) return;
        if (mob instanceof LivingEntity le) {
            boost(le, Attribute.MAX_HEALTH, plugin.getConfig().getDouble("models.minions.health", 40));
            boost(le, Attribute.ATTACK_DAMAGE, plugin.getConfig().getDouble("models.minions.damage", 6));
            AttributeInstance hp = le.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) le.setHealth(hp.getBaseValue());
        }
        ModelDef d = defs.get("dragonling");
        if (d == null || !enabled() || !plugin.getConfig().getBoolean("models.minions.enabled", true)) return;
        float scale = (float) plugin.getConfig().getDouble("models.minions.scale", 1.0);
        float yo = (float) plugin.getConfig().getDouble("models.minions.y-offset", 0.6);
        instances.add(new ModelInstance(this, mob, d, ModelInstance.Mode.MOB, scale, yo, false));
    }

    private void boost(LivingEntity le, Attribute a, double value) {
        AttributeInstance ai = le.getAttribute(a);
        if (ai != null && value > 0) ai.setBaseValue(value);
    }

    /** Wither skull -> flying dragon head (Java players: skull hidden, head shown; Bedrock: pack replaces the skull). */
    public void attachSkull(Entity skull) {
        ModelDef d = defs.get("head");
        if (d == null || !enabled() || !plugin.getConfig().getBoolean("models.skull-heads.enabled", true)) return;
        float scale = (float) plugin.getConfig().getDouble("models.skull-heads.scale", 0.22);
        instances.add(new ModelInstance(this, skull, d, ModelInstance.Mode.PROJECTILE, scale, 0.15f, true));
    }

    /** crystal centre -> sphere + rune circle (null when the pack data is missing) */
    CrystalBubble bubble(Location center) {
        if (bubbleCfg == null || runeCfg == null) return null;
        float s = (float) plugin.getConfig().getDouble("effects.crystal-bubble.scale", 1.0);
        return new CrystalBubble(this, center, bubbleCfg.get("cmd").getAsInt(), bubbleCfg.get("k").getAsFloat(),
                runeCfg.get("cmd").getAsInt(), runeCfg.get("k").getAsFloat(), s);
    }

    // ------------------------------------------------------------------ loop

    private void run() {
        tick += PERIOD;
        boolean sync = tick % 10 == 0;
        Map<World, Boolean> javaIn = new HashMap<>();
        Map<World, List<Player>> players = new HashMap<>();
        for (ModelInstance m : new ArrayList<>(instances)) {
            if (m.isRemoved()) {
                instances.remove(m);
                continue;
            }
            World w = m.world();
            if (w == null) {
                m.remove();
                instances.remove(m);
                continue;
            }
            List<Player> ps = players.computeIfAbsent(w, World::getPlayers);
            boolean java = javaIn.computeIfAbsent(w, k -> {
                for (Player p : ps) if (!isBedrock(p)) return true;
                return false;
            });
            m.update(tick, java, sync, ps);
        }
    }

    public void shutdown() {
        task.cancel();
        for (ModelInstance m : instances) m.remove();
        instances.clear();
    }
}
