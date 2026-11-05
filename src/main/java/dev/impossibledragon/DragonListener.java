package dev.impossibledragon;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class DragonListener implements Listener {

    private final ImpossibleDragon plugin;
    private final DragonManager manager;

    public DragonListener(ImpossibleDragon plugin, DragonManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // ---------------------------------------------------------------- tracking

    @EventHandler
    public void onSpawn(EntitySpawnEvent e) {
        if (e.getEntity() instanceof EnderDragon dragon) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (dragon.isValid()) manager.track(dragon, true);
            });
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity entity : e.getEntities()) {
            if (entity instanceof EnderDragon dragon) manager.track(dragon, false);
        }
    }

    // ---------------------------------------------------------------- damage

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDragonHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof EnderDragon dragon)) return;
        DragonFight fight = manager.get(dragon);
        if (fight == null) return;

        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            // the dragon must never hurt itself with its own fireballs / explosions
            if (sourceDragon(byEntity.getDamager()) == dragon) {
                e.setCancelled(true);
                return;
            }
            attacker = attackerOf(byEntity.getDamager());
        }
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return;

        double dmg = fight.modifyIncoming(e.getDamage(), attacker);
        if (dmg <= 0) {
            e.setCancelled(true);
            return;
        }
        e.setDamage(dmg);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerHurtByDragon(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        EnderDragon dragon = sourceDragon(e.getDamager());
        if (dragon == null) return;
        DragonFight fight = manager.get(dragon);
        if (fight == null) return;

        Double exact = fight.exactHit(e.getEntity().getUniqueId()); // breath etc: already final
        double dmg = exact != null ? exact : e.getDamage() * fight.damageDealtMultiplier();
        e.setDamage(dmg);

        double steal = cfg().getDouble("dragon.lifesteal", 0.5);
        AttributeInstance max = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (steal > 0 && max != null && fight.canHeal()) {
            dragon.setHealth(Math.min(max.getValue(), dragon.getHealth() + dmg * steal));
        }
    }

    private EnderDragon sourceDragon(Entity damager) {
        if (damager instanceof EnderDragon d) return d;
        if (damager instanceof Projectile p && p.getShooter() instanceof EnderDragon d) return d;
        if (damager instanceof AreaEffectCloud c && c.getSource() instanceof EnderDragon d) return d;
        return null;
    }

    private Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    // ---------------------------------------------------------------- projectiles

    @EventHandler
    public void onFireballHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof DragonFireball fb)) return;
        if (!(fb.getShooter() instanceof EnderDragon dragon) || !manager.isTracked(dragon)) return;
        double power = cfg().getDouble("abilities.fireball.explosion-power", 3.0);
        if (power > 0) {
            fb.getWorld().createExplosion(fb.getLocation(), (float) power, false, false, dragon);
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        if (!(e.getEntity() instanceof Projectile pr)
                || !(pr.getShooter() instanceof EnderDragon dragon)
                || !manager.isTracked(dragon)) return;
        boolean allow = pr instanceof WitherSkull
                ? cfg().getBoolean("abilities.wither-skulls.break-blocks", false)
                : cfg().getBoolean("abilities.meteors.break-blocks", false);
        if (!allow) e.blockList().clear();
    }

    // ---------------------------------------------------------------- death

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Entity dead = e.getEntity();

        if (dead.getPersistentDataContainer().has(plugin.minionKey(), PersistentDataType.BYTE)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }

        if (!(dead instanceof EnderDragon dragon) || !manager.isTracked(dragon)) return;

        DragonFight fight = manager.get(dragon);
        fight.removeMinions();
        manager.untrack(dragon.getUniqueId());

        World world = dragon.getWorld();
        String msg = cfg().getString("messages.death", "");
        if (!msg.isEmpty()) {
            String text = ChatColor.translateAlternateColorCodes('&', msg);
            for (Player p : world.getPlayers()) p.sendMessage(text);
        }

        Location drop = dragon.getLocation();
        DragonBattle battle = dragon.getDragonBattle();
        if (battle != null && battle.getEndPortalLocation() != null) {
            drop = battle.getEndPortalLocation().clone().add(0.5, 4, 0.5);
        }
        final Location dropAt = drop;

        int xp = cfg().getInt("rewards.bonus-xp", 5000);
        if (xp > 0) {
            world.spawn(dropAt, ExperienceOrb.class, orb -> orb.setExperience(xp));
        }
        for (String entry : cfg().getStringList("rewards.items")) {
            String[] parts = entry.split(":");
            Material mat = Material.matchMaterial(parts[0].trim());
            if (mat == null || mat.isAir()) {
                plugin.getLogger().warning("Unknown reward material: " + entry);
                continue;
            }
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    // keep 1
                }
            }
            while (amount > 0) {
                int stack = Math.min(amount, mat.getMaxStackSize());
                world.dropItemNaturally(dropAt, new ItemStack(mat, stack));
                amount -= stack;
            }
        }
    }
}
