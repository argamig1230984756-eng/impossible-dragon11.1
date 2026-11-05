package dev.impossibledragon;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderDragon;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class ImpossibleDragon extends JavaPlugin {

    private DragonManager manager;
    private ModelManager models;
    private NamespacedKey minionKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        minionKey = new NamespacedKey(this, "minion");
        models = new ModelManager(this);
        manager = new DragonManager(this);
        Bukkit.getPluginManager().registerEvents(new DragonListener(this, manager), this);

        // Dragons that already exist (e.g. after /reload)
        for (World world : Bukkit.getWorlds()) {
            for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
                manager.track(dragon, false);
            }
        }
        getLogger().info("Impossible Dragon enabled. Good luck.");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
        if (models != null) models.shutdown();
    }

    public ModelManager models() {
        return models;
    }

    public NamespacedKey minionKey() {
        return minionKey;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                manager.retuneAll();
                sender.sendMessage("§aImpossibleDragon config reloaded.");
            }
            case "retune" -> {
                manager.retuneAll();
                sender.sendMessage("§aRe-applied stats to " + manager.count() + " dragon(s).");
            }
            case "status" -> {
                if (manager.count() == 0) {
                    sender.sendMessage("§7No active Impossible Dragon.");
                } else {
                    manager.all().forEach(f -> sender.sendMessage(f.statusLine()));
                }
            }
            default -> sender.sendMessage("§cUsage: /" + label + " <reload|status|retune>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("reload", "status", "retune") : List.of();
    }
}
