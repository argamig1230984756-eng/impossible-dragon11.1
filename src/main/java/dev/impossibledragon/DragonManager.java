package dev.impossibledragon;

import org.bukkit.entity.EnderDragon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DragonManager {

    private final ImpossibleDragon plugin;
    private final Map<UUID, DragonFight> fights = new HashMap<>();

    public DragonManager(ImpossibleDragon plugin) {
        this.plugin = plugin;
    }

    /** @param fresh true for a newly spawned dragon (starts at full health) */
    public void track(EnderDragon dragon, boolean fresh) {
        if (fights.containsKey(dragon.getUniqueId())) return;
        fights.put(dragon.getUniqueId(), new DragonFight(plugin, this, dragon, fresh));
    }

    public DragonFight get(EnderDragon dragon) {
        return fights.get(dragon.getUniqueId());
    }

    public boolean isTracked(EnderDragon dragon) {
        return fights.containsKey(dragon.getUniqueId());
    }

    public void untrack(UUID id) {
        DragonFight fight = fights.remove(id);
        if (fight != null) fight.stop();
    }

    public Collection<DragonFight> all() {
        return new ArrayList<>(fights.values());
    }

    public int count() {
        return fights.size();
    }

    public void retuneAll() {
        for (DragonFight f : all()) f.applyStats(false);
    }

    public void shutdown() {
        for (DragonFight f : all()) f.stop();
        fights.clear();
    }
}
