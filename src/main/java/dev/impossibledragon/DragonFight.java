package dev.impossibledragon;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.DragonBattle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.WitherSkull;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** All custom behaviour for one Ender Dragon. */
public final class DragonFight {

    private enum Ability { FIREBALL, SKULLS, LIGHTNING, MINIONS, ROAR, LIFT, TAIL, BREATH, METEORS, ORBS, PULL }

    private enum Shield { UP, BROKEN }

    private static final long PERIOD = 10L; // ticks between brain updates

    private final ImpossibleDragon plugin;
    private final DragonManager manager;
    private final EnderDragon dragon;
    private final BukkitTask task;
    private final BukkitTask animTask;

    private final Map<Ability, Long> next = new EnumMap<>(Ability.class);
    private final Set<UUID> minions = new HashSet<>();

    // crystals / shield
    private final List<Location> crystalSpots = new ArrayList<>();
    private final Map<Integer, UUID> crystalAt = new HashMap<>();      // spot -> living crystal
    private final Map<Integer, Long> respawnDue = new HashMap<>();     // spot -> tick it returns
    private final Map<Integer, Long> downSince = new HashMap<>();      // spot -> tick it died
    private final Map<Integer, ArmorStand> stands = new HashMap<>();   // spot -> timer hologram
    private final Map<UUID, Long> blockedMsg = new HashMap<>();
    private Shield shield = Shield.UP;
    private long brokenUntil = 0;
    private long lastBlockSound = 0;
    private boolean landedByShield = false;
    /** exact damage for hits the dragon deals itself (breath), so the global multiplier is not applied twice */
    private final Map<UUID, Double> exactHits = new HashMap<>();

    private long now = 0;
    private long fightTicks = 0;
    private boolean doom = false;
    private boolean rebirthStarted = false;
    private boolean rebirthing = false;
    private boolean reborn = false;
    private int rebirthStep = 0;
    private int phase = 1;
    private double maxHealth = 200;
    private double anim = 0;
    private final Map<Integer, CrystalBubble> bubbles = new HashMap<>();
    private int bubbleTick = 0;

    public DragonFight(ImpossibleDragon plugin, DragonManager manager, EnderDragon dragon, boolean fresh) {
        this.plugin = plugin;
        this.manager = manager;
        this.dragon = dragon;
        applyStats(fresh);
        plugin.models().attachDragon(dragon);
        scanCrystals();
        updateBar();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, PERIOD);
        this.animTask = Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 20L, 2L);
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // ------------------------------------------------------------------ setup

    public void applyStats(boolean fresh) {
        double max = Math.min(1024.0, Math.max(1.0, cfg().getDouble("dragon.max-health", 600)));
        AttributeInstance attr = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            double old = attr.getBaseValue();
            attr.setBaseValue(max);
            double hp = fresh ? max : (old > 0 ? dragon.getHealth() / old * max : max);
            dragon.setHealth(Math.min(max, Math.max(1.0, hp)));
            maxHealth = max;
        }
    }

    private void scanCrystals() {
        if (!crystalSpots.isEmpty()) return;
        World w = dragon.getWorld();
        for (EnderCrystal c : w.getEntitiesByClass(EnderCrystal.class)) {
            Location l = c.getLocation();
            if (l.getY() < 70) continue; // pillar crystals only
            if (l.getX() * l.getX() + l.getZ() * l.getZ() > 80 * 80) continue;
            int idx = crystalSpots.size();
            crystalSpots.add(l.clone());
            crystalAt.put(idx, c.getUniqueId());
        }
    }

    public void stop() {
        task.cancel();
        animTask.cancel();
        removeMinions();
        clearStands();
        for (CrystalBubble b : bubbles.values()) b.remove();
        bubbles.clear();
        plugin.models().detachDragon(dragon);
    }

    public void removeMinions() {
        for (UUID id : minions) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        minions.clear();
    }

    // ------------------------------------------------------------------ main loop

    private void tick() {
        if (!dragon.isValid()) {
            manager.untrack(dragon.getUniqueId());
            return;
        }
        now += PERIOD;

        updatePhase();
        ensureBossBar();
        scanCrystals();

        List<Player> targets = targets();
        if (!targets.isEmpty()) fightTicks += PERIOD;
        checkDoom();
        if (rebirthing) {
            rebirthTick();
            return;
        }
        processShield();

        if (now % 20 == 0) {
            crystalHeal();
            updateTimers();
            updateBar();
            raceBar();
        }
        if (targets.isEmpty()) return;

        if (on("fireball", 1) && ready(Ability.FIREBALL, "fireball", 7)) {
            volley(targets, cfg().getInt("abilities.fireball.count", 4) + (phase - 1) + extra(targets), false);
        }
        if (on("wither-skulls", 2) && ready(Ability.SKULLS, "wither-skulls", 10)) {
            volley(targets, cfg().getInt("abilities.wither-skulls.count", 4) + extra(targets), true);
        }
        if (on("lightning", 2) && ready(Ability.LIGHTNING, "lightning", 9)) {
            lightning(targets);
        }
        if (on("tail", 1) && anyWithin(targets, cfg().getDouble("abilities.tail.range", 30))
                && ready(Ability.TAIL, "tail", 6)) {
            tailStrike();
        }
        if (on("lightning-breath", 2) && ready(Ability.BREATH, "lightning-breath", 12)) {
            breath(targets);
        }
        if (on("meteors", 2) && ready(Ability.METEORS, "meteors", 16)) {
            meteors(targets);
        }
        if (on("void-orbs", 2) && ready(Ability.ORBS, "void-orbs", 14)) {
            orbs(targets);
        }
        if (on("gravity-well", 3) && ready(Ability.PULL, "gravity-well", 28)) {
            gravityWell(targets);
        }
        if (on("minions", 1) && ready(Ability.MINIONS, "minions", 22)) {
            spawnMinions(targets);
        }
        if (on("roar", 2) && ready(Ability.ROAR, "roar", 26)) {
            roar(targets);
        }
        if (on("levitation", 3) && ready(Ability.LIFT, "levitation", 22)) {
            lift(targets);
        }
    }

    private boolean on(String path, int defaultMinPhase) {
        String base = "abilities." + path;
        return cfg().getBoolean(base + ".enabled", true)
                && phase >= cfg().getInt(base + ".min-phase", defaultMinPhase);
    }

    /** Returns true when the ability may fire now and starts its cooldown. */
    private boolean ready(Ability a, String path, int defaultSeconds) {
        double secs = cfg().getDouble("abilities." + path + ".cooldown-seconds", defaultSeconds);
        if (phase >= 3) secs *= cfg().getDouble("dragon.enrage-cooldown-multiplier", 0.5);
        if (doom) secs *= cfg().getDouble("fight.doom-cooldown-multiplier", 0.4);
        if (reborn) secs *= cfg().getDouble("rebirth.cooldown-multiplier", 0.8);
        long cooldown = Math.max(20L, (long) (secs * 20));
        Long due = next.get(a);
        if (due == null) {
            next.put(a, now + cooldown);
            return false;
        }
        if (now < due) return false;
        next.put(a, now + cooldown);
        return true;
    }

    private int extra(List<Player> targets) {
        return Math.min(4, targets.size() / 2);
    }

    private List<Player> targets() {
        List<Player> list = new ArrayList<>();
        Location dl = dragon.getLocation();
        for (Player p : dragon.getWorld().getPlayers()) {
            if (!vulnerable(p)) continue;
            if (p.getLocation().distanceSquared(dl) > 200 * 200) continue;
            list.add(p);
        }
        return list;
    }

    private Player randomOf(List<Player> list) {
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    // ------------------------------------------------------------------ phases / doom

    private void updatePhase() {
        AttributeInstance attr = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) maxHealth = attr.getValue();
        if (rebirthing) return;
        double frac = dragon.getHealth() / maxHealth;
        int newPhase = reborn ? 4 : (frac > 0.66 ? 1 : (frac > 0.33 ? 2 : 3));
        if (newPhase == phase) return;

        boolean up = newPhase > phase;
        phase = newPhase;
        updateBar();
        if (!up) return;

        String msg = cfg().getString(phase == 2 ? "messages.phase-2" : "messages.phase-3", "");
        for (Player p : dragon.getWorld().getPlayers()) {
            if (!msg.isEmpty()) p.sendMessage(color(msg));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 4f, 0.5f);
        }
        if (shieldFeature()) reformShield(false);
    }

    private void checkDoom() {
        long limit = cfg().getLong("fight.time-limit-seconds", 600);
        if (doom || limit <= 0 || fightTicks / 20 < limit) return;
        doom = true;
        titleAll(cfg().getString("messages.doom", "&4&lTIME'S UP - THE DRAGON GOES BERSERK!"), "");
        for (Player p : dragon.getWorld().getPlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8f, 0.4f);
        }
        updateBar();
    }

    private void updateBar() {
        DragonBattle battle = dragon.getDragonBattle();
        if (battle == null) return;
        BossBar bar = battle.getBossBar();
        StringBuilder sb = new StringBuilder(color(cfg().getString("dragon.boss-bar-title", "&5&lImpossible Ender Dragon")));
        if (rebirthing) {
            sb.append(ChatColor.GRAY).append(" | ").append(ChatColor.DARK_RED).append("REBIRTH");
        } else if (phase == 4) {
            sb.append(ChatColor.GRAY).append(" | ").append(ChatColor.DARK_RED).append("FINAL FORM");
        } else {
            sb.append(ChatColor.GRAY).append(" | Phase ").append(phase);
        }
        if (healLockMode()) {
            appendHealLockBar(sb);
        } else if (shieldFeature()) {
            if (shield == Shield.UP) {
                sb.append(ChatColor.GRAY).append(" | ").append(ChatColor.LIGHT_PURPLE).append("SHIELDED ")
                        .append(ChatColor.GRAY).append('(').append(downCount()).append('/')
                        .append(crystalSpots.size()).append(" down)");
            } else {
                sb.append(ChatColor.GRAY).append(" | ").append(ChatColor.GREEN).append("VULNERABLE ")
                        .append(Math.max(0, (brokenUntil - now) / 20)).append('s');
            }
        }
        long limit = cfg().getLong("fight.time-limit-seconds", 600);
        if (limit > 0) {
            if (doom) {
                sb.append(ChatColor.GRAY).append(" | ").append(ChatColor.DARK_RED).append("DOOM");
            } else {
                long left = Math.max(0, limit - fightTicks / 20);
                sb.append(ChatColor.GRAY).append(" | ").append(ChatColor.YELLOW)
                        .append(String.format("%d:%02d", left / 60, left % 60));
            }
        }
        bar.setTitle(sb.toString());
        bar.setColor(rebirthing ? BarColor.WHITE : (doom || phase >= 3 ? BarColor.RED : (phase == 2 ? BarColor.PINK : BarColor.PURPLE)));
    }

    /** Makes sure every player in the End world is subscribed to the boss bar. */
    private void ensureBossBar() {
        DragonBattle battle = dragon.getDragonBattle();
        if (battle == null) return;
        BossBar bar = battle.getBossBar();
        bar.setVisible(true);
        for (Player p : dragon.getWorld().getPlayers()) bar.addPlayer(p);
    }

    // ------------------------------------------------------------------ shield + crystal timers

    private boolean shieldFeature() {
        return cfg().getBoolean("shield.enabled", true) && !crystalSpots.isEmpty();
    }

    private int downCount() {
        return crystalSpots.size() - crystalAt.size();
    }

    private int aliveCrystals() {
        return crystalAt.size();
    }

    private void processShield() {
        if (!shieldFeature()) return;
        World w = dragon.getWorld();

        // 1) crystals that were destroyed -> start THEIR OWN timer
        for (Map.Entry<Integer, UUID> en : new ArrayList<>(crystalAt.entrySet())) {
            int idx = en.getKey();
            Entity e = Bukkit.getEntity(en.getValue());
            if (e != null && e.isValid()) continue;
            Location spot = crystalSpots.get(idx);
            if (!w.isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4)) continue;
            crystalAt.remove(idx);
            onCrystalDestroyed(idx);
        }

        // 2) crystals whose timer ran out come back
        for (Map.Entry<Integer, Long> en : new ArrayList<>(respawnDue.entrySet())) {
            if (now >= en.getValue()) respawnSpot(en.getKey());
        }

        // 3) all crystals down at the same time -> shield breaks
        if (shield == Shield.UP && crystalAt.isEmpty()) {
            breakShield();
        } else if (shield == Shield.BROKEN && now >= brokenUntil) {
            reformShield(true);
        }
    }

    private void onCrystalDestroyed(int idx) {
        if (shield != Shield.UP) return;
        long total = (long) (cfg().getDouble("shield.crystal-respawn-seconds", 40) * 20);
        respawnDue.put(idx, now + total);
        downSince.put(idx, now);
        spawnStand(idx);

        String msg = cfg().getString("messages.crystal-down", "");
        if (!msg.isEmpty() && !crystalAt.isEmpty()) {
            msg = msg.replace("{down}", String.valueOf(downCount()))
                    .replace("{total}", String.valueOf(crystalSpots.size()))
                    .replace("{seconds}", String.valueOf(total / 20));
            for (Player p : dragon.getWorld().getPlayers()) p.sendActionBar(Component.text(color(msg)));
        }
    }

    /**
     * HEAL_LOCK (default): the dragon can always be hit. The shield is the crystal network - while any
     * crystal lives the dragon heals; destroy the LAST crystal before the FIRST one respawns and the shield
     * breaks, which stops ALL dragon healing. IMMUNITY: the old behaviour (immune until the shield breaks).
     */
    private boolean healLockMode() {
        return shieldFeature() && !"IMMUNITY".equalsIgnoreCase(cfg().getString("shield.mode", "HEAL_LOCK"));
    }

    /** false while the shield is broken in HEAL_LOCK mode: no crystal healing, no lifesteal. */
    public boolean canHeal() {
        return !(healLockMode() && shield == Shield.BROKEN);
    }

    /** exact damage reserved for this player's next hit from the dragon (consumed by the listener), or null */
    public Double exactHit(UUID id) {
        return exactHits.get(id);
    }

    private void breakShield() {
        final boolean lock = healLockMode();
        shield = Shield.BROKEN;
        long seconds = cfg().getLong("shield.broken-seconds", lock ? 120 : 35);
        final boolean untilPhase = lock && seconds <= 0; // 0 = no healing until the next phase / rebirth
        brokenUntil = untilPhase ? Long.MAX_VALUE / 4 : now + Math.max(1, seconds) * 20;
        respawnDue.clear();
        downSince.clear();
        clearStands();

        World w = dragon.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, dragon.getLocation(), 6, 3, 2, 3, 0);
        if (lock) shatterFx(w);
        for (Player p : w.getPlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.HOSTILE, 4f, 0.7f);
            if (lock) p.playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 4f, 0.6f);
        }
        String sub;
        if (lock) {
            sub = untilPhase
                    ? cfg().getString("messages.shield-broken-sub-lock-phase", "&7The dragon can no longer heal until its next phase!")
                    : cfg().getString("messages.shield-broken-sub-lock", "&7The dragon can no longer heal for {seconds}s!")
                            .replace("{seconds}", String.valueOf(seconds));
        } else {
            sub = cfg().getString("messages.shield-broken-sub", "&7Hit it! Vulnerable for {seconds}s")
                    .replace("{seconds}", String.valueOf(seconds));
        }
        titleAll(cfg().getString("messages.shield-broken", "&a&lSHIELD BROKEN!"), sub);

        boolean land = lock ? cfg().getBoolean("shield.lock-land-on-break", false)
                : cfg().getBoolean("shield.land-on-break", true);
        if (land) {
            landedByShield = true;
            try {
                dragon.setPhase(EnderDragon.Phase.LAND_ON_PORTAL);
            } catch (Throwable ignored) {
                // phase change is cosmetic
            }
        }
        updateBar();
    }

    /** every crystal spot bursts when the shield breaks */
    private void shatterFx(World w) {
        for (Location spot : crystalSpots) {
            if (!w.isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4)) continue;
            Location c = spot.clone().add(0, 1, 0);
            w.spawnParticle(Particle.END_ROD, c, 60, 1.5, 1.5, 1.5, 0.15);
            w.spawnParticle(Particle.ELECTRIC_SPARK, c, 40, 1.5, 1.5, 1.5, 0.3);
            w.playSound(c, Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 3f, 0.5f);
        }
        w.strikeLightningEffect(dragon.getLocation());
    }

    private void reformShield(boolean timedOut) {
        final boolean lock = healLockMode();
        shield = Shield.UP;
        respawnDue.clear();
        downSince.clear();
        clearStands();
        for (int i = 0; i < crystalSpots.size(); i++) respawnSpot(i);

        if (timedOut && !lock) { // you were too slow: the dragon regenerates a bit (never above its phase)
            double heal = maxHealth * cfg().getDouble("shield.heal-on-reform", 0.05);
            double ceiling = phase == 1 ? maxHealth : (phase == 2 ? maxHealth * 0.66
                    : (phase == 3 ? maxHealth * 0.33 : maxHealth * cfg().getDouble("rebirth.health-fraction", 0.5)));
            heal = Math.min(heal, ceiling - dragon.getHealth());
            if (heal > 0) dragon.setHealth(Math.min(maxHealth, dragon.getHealth() + heal));
        }
        for (Player p : dragon.getWorld().getPlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.HOSTILE, 4f, 0.6f);
        }
        String sub = lock
                ? cfg().getString("messages.shield-restored-sub-lock", "&7Crystals are back - the dragon can heal again!")
                : cfg().getString("messages.shield-restored-sub", "&7Destroy every crystal again!");
        titleAll(cfg().getString("messages.shield-restored", "&5&lSHIELD RESTORED"), sub);
        boolean circle = lock ? landedByShield : cfg().getBoolean("shield.land-on-break", true);
        if (circle) {
            try {
                dragon.setPhase(EnderDragon.Phase.CIRCLING);
            } catch (Throwable ignored) {
                // phase change is cosmetic
            }
        }
        landedByShield = false;
        updateBar();
    }

    private void appendHealLockBar(StringBuilder sb) {
        sb.append(ChatColor.GRAY).append(" | ");
        if (shield == Shield.UP) {
            sb.append(ChatColor.LIGHT_PURPLE).append("SHIELD ").append(ChatColor.GRAY).append('(')
                    .append(ChatColor.WHITE).append(aliveCrystals()).append(ChatColor.GRAY).append('/')
                    .append(ChatColor.WHITE).append(crystalSpots.size()).append(ChatColor.GRAY).append(" crystals)");
            if (aliveCrystals() > 0) sb.append(' ').append(ChatColor.RED).append("HEALING");
        } else {
            sb.append(ChatColor.GREEN).append("SHIELD BROKEN ").append(ChatColor.GRAY).append("- ")
                    .append(ChatColor.RED).append("NO HEALING");
            if (brokenUntil - now < 1_000_000L) {
                sb.append(ChatColor.GRAY).append(" (").append(ChatColor.YELLOW)
                        .append(Math.max(0, (brokenUntil - now) / 20)).append('s').append(ChatColor.GRAY).append(')');
            }
        }
    }

    /** action bar race: shows how long the players have until the FIRST destroyed crystal comes back */
    private void raceBar() {
        if (!healLockMode() || shield != Shield.UP || respawnDue.isEmpty()) return;
        if (!cfg().getBoolean("shield.race-bar", true)) return;
        long first = Long.MAX_VALUE;
        for (long due : respawnDue.values()) first = Math.min(first, due);
        long secs = Math.max(0, (long) Math.ceil((first - now) / 20.0));
        String col = secs <= 10 ? "&c&l" : (secs <= 20 ? "&e" : "&f");
        String msg = cfg().getString("messages.shield-race",
                        "&5&lSHIELD RACE &8| &d{left} &7crystal(s) left &8| &7first returns in {time}")
                .replace("{left}", String.valueOf(aliveCrystals()))
                .replace("{time}", col + secs + "s");
        Component bar = Component.text(color(msg));
        for (Player p : dragon.getWorld().getPlayers()) p.sendActionBar(bar);
    }

    private void respawnSpot(int idx) {
        if (crystalAt.containsKey(idx)) {
            respawnDue.remove(idx);
            downSince.remove(idx);
            removeStand(idx);
            return;
        }
        World w = dragon.getWorld();
        Location spot = crystalSpots.get(idx);
        if (!w.isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4)) return; // retry later

        Collection<Entity> near = w.getNearbyEntities(spot, 1.5, 1.5, 1.5, e -> e instanceof EnderCrystal);
        if (!near.isEmpty()) {
            crystalAt.put(idx, near.iterator().next().getUniqueId());
        } else {
            EnderCrystal c = w.spawn(spot, EnderCrystal.class, ec -> ec.setShowingBottom(false));
            crystalAt.put(idx, c.getUniqueId());
            w.spawnParticle(Particle.REVERSE_PORTAL, spot, 60, 0.5, 0.8, 0.5, 0.2);
            w.playSound(spot, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.HOSTILE, 3f, 0.8f);
        }
        respawnDue.remove(idx);
        downSince.remove(idx);
        removeStand(idx);
    }

    private void crystalHeal() {
        double per = cfg().getDouble("dragon.crystal-heal-per-second", 1.0);
        if (per <= 0 || dragon.getHealth() >= maxHealth) return;
        if (!canHeal()) return; // shield broken: the dragon can no longer heal
        int alive = aliveCrystals();
        if (alive == 0) return;
        dragon.setHealth(Math.min(maxHealth, dragon.getHealth() + alive * per));
        if (healLockMode()) { // make the healing visible so people know why crystals matter
            dragon.getWorld().spawnParticle(Particle.HEART, dragon.getLocation().add(0, 3, 0),
                    Math.min(8, alive), 2.5, 1, 2.5, 0);
        }
    }

    // ---- timer holograms (armor stands work for Java AND Bedrock/Geyser)

    private void spawnStand(int idx) {
        if (!cfg().getBoolean("shield.show-timers", true)) return;
        World w = dragon.getWorld();
        Location spot = crystalSpots.get(idx).clone().add(0, 2.5, 0);
        if (!w.isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4)) return;
        ArmorStand st = w.spawn(spot, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setMarker(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(false);
            a.setCustomNameVisible(true);
        });
        stands.put(idx, st);
        nameStand(idx);
    }

    private void nameStand(int idx) {
        ArmorStand st = stands.get(idx);
        Long due = respawnDue.get(idx);
        if (st == null || due == null) return;
        long secs = Math.max(0, (long) Math.ceil((due - now) / 20.0));
        String col = secs <= 10 ? "&c&l" : (secs <= 20 ? "&e" : "&d");
        st.customName(Component.text(color(col + "Crystal returns in &f" + secs + "s")));
    }

    private void updateTimers() {
        World w = dragon.getWorld();
        for (Integer idx : new ArrayList<>(stands.keySet())) {
            nameStand(idx);
            Long due = respawnDue.get(idx);
            if (due == null) continue;
            long secs = (long) Math.ceil((due - now) / 20.0);
            if (secs > 0 && secs <= 5) { // about to come back: tick tick tick
                w.playSound(crystalSpots.get(idx), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.HOSTILE,
                        2f, 1.0f + (5 - secs) * 0.15f);
            }
        }
    }

    private void removeStand(int idx) {
        ArmorStand st = stands.remove(idx);
        if (st != null) st.remove();
    }

    private void clearStands() {
        for (ArmorStand st : stands.values()) st.remove();
        stands.clear();
    }

    // ---- rebirth: the dragon "dies" ONCE and rises again in a FINAL FORM (phase 4)

    /** Turns the first lethal hit into the start of the rebirth. */
    private double lethalGuard(double dmg) {
        if (rebirthStarted || !cfg().getBoolean("rebirth.enabled", true)) return dmg;
        double hp = dragon.getHealth();
        if (dmg < hp) return dmg;
        startRebirth();
        return Math.max(0, hp - 1);
    }

    private void startRebirth() {
        rebirthStarted = true;
        rebirthing = true;
        rebirthStep = 0;
        removeMinions();
        clearStands();
        for (Player p : dragon.getWorld().getPlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_DEATH, SoundCategory.HOSTILE, 8f, 0.7f);
        }
        titleAll(cfg().getString("messages.rebirth", "&4&lTHE DRAGON REFUSES TO DIE!"),
                cfg().getString("messages.rebirth-sub", "&7It rises again..."));
        updateBar();
    }

    private void rebirthTick() {
        World w = dragon.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int totalSteps = Math.max(2, (int) (cfg().getDouble("rebirth.duration-seconds", 6) * 2));
        rebirthStep++;
        double progress = Math.min(1.0, rebirthStep / (double) totalSteps);

        // health bar visibly refills while it rises
        double target = maxHealth * cfg().getDouble("rebirth.health-fraction", 0.5);
        dragon.setHealth(Math.max(1.0, Math.min(maxHealth, 1.0 + (target - 1.0) * progress)));

        Location c = dragon.getLocation();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 2, 4, 2, 4, 0);
        w.spawnParticle(Particle.REVERSE_PORTAL, c, 80, 5, 3, 5, 0.3);
        w.strikeLightningEffect(c.clone().add(r.nextDouble(-12, 12), 0, r.nextDouble(-12, 12)));
        w.playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 6f, 0.5f + (float) progress);

        if (rebirthStep >= totalSteps) completeRebirth();
    }

    private void completeRebirth() {
        rebirthing = false;
        reborn = true;
        phase = 4;
        next.clear(); // abilities restart with fresh cooldowns
        double target = maxHealth * cfg().getDouble("rebirth.health-fraction", 0.5);
        dragon.setHealth(Math.max(1.0, Math.min(maxHealth, target)));
        if (shieldFeature()) reformShield(false); // every crystal comes back, shield up again

        String msg = cfg().getString("messages.phase-4", "&4&lFINAL FORM - THE DRAGON IS REBORN!");
        titleAll(msg, cfg().getString("messages.phase-4-sub", "&7Faster. Stronger. No more second chances."));
        for (Player p : dragon.getWorld().getPlayers()) {
            p.sendMessage(color(msg));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8f, 0.4f);
        }
        updateBar();
    }

    // ---- damage rules (called by the listener)

    /** Returns the damage the dragon should actually take (0 = cancel the hit). */
    public double modifyIncoming(double raw, Player attacker) {
        if (rebirthing) return 0; // invulnerable while it rises again
        double cap = cfg().getDouble("dragon.max-damage-per-hit", 30);
        double dmg;
        if (healLockMode()) { // the dragon is ALWAYS hittable; the shield only decides whether it can heal
            dmg = raw * (shield == Shield.UP ? cfg().getDouble("shield.up-damage-multiplier", 1.0)
                    : cfg().getDouble("shield.broken-damage-multiplier", 1.0));
            if (cap > 0) dmg = Math.min(dmg, cap);
            if (cfg().getBoolean("shield.phase-gate-floor", false)) {
                double floor = phase == 1 ? maxHealth * 0.66 - 0.5 : (phase == 2 ? maxHealth * 0.33 - 0.5 : 0);
                if (floor > 0) dmg = Math.min(dmg, dragon.getHealth() - floor);
            }
            return lethalGuard(Math.max(0, dmg));
        }
        if (shieldFeature()) {
            if (shield == Shield.UP) {
                dmg = raw * cfg().getDouble("shield.damage-multiplier", 0.0);
                if (dmg <= 0) {
                    blocked(attacker);
                    return 0;
                }
            } else {
                dmg = raw * cfg().getDouble("shield.broken-damage-multiplier", 1.0);
                if (cap > 0) dmg = Math.min(dmg, cap);
                // can't fall through a phase gate in one window; at the gate the shield comes back
                double floor = phase == 1 ? maxHealth * 0.66 - 0.5 : (phase == 2 ? maxHealth * 0.33 - 0.5 : 0);
                if (floor > 0) dmg = Math.min(dmg, dragon.getHealth() - floor);
                return lethalGuard(Math.max(0, dmg));
            }
        } else {
            dmg = raw * cfg().getDouble("dragon.damage-taken-multiplier", 0.6);
        }
        if (cap > 0) dmg = Math.min(dmg, cap);
        return lethalGuard(Math.max(0, dmg));
    }

    public double damageDealtMultiplier() {
        double m = cfg().getDouble("dragon.damage-dealt-multiplier", 2.0);
        if (doom) m *= cfg().getDouble("fight.doom-damage-multiplier", 2.0);
        if (reborn) m *= cfg().getDouble("rebirth.damage-multiplier", 1.25);
        return m;
    }

    private void blocked(Player attacker) {
        long ms = System.currentTimeMillis();
        if (ms - lastBlockSound > 400) {
            lastBlockSound = ms;
            Location l = dragon.getLocation();
            dragon.getWorld().playSound(l, Sound.ITEM_SHIELD_BLOCK, SoundCategory.HOSTILE, 1.5f, 0.8f);
            dragon.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, l, 10, 3, 2, 3, 0.1);
        }
        if (attacker == null) return;
        Long last = blockedMsg.get(attacker.getUniqueId());
        if (last != null && ms - last < 1500) return;
        blockedMsg.put(attacker.getUniqueId(), ms);
        attacker.sendActionBar(Component.text(color("&5&lSHIELDED &7- destroy ALL crystals! &d("
                + aliveCrystals() + " left)")));
    }

    // ------------------------------------------------------------------ attacks

    private void volley(List<Player> targets, int count, boolean skull) {
        for (int i = 0; i < count; i++) {
            final Player t = randomOf(targets);
            final long delay = i * 6L;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dragon.isValid() && t.isOnline() && !t.isDead()
                        && t.getWorld().equals(dragon.getWorld())) {
                    fire(t, skull);
                }
            }, delay);
        }
    }

    private void fire(Player target, boolean skull) {
        Location from = dragon.getLocation().add(0, 1.5, 0);
        Vector dir = target.getEyeLocation().toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 1) return;
        dir.normalize();
        Location spawn = from.clone().add(dir.clone().multiply(5));
        World w = dragon.getWorld();
        if (skull) {
            boolean blue = ThreadLocalRandom.current().nextDouble() < 0.3;
            w.spawn(spawn, WitherSkull.class, s -> {
                s.setShooter(dragon);
                s.setDirection(dir);
                s.setCharged(blue);
                plugin.models().attachSkull(s);
            });
        } else {
            w.spawn(spawn, DragonFireball.class, f -> {
                f.setShooter(dragon);
                f.setDirection(dir);
            });
        }
        w.playSound(from, Sound.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.HOSTILE, 4f, 1f);
    }

    private void lightning(List<Player> targets) {
        int bolts = cfg().getInt("abilities.lightning.bolts", 3) + (phase >= 3 ? 1 : 0) + extra(targets)
                + (doom ? targets.size() : 0);
        for (int i = 0; i < bolts; i++) {
            final Player victim = randomOf(targets);
            warn(victim, cfg().getString("messages.warn-lightning", ""));
            final Location spot = victim.getLocation().clone();
            final World w = spot.getWorld();
            if (w == null) continue;
            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    if (!dragon.isValid()) {
                        cancel();
                        return;
                    }
                    for (int k = 0; k < 16; k++) {
                        double a = k * Math.PI / 8;
                        w.spawnParticle(Particle.ELECTRIC_SPARK,
                                spot.getX() + Math.cos(a) * 2, spot.getY() + 0.2, spot.getZ() + Math.sin(a) * 2,
                                1, 0, 0, 0, 0);
                    }
                    w.playSound(spot, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.HOSTILE, 1.5f, 2f);
                    if (++t >= 5) {
                        w.strikeLightning(spot);
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 0L, 4L);
        }
    }

    /** Meteor storm: marked circles on the ground, then fireballs fall from the sky. */
    private void meteors(List<Player> targets) {
        final World w = dragon.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int count = cfg().getInt("abilities.meteors.count", 6) + extra(targets) + (phase >= 3 ? 3 : 0);
        final float power = (float) cfg().getDouble("abilities.meteors.explosion-power", 3.0);
        for (Player p : targets) warn(p, cfg().getString("messages.warn-meteors", ""));

        for (int i = 0; i < count; i++) {
            Player victim = randomOf(targets);
            final Location spot = victim.getLocation().clone().add(r.nextDouble(-7, 7), 0, r.nextDouble(-7, 7));
            final long startDelay = i * 4L;
            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    if (!dragon.isValid()) {
                        cancel();
                        return;
                    }
                    if (t == 0) w.playSound(spot, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 3f, 0.5f);
                    for (int k = 0; k < 14; k++) {
                        double a = k * Math.PI / 7;
                        w.spawnParticle(Particle.FLAME, spot.getX() + Math.cos(a) * 2.5, spot.getY() + 0.2,
                                spot.getZ() + Math.sin(a) * 2.5, 1, 0, 0, 0, 0);
                    }
                    if (++t >= 12) {
                        Location sky = spot.clone().add(0, 28, 0);
                        w.spawn(sky, LargeFireball.class, f -> {
                            f.setShooter(dragon);
                            f.setDirection(new Vector(0, -1, 0));
                            f.setYield(power);
                            f.setIsIncendiary(false);
                        });
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, startDelay, 2L);
        }
    }

    /** Homing void orbs (shulker bullets): levitation + damage, they follow you. */
    private void orbs(List<Player> targets) {
        int count = cfg().getInt("abilities.void-orbs.count", 3) + extra(targets);
        for (Player p : targets) warn(p, cfg().getString("messages.warn-orbs", ""));
        for (int i = 0; i < count; i++) {
            final Player p = randomOf(targets);
            final long delay = i * 8L;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!dragon.isValid() || !p.isOnline() || p.isDead() || !p.getWorld().equals(dragon.getWorld())) return;
                Location from = dragon.getLocation().add(0, 2, 0);
                Vector dir = p.getEyeLocation().toVector().subtract(from.toVector());
                if (dir.lengthSquared() < 1) return;
                dir.normalize();
                Location spawn = from.add(dir.multiply(5));
                dragon.getWorld().spawn(spawn, ShulkerBullet.class, b -> {
                    b.setShooter(dragon);
                    b.setTarget(p);
                });
            }, delay);
        }
    }

    /** Gravity well: everybody near gets dragged towards the dragon for a few seconds. */
    private void gravityWell(List<Player> targets) {
        final World w = dragon.getWorld();
        final double radius = cfg().getDouble("abilities.gravity-well.radius", 45);
        final double strength = cfg().getDouble("abilities.gravity-well.strength", 0.22);
        final int duration = cfg().getInt("abilities.gravity-well.duration-ticks", 50);
        for (Player p : targets) warn(p, cfg().getString("messages.warn-pull", ""));
        w.playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8f, 0.3f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!dragon.isValid()) {
                    cancel();
                    return;
                }
                Location c = dragon.getLocation();
                w.spawnParticle(Particle.PORTAL, c, 60, 4, 2, 4, 0.8);
                for (Player p : w.getPlayers()) {
                    if (!vulnerable(p)) continue;
                    Vector pull = c.toVector().subtract(p.getLocation().toVector());
                    if (pull.length() > radius || pull.length() < 4) continue;
                    pull.normalize().multiply(strength);
                    pull.setY(pull.getY() * 0.4);
                    p.setVelocity(p.getVelocity().add(pull));
                }
                if (++t > duration) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void spawnMinions(List<Player> targets) {
        minions.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });
        int cap = cfg().getInt("abilities.minions.max-alive", 14);
        int perWave = cfg().getInt("abilities.minions.per-wave", 4) + extra(targets);
        int life = cfg().getInt("abilities.minions.lifetime-seconds", 60);
        List<String> types = cfg().getStringList("abilities.minions.types");
        if (types.isEmpty()) return;

        for (int i = 0; i < perWave && minions.size() < cap; i++) {
            Player p = randomOf(targets);
            EntityType type;
            try {
                type = EntityType.valueOf(types.get(ThreadLocalRandom.current().nextInt(types.size())).toUpperCase());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Location loc = spotNear(p.getLocation());
            Entity e;
            try {
                e = p.getWorld().spawnEntity(loc, type);
            } catch (Exception ex) {
                continue;
            }
            e.getPersistentDataContainer().set(plugin.minionKey(), PersistentDataType.BYTE, (byte) 1);
            if (e instanceof Mob m) m.setTarget(p);
            minions.add(e.getUniqueId());
            plugin.models().attachMinion(e);
            p.getWorld().spawnParticle(Particle.PORTAL, loc, 40, 0.4, 0.8, 0.4, 0.3);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (e.isValid()) e.remove();
            }, life * 20L);
        }
    }

    private Location spotNear(Location center) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 10; attempt++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = 5 + r.nextDouble() * 4;
            Location l = center.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
            for (int dy = 2; dy >= -6; dy--) {
                Block b = l.clone().add(0, dy, 0).getBlock();
                if (b.getType().isSolid()
                        && b.getRelative(BlockFace.UP).isPassable()
                        && b.getRelative(0, 2, 0).isPassable()) {
                    return b.getLocation().add(0.5, 1, 0.5);
                }
            }
        }
        return center.clone().add(0, 1, 0);
    }

    private void roar(List<Player> targets) {
        double radius = cfg().getDouble("abilities.roar.radius", 50);
        Location dl = dragon.getLocation();
        dragon.getWorld().playSound(dl, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8f, 0.6f);
        String msg = cfg().getString("messages.roar", "");
        for (Player p : targets) {
            if (p.getLocation().distanceSquared(dl) > radius * radius) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
            if (!msg.isEmpty()) p.sendActionBar(Component.text(color(msg)));
        }
    }

    private void lift(List<Player> targets) {
        int ticks = cfg().getInt("abilities.levitation.duration-ticks", 40);
        String msg = cfg().getString("messages.lift", "");
        for (Player p : targets) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, ticks, 1));
            if (!msg.isEmpty()) p.sendActionBar(Component.text(color(msg)));
        }
    }

    // ------------------------------------------------------------------ bedrock / text warnings

    private boolean isBedrock(Player p) {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, p.getUniqueId());
        } catch (Throwable ignored) {
            return p.getUniqueId().getMostSignificantBits() == 0L;
        }
    }

    private void warn(Player p, String msg) {
        if (msg == null || msg.isEmpty()) return;
        String mode = cfg().getString("effects.warning-text", "bedrock");
        if (mode.equalsIgnoreCase("off")) return;
        if (mode.equalsIgnoreCase("bedrock") && !isBedrock(p)) return;
        p.sendActionBar(Component.text(color(msg)));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.HOSTILE, 1f, 0.5f);
    }

    // ------------------------------------------------------------------ tail + mouth

    private Vector tailDir() {
        Vector v = dragon.getLocation().getDirection().setY(0);
        if (v.lengthSquared() < 1.0E-4) v = new Vector(0, 0, 1);
        v.normalize();
        if (cfg().getBoolean("dragon.flip-facing", false)) v.multiply(-1);
        return v;
    }

    private Location mouth() {
        return dragon.getLocation().add(tailDir().multiply(-6.5)).add(0, 1.5, 0);
    }

    private static boolean vulnerable(Player p) {
        if (p.isDead()) return false;
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    private boolean anyWithin(List<Player> targets, double range) {
        Location dl = dragon.getLocation();
        for (Player p : targets) {
            if (p.getLocation().distanceSquared(dl) <= range * range) return true;
        }
        return false;
    }

    private void tailStrike() {
        final double damage = cfg().getDouble("abilities.tail.damage", 10);
        final double knock = cfg().getDouble("abilities.tail.knockback", 1.5);
        final World w = dragon.getWorld();
        final Set<UUID> hit = new HashSet<>();

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!dragon.isValid()) {
                    cancel();
                    return;
                }
                Location base = dragon.getLocation().add(0, -1, 0);
                Vector dir = tailDir();

                if (t < 10) {
                    if (t == 0) {
                        w.playSound(base, Sound.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.HOSTILE, 6f, 0.6f);
                        for (Player p : w.getPlayers()) {
                            if (p.getLocation().distanceSquared(base) <= 35 * 35) {
                                warn(p, cfg().getString("messages.warn-tail", ""));
                            }
                        }
                    }
                    Vector start = dir.clone().rotateAroundY(Math.toRadians(-70));
                    for (double d = 4; d <= 20; d += 2) {
                        Location l = base.clone().add(start.clone().multiply(d));
                        w.spawnParticle(Particle.FLAME, l, 2, 0.2, 0.2, 0.2, 0.01);
                    }
                } else {
                    double progress = (t - 10) / 10.0;
                    Vector swing = dir.clone().rotateAroundY(Math.toRadians(-70 + 140 * progress));
                    if (t == 10) {
                        w.playSound(base, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 6f, 0.5f);
                    }
                    for (double d = 4; d <= 20; d += 2) {
                        Location l = base.clone().add(swing.clone().multiply(d));
                        w.spawnParticle(Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0);
                        w.spawnParticle(Particle.LARGE_SMOKE, l, 2, 0.5, 0.5, 0.5, 0.02);
                    }
                    for (Player p : w.getPlayers()) {
                        if (!vulnerable(p) || hit.contains(p.getUniqueId())) continue;
                        Location pl = p.getLocation().add(0, 1, 0);
                        for (double d = 4; d <= 20; d += 2) {
                            Location tail = base.clone().add(swing.clone().multiply(d));
                            if (pl.distanceSquared(tail) > 3.5 * 3.5) continue;
                            hit.add(p.getUniqueId());
                            p.damage(damage, dragon);
                            Vector kb = p.getLocation().toVector().subtract(dragon.getLocation().toVector()).setY(0);
                            if (kb.lengthSquared() < 1.0E-4) kb = swing.clone();
                            kb.normalize().multiply(knock).setY(0.6);
                            p.setVelocity(kb);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0));
                            break;
                        }
                    }
                }
                if (++t > 20) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void breath(List<Player> targets) {
        final Player victim = randomOf(targets);
        final Location lock = victim.getLocation().add(0, 1, 0); // aim is locked -> dodge!
        final World w = dragon.getWorld();
        final int windup = Math.max(5, cfg().getInt("abilities.lightning-breath.windup-ticks", 22));
        final double damage = cfg().getDouble("abilities.lightning-breath.damage", 12);
        final double length = cfg().getDouble("abilities.lightning-breath.length", 70);
        // a single breath hit can never take more than this share of the player's max health (0 = no cap)
        final double maxFraction = cfg().getDouble("abilities.lightning-breath.max-health-fraction", 0.45);
        final double edgeFactor = cfg().getDouble("abilities.lightning-breath.edge-damage-factor", 0.6);
        final int fireTicks = cfg().getInt("abilities.lightning-breath.fire-ticks", 40);
        final int beamTicks = 8;
        final Set<UUID> hit = new HashSet<>();
        final Particle.DustOptions glow = new Particle.DustOptions(Color.fromRGB(200, 170, 255), 1.5f);

        new BukkitRunnable() {
            int t = 0;
            Vector dir = null;

            @Override
            public void run() {
                if (!dragon.isValid()) {
                    cancel();
                    return;
                }
                ThreadLocalRandom r = ThreadLocalRandom.current();
                Location mouth = mouth();

                if (t < windup) {
                    if (t == 0) {
                        w.playSound(mouth, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.HOSTILE, 5f, 2f);
                        warn(victim, cfg().getString("messages.warn-breath", ""));
                    }
                    double radius = 3.0 * (1.0 - (double) t / windup) + 0.3;
                    for (int k = 0; k < 8; k++) {
                        double a = t * 0.5 + k * Math.PI / 4;
                        w.spawnParticle(Particle.ELECTRIC_SPARK,
                                mouth.getX() + Math.cos(a) * radius,
                                mouth.getY() + Math.sin(a) * radius,
                                mouth.getZ() + Math.sin(a * 1.3) * radius, 1, 0, 0, 0, 0);
                    }
                    if (t % 4 == 0) {
                        for (int k = 0; k < 16; k++) {
                            double a = k * Math.PI / 8;
                            w.spawnParticle(Particle.ELECTRIC_SPARK, lock.getX() + Math.cos(a) * 1.5,
                                    lock.getY() - 0.8, lock.getZ() + Math.sin(a) * 1.5, 1, 0, 0, 0, 0);
                        }
                    }
                    if (t % 2 == 0) { // the lane the beam will use - step out of it
                        Vector lane = lock.toVector().subtract(mouth.toVector());
                        if (lane.lengthSquared() > 1.0E-4) {
                            lane.normalize();
                            double fade = (double) t / windup;
                            Particle.DustOptions laneDust = new Particle.DustOptions(
                                    Color.fromRGB(255, (int) (120 - 90 * fade), 60), 1.0f + (float) fade);
                            for (double d = 2; d <= length; d += 2.5) {
                                dust(w, mouth.getX() + lane.getX() * d, mouth.getY() + lane.getY() * d,
                                        mouth.getZ() + lane.getZ() * d, laneDust);
                            }
                        }
                    }
                } else {
                    if (dir == null) {
                        dir = lock.toVector().subtract(mouth.toVector());
                        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, -1, 0);
                        dir.normalize();
                        w.playSound(mouth, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 8f, 1.4f);
                    }
                    for (double d = 0; d <= length; d += 1.2) {
                        double x = mouth.getX() + dir.getX() * d + r.nextDouble(-0.35, 0.35);
                        double y = mouth.getY() + dir.getY() * d + r.nextDouble(-0.35, 0.35);
                        double z = mouth.getZ() + dir.getZ() * d + r.nextDouble(-0.35, 0.35);
                        w.spawnParticle(Particle.ELECTRIC_SPARK, x, y, z, 1, 0, 0, 0, 0);
                        w.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, glow);
                    }
                    if (t == windup + beamTicks / 2) w.strikeLightningEffect(lock);

                    for (Player p : w.getPlayers()) {
                        if (!vulnerable(p) || hit.contains(p.getUniqueId())) continue;
                        Vector pv = p.getLocation().add(0, 1, 0).toVector();
                        Vector v = pv.clone().subtract(mouth.toVector());
                        double along = Math.max(0, Math.min(length, v.dot(dir)));
                        Vector closest = mouth.toVector().add(dir.clone().multiply(along));
                        double dist = Math.sqrt(pv.distanceSquared(closest));
                        if (dist <= 2.5) {
                            hit.add(p.getUniqueId());
                            double maxHp = 20.0;
                            AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
                            if (mh != null) maxHp = mh.getValue();
                            // beam core hurts most, the rim only grazes
                            double dmg = damage * damageDealtMultiplier() * (dist <= 1.2 ? 1.0 : edgeFactor);
                            if (maxFraction > 0) dmg = Math.min(dmg, maxHp * maxFraction);
                            exactHits.put(p.getUniqueId(), dmg);
                            try {
                                p.damage(dmg, dragon);
                            } finally {
                                exactHits.remove(p.getUniqueId());
                            }
                            if (fireTicks > 0) p.setFireTicks(fireTicks);
                        }
                    }
                }
                if (++t > windup + beamTicks) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------------ visuals

    private void dust(World w, double x, double y, double z, Particle.DustOptions o) {
        w.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, o);
    }

    private boolean nearAny(List<Player> viewers, Location l, double dist) {
        for (Player p : viewers) {
            if (p.getLocation().distanceSquared(l) < dist * dist) return true;
        }
        return false;
    }

    private void animate() {
        if (!cfg().getBoolean("effects.crystal-circles.enabled", true)) return;
        World w = dragon.getWorld();
        List<Player> viewers = w.getPlayers();
        if (viewers.isEmpty()) return;
        anim += 0.10;

        Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(170, 40, 255), 1.1f);
        Particle.DustOptions pink = new Particle.DustOptions(Color.fromRGB(255, 120, 255), 0.9f);

        // living crystals: rune circle + spinning shield + translucent energy bubble
        boolean bubbleOn = plugin.models().enabled()
                && cfg().getBoolean("effects.crystal-bubble.enabled", true);
        boolean syncBubbles = (++bubbleTick % 5) == 0;
        for (Map.Entry<Integer, UUID> en : new ArrayList<>(crystalAt.entrySet())) {
            Entity c = Bukkit.getEntity(en.getValue());
            if (c == null || !c.isValid()) {
                CrystalBubble dead = bubbles.remove(en.getKey());
                if (dead != null) {
                    dead.remove();
                    w.spawnParticle(Particle.END_ROD, crystalSpots.get(en.getKey()).clone().add(0, 1, 0), 40, 1.5, 1.5, 1.5, 0.08);
                }
                continue;
            }
            Location base = c.getLocation();
            if (!nearAny(viewers, base, 48)) continue;
            drawRuneCircle(w, base.clone().add(0, 0.15, 0), purple, pink);
            drawShield(w, base.clone().add(0, 1, 0), purple);
            if (bubbleOn) {
                boolean anyJava = false;
                for (Player p : viewers) if (!plugin.models().isBedrock(p)) anyJava = true;
                CrystalBubble bub = bubbles.get(en.getKey());
                if (bub == null && anyJava) {
                    bub = plugin.models().bubble(base.clone().add(0, 1, 0));
                    if (bub != null) {
                        bubbles.put(en.getKey(), bub);
                        syncBubbles = true;
                    }
                }
                if (bub != null && syncBubbles) bub.tick(viewers);
            }
        }
        // crystals whose entry vanished while their bubble is still up
        for (Integer key : new ArrayList<>(bubbles.keySet())) {
            if (!crystalAt.containsKey(key)) {
                bubbles.remove(key).remove();
            }
        }

        // destroyed crystals: a ring that fills up as the respawn timer runs out
        for (Map.Entry<Integer, Long> en : new ArrayList<>(respawnDue.entrySet())) {
            Location spot = crystalSpots.get(en.getKey());
            if (!nearAny(viewers, spot, 48)) continue;
            long since = downSince.getOrDefault(en.getKey(), now);
            long total = en.getValue() - since;
            double progress = total <= 0 ? 1 : Math.min(1.0, (now - since) / (double) total);
            drawTimerRing(w, spot.clone().add(0, 0.15, 0), progress);
        }

        drawDragonShield(w, viewers);
    }

    private void drawTimerRing(World w, Location c, double progress) {
        Particle.DustOptions done = new Particle.DustOptions(Color.fromRGB(255, 200, 60), 1.3f);
        Particle.DustOptions todo = new Particle.DustOptions(Color.fromRGB(90, 20, 20), 0.8f);
        int n = 36;
        for (int i = 0; i < n; i++) {
            double f = i / (double) n;
            double a = f * 2 * Math.PI - Math.PI / 2;
            dust(w, c.getX() + Math.cos(a) * 2.6, c.getY(), c.getZ() + Math.sin(a) * 2.6, f <= progress ? done : todo);
        }
    }

    private void drawDragonShield(World w, List<Player> viewers) {
        if (!shieldFeature()) return;
        Location c = dragon.getLocation().add(0, 1, 0);
        if (!nearAny(viewers, c, 64)) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (shield == Shield.UP) {
            Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(150, 30, 255), 1.6f);
            // in HEAL_LOCK mode the bubble thins out as crystals fall, so you can SEE the shield weakening
            int shells = healLockMode() && !crystalSpots.isEmpty()
                    ? Math.max(3, 14 * aliveCrystals() / crystalSpots.size()) : 14;
            for (int i = 0; i < shells; i++) {
                Vector v = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
                if (v.lengthSquared() < 1.0E-4) continue;
                v.normalize().multiply(8);
                dust(w, c.getX() + v.getX(), c.getY() + v.getY(), c.getZ() + v.getZ(), purple);
            }
            if (r.nextDouble() < 0.4) {
                Vector v = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
                if (v.lengthSquared() > 1.0E-4) {
                    v.normalize().multiply(8);
                    w.spawnParticle(Particle.ELECTRIC_SPARK, c.getX() + v.getX(), c.getY() + v.getY(),
                            c.getZ() + v.getZ(), 3, 0.5, 0.5, 0.5, 0.05);
                }
            }
        } else {
            w.spawnParticle(Particle.CRIT, c, 8, 5, 3, 5, 0.1);
            if (healLockMode()) { // heal-locked: dark red embers and smoke
                Particle.DustOptions lockDust = new Particle.DustOptions(Color.fromRGB(150, 15, 15), 1.4f);
                for (int i = 0; i < 6; i++) {
                    Vector v = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
                    if (v.lengthSquared() < 1.0E-4) continue;
                    v.normalize().multiply(6);
                    dust(w, c.getX() + v.getX(), c.getY() + v.getY(), c.getZ() + v.getZ(), lockDust);
                }
                w.spawnParticle(Particle.SMOKE, c, 4, 4, 2, 4, 0.02);
            }
        }
    }

    private void drawRuneCircle(World w, Location c, Particle.DustOptions main, Particle.DustOptions accent) {
        double outer = 3.2, inner = 2.4;
        int n = 28;
        for (int i = 0; i < n; i++) {
            if (i % 4 == 3) continue;
            double a = anim * 0.6 + i * 2 * Math.PI / n;
            dust(w, c.getX() + Math.cos(a) * outer, c.getY(), c.getZ() + Math.sin(a) * outer, main);
        }
        int m = 20;
        for (int i = 0; i < m; i++) {
            double a = -anim * 0.9 + i * 2 * Math.PI / m;
            dust(w, c.getX() + Math.cos(a) * inner, c.getY(), c.getZ() + Math.sin(a) * inner, accent);
        }
        double rot = -anim * 0.4;
        for (int k = 0; k < 6; k++) {
            double a1 = rot + k * Math.PI / 3;
            double a2 = rot + ((k + 2) % 6) * Math.PI / 3;
            double x1 = Math.cos(a1) * inner, z1 = Math.sin(a1) * inner;
            double x2 = Math.cos(a2) * inner, z2 = Math.sin(a2) * inner;
            for (int s = 0; s < 5; s++) {
                double f = s / 5.0;
                dust(w, c.getX() + x1 + (x2 - x1) * f, c.getY(), c.getZ() + z1 + (z2 - z1) * f, main);
            }
        }
        double ra = anim * 1.3;
        w.spawnParticle(Particle.ENCHANT, c.getX() + Math.cos(ra) * outer, c.getY() + 0.8,
                c.getZ() + Math.sin(ra) * outer, 2, 0.1, 0.3, 0.1, 0.5);
    }

    private void drawShield(World w, Location c, Particle.DustOptions main) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double radius = 2.2;
        int n = 16;
        for (int ring = 0; ring < 3; ring++) {
            for (int i = 0; i < n; i++) {
                double th = i * 2 * Math.PI / n;
                Vector v = new Vector(Math.cos(th) * radius, Math.sin(th) * radius, 0);
                v.rotateAroundX(Math.PI * ring / 3.0 + anim * 0.5);
                v.rotateAroundY(anim * (ring % 2 == 0 ? 0.7 : -0.7) + ring * 1.05);
                dust(w, c.getX() + v.getX(), c.getY() + v.getY(), c.getZ() + v.getZ(), main);
            }
        }
        for (int i = 0; i < 4; i++) {
            Vector v = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
            if (v.lengthSquared() < 1.0E-4) continue;
            v.normalize().multiply(2.5);
            w.spawnParticle(Particle.WITCH, c.getX() + v.getX(), c.getY() + v.getY(), c.getZ() + v.getZ(),
                    1, 0, 0, 0, 0);
        }
        if (r.nextDouble() < 0.5) {
            Vector end = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
            if (end.lengthSquared() > 1.0E-4) {
                end.normalize().multiply(2.5);
                int segs = 6;
                for (int s = 1; s <= segs; s++) {
                    double f = s / (double) segs;
                    w.spawnParticle(Particle.ELECTRIC_SPARK,
                            c.getX() + end.getX() * f + r.nextGaussian() * 0.15,
                            c.getY() + end.getY() * f + r.nextGaussian() * 0.15,
                            c.getZ() + end.getZ() * f + r.nextGaussian() * 0.15, 1, 0, 0, 0, 0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ misc

    private void titleAll(String title, String sub) {
        for (Player p : dragon.getWorld().getPlayers()) {
            p.sendTitle(color(title), color(sub), 5, 50, 15);
        }
    }

    public String statusLine() {
        String sh = !shieldFeature() ? "off" : (shield == Shield.UP ? "UP" : "BROKEN " + Math.max(0, (brokenUntil - now) / 20) + "s");
        return String.format("§5Dragon §7HP §f%.0f§7/§f%.0f §7| Phase §f%d §7| Shield §f%s §7| Crystals §f%d§7/§f%d §7| Minions §f%d §7| Time §f%ds%s",
                dragon.getHealth(), maxHealth, phase, sh, aliveCrystals(), crystalSpots.size(),
                minions.size(), fightTicks / 20, doom ? " §4DOOM" : "");
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
