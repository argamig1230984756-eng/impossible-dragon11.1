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
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An animated resource-pack model glued to an entity (Java Edition).
 * Every part is an ItemDisplay whose item has a custom model from the Java resource pack.
 * Bedrock players never see these: their pack replaces the entity model directly.
 */
final class ModelInstance {

    enum Mode { DRAGON, MOB, PROJECTILE }

    private final ModelManager mgr;
    private final Entity anchor;
    private final ModelDef def;
    private final Mode mode;
    private final float scale;
    private final float yOffset;
    private final boolean hideAnchorFromJava;

    private final List<ItemDisplay> displays = new ArrayList<>();
    private final Map<UUID, Set<UUID>> shown = new HashMap<>();
    private final Set<UUID> anchorHidden = new HashSet<>();

    private boolean removed = false;
    private boolean first = true;
    /** replica of the vanilla EnderDragon.flapTime clock (drives the middle head's jaw) */
    private double flapTime = 0;

    /** true = this is the "three heads" instance: both side heads are computed with the vanilla neck/head maths */
    boolean triple = false;
    private static final int NECK_PIECES = 8;
    private static final int PER_SIDE = NECK_PIECES + 2;      // neck pieces + head + jaw

    ModelInstance(ModelManager mgr, Entity anchor, ModelDef def, Mode mode, float scale, float yOffset,
                  boolean hideAnchorFromJava) {
        this.mgr = mgr;
        this.anchor = anchor;
        this.def = def;
        this.mode = mode;
        this.scale = scale;
        this.yOffset = yOffset;
        this.hideAnchorFromJava = hideAnchorFromJava;
    }

    boolean anchorIs(Entity e) {
        return anchor.getUniqueId().equals(e.getUniqueId());
    }

    boolean isRemoved() {
        return removed;
    }

    /** the anchor's world, or null once the anchor is gone (the manager then drops this instance) */
    World world() {
        return anchor.isValid() ? anchor.getWorld() : null;
    }

    /**
     * Called by the manager every ModelManager.PERIOD ticks.
     * @param java true when at least one Java player is in this world (otherwise nothing is drawn)
     * @param sync true every 10 ticks: re-send visibility to players
     */
    void update(long tick, boolean java, boolean sync, List<Player> players) {
        if (removed) return;
        if (!anchor.isValid()) { remove(); return; }
        if (!java) return;
        World w = anchor.getWorld();

        // (re)spawn the displays lazily; chunk unloads can invalidate them
        if (!displays.isEmpty() && displays.stream().anyMatch(d -> !d.isValid())) {
            clearDisplays();
            first = true;
        }
        if (displays.isEmpty()) {
            spawn(w);
            first = true;
        }

        advanceFlap();
        if (triple) {
            updateTriple(w, anchor.getLocation());
        } else {
            updateGeneric(w, tick);
        }
        boolean justSpawned = first;
        first = false;
        if (sync || justSpawned) sync(players);
    }

    /** dragon (own-model mode), dragonling minions and skull heads: parts + joint animation glued to the anchor */
    private void updateGeneric(World w, long tick) {
        Location l = anchor.getLocation();
        Quaternionf base;
        if (mode == Mode.PROJECTILE) {
            Vector v = anchor.getVelocity();
            Vector3f dir = v.lengthSquared() > 1.0E-4
                    ? new Vector3f((float) v.getX(), (float) v.getY(), (float) v.getZ())
                    : new Vector3f((float) l.getDirection().getX(), (float) l.getDirection().getY(),
                                   (float) l.getDirection().getZ());
            base = facing(dir);
        } else {
            double yaw = -Math.toRadians(l.getYaw()) + (mgr.flipFacing() ? Math.PI : 0.0);
            base = new Quaternionf().rotateY((float) yaw);
        }
        Vector3f origin = new Vector3f((float) l.getX(), (float) l.getY() + yOffset, (float) l.getZ());

        for (int i = 0; i < def.parts.size() && i < displays.size(); i++) {
            ModelDef.Part part = def.parts.get(i);
            Vector3f p = new Vector3f(part.center);
            Quaternionf q = new Quaternionf();
            for (ModelDef.Joint j : part.chain) {           // innermost joint first
                float ang = (float) j.angle(tick, flapTime);
                Quaternionf r = new Quaternionf().fromAxisAngleRad(new Vector3f(j.axis).normalize(), ang);
                p.sub(j.pivot).rotate(r).add(j.pivot);
                q.premul(r);
            }
            p.mul(scale).rotate(base).add(origin);
            Quaternionf fq = new Quaternionf(base).mul(q);
            show(w, i, p, fq, scale * part.k, 1f, 1f, 1f);
        }
    }

    /** centre of the dragon part whose hit box is roughly w x h (vanilla: head 1x1, neck 3x3, body 5x3) */
    private static Vector3f partCenter(org.bukkit.entity.EnderDragon ed, double w, double h) {
        for (var part : ed.getParts()) {
            var bb = part.getBoundingBox();
            if (Math.abs(bb.getWidthX() - w) < 0.3 && Math.abs(bb.getHeight() - h) < 0.3) {
                var c = bb.getCenter();
                return new Vector3f((float) c.getX(), (float) c.getY(), (float) c.getZ());
            }
        }
        return null;
    }

    private static Quaternionf facing(Vector3f dir) {
        Vector3f d = new Vector3f(dir);
        if (d.lengthSquared() < 1.0E-8f) return new Quaternionf();
        d.normalize();
        float yaw = (float) Math.atan2(d.x, d.z);
        float pitch = (float) -Math.asin(Math.max(-1f, Math.min(1f, d.y)));
        return new Quaternionf().rotateY(yaw).rotateX(pitch);
    }

    /**
     * The side heads are slaved to the REAL head: every update we read where the dragon's own head, neck and body
     * hit boxes are (the server moves those with the vanilla head physics), put one extra head to the left and
     * one to the right of the real head, and bend a neck from the body to each of them. The side heads face
     * the same way as the real head and use the same jaw clock, so they turn, swing, dive and bite with it.
     */
    private void updateTriple(World w, Location l) {
        if (!(anchor instanceof org.bukkit.entity.EnderDragon ed)) return;
        var cfg = mgr.plugin().getConfig();
        double spacing = cfg.getDouble("models.three-heads.spacing", 2.4);
        double size = cfg.getDouble("models.three-heads.size", 1.0);
        double yAdj = cfg.getDouble("models.three-heads.y-adjust", 0.0);
        double fan = Math.toRadians(cfg.getDouble("models.three-heads.neck-fan", 18.0));

        Vector3f head = partCenter(ed, 1.0, 1.0);
        Vector3f body = partCenter(ed, 5.0, 3.0);
        if (head == null || body == null) return;
        head.y += (float) yAdj;

        Vector3f toHead = new Vector3f(head).sub(body);
        Quaternionf qHead = facing(toHead);                    // same yaw/pitch as the real head
        Vector3f fwd = new Vector3f(0, 0, 1).rotate(qHead);    // direction the real head looks
        Vector3f fwdH = new Vector3f(toHead.x, 0, toHead.z);
        if (fwdH.lengthSquared() < 1.0E-6f) fwdH.set(fwd.x, 0, fwd.z);
        fwdH.normalize();
        Vector3f right = new Vector3f(-fwdH.z, 0, fwdH.x).negate(); // horizontal, 90 degrees from the heading
        right.set(fwdH.z, 0, -fwdH.x);

        double jawRot = (Math.sin(flapTime * (Math.PI * 2)) + 1.0) * 0.2;
        Vector3f root = new Vector3f(body).add(new Vector3f(fwdH).mul(1.6f)).add(0f, 0.25f, 0f); // where the necks leave the body

        for (int side = 0; side < 2; side++) {
            float sgn = side == 0 ? 1f : -1f;
            Vector3f hs = new Vector3f(head).add(new Vector3f(right).mul((float) spacing * sgn)).add(0f, -0.1f, 0f);
            Vector3f neckEnd = new Vector3f(hs).sub(new Vector3f(fwd).mul(0.56f));   // back of the skull
            // neck = cubic bezier from the body to the back of the side head
            Vector3f chord = new Vector3f(neckEnd).sub(root);
            float len = Math.max(0.5f, chord.length());
            Vector3f dirOut = new Vector3f(chord).rotateY((float) (fan * sgn)).normalize();
            Vector3f p0 = root;
            Vector3f p1 = new Vector3f(root).add(dirOut.mul(len * 0.4f)).add(0f, 0.5f, 0f);
            Vector3f p2 = new Vector3f(neckEnd).sub(new Vector3f(fwd).mul(len * 0.35f));
            Vector3f p3 = neckEnd;
            float stretch = (float) Math.max(0.8, Math.min(1.7, (len * 1.1f / NECK_PIECES) / 0.625));
            for (int i = 0; i < NECK_PIECES; i++) {
                float t = (i + 0.5f) / NECK_PIECES;
                float u = 1 - t;
                Vector3f pos = new Vector3f()
                        .add(new Vector3f(p0).mul(u * u * u))
                        .add(new Vector3f(p1).mul(3 * u * u * t))
                        .add(new Vector3f(p2).mul(3 * u * t * t))
                        .add(new Vector3f(p3).mul(t * t * t));
                Vector3f tan = new Vector3f()
                        .add(new Vector3f(p1).sub(p0).mul(3 * u * u))
                        .add(new Vector3f(p2).sub(p1).mul(6 * u * t))
                        .add(new Vector3f(p3).sub(p2).mul(3 * t * t));
                Quaternionf q = facing(tan);
                pos.add(new Vector3f(0f, 2f / 16f, 0f).rotate(q));        // the piece's centre sits 2px above its pivot
                show(w, side * PER_SIDE + i, pos, q, size, 1f, 1f, stretch);
            }
            show(w, side * PER_SIDE + NECK_PIECES, hs, qHead, size, 1f, 1f, 1f);
            // jaw: same hinge and angle as the vanilla jaw, so it bites together with the middle head
            Vector3f local = new Vector3f(0, 2, -8).rotateX((float) jawRot).add(0, 4, -8).sub(0, -2, -9);
            Vector3f jawOff = new Vector3f(local.x, -local.y, -local.z).mul(1f / 16f).rotate(qHead);
            Quaternionf qJaw = new Quaternionf(qHead).rotateX((float) jawRot);
            show(w, side * PER_SIDE + NECK_PIECES + 1, new Vector3f(hs).add(jawOff), qJaw, size, 1f, 1f, 1f);
        }
    }

    private void show(World w, int idx, Vector3f pos, Quaternionf q, double size, float sx, float sy, float sz) {
        if (idx >= displays.size()) return;
        ItemDisplay d = displays.get(idx);
        if (!d.isValid()) return;
        float s = (float) size;
        d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(s * sx, s * sy, s * sz), new Quaternionf()));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(first ? 0 : ModelManager.PERIOD + 1);
        d.teleport(new Location(w, pos.x, pos.y, pos.z));
    }

    /** same maths as the vanilla dragon's aiStep, run once per tick that passed since the last update */
    private void advanceFlap() {
        Vector v = anchor.getVelocity();
        double horiz = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        double f = 0.2 / (horiz * 10.0 + 1.0);
        f *= Math.pow(2.0, v.getY());
        flapTime += f * ModelManager.PERIOD;
    }

    private void spawn(World w) {
        Location at = anchor.getLocation();
        for (ModelDef.Part part : def.parts) {
            ItemStack stack = new ItemStack(Material.PAPER);
            ItemMeta meta = stack.getItemMeta();
            meta.setCustomModelData(part.cmd);
            stack.setItemMeta(meta);
            ItemDisplay d = w.spawn(at, ItemDisplay.class, e -> {
                e.setItemStack(stack);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setInterpolationDuration(ModelManager.PERIOD + 1);
                e.setTeleportDuration(ModelManager.PERIOD + 1);
                e.setViewRange(1.6f);
                e.setShadowRadius(0f);
                e.setBrightness(mgr.modelBrightness());
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(0.01f), new Quaternionf()));
            });
            displays.add(d);
        }
    }

    private void clearDisplays() {
        for (ItemDisplay d : displays) if (d.isValid()) d.remove();
        displays.clear();
        shown.clear();
    }

    void sync(List<Player> players) {
        Set<UUID> online = new HashSet<>();
        for (Player p : players) {
            online.add(p.getUniqueId());
            if (mgr.isBedrock(p)) continue; // Bedrock gets the model from its own resource pack
            if (hideAnchorFromJava && anchorHidden.add(p.getUniqueId())) {
                p.hideEntity(mgr.plugin(), anchor); // hide the vanilla skull, show the dragon head instead
            }
            if (displays.isEmpty()) continue;
            Set<UUID> s = shown.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            for (ItemDisplay d : displays) {
                if (s.add(d.getUniqueId())) p.showEntity(mgr.plugin(), d);
            }
        }
        shown.keySet().retainAll(online);
    }

    void remove() {
        removed = true;
        clearDisplays();
    }
}
