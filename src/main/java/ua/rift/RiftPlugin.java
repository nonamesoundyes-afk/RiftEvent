package ua.rift;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Random;

public final class RiftPlugin extends JavaPlugin {
    private RiftManager manager;
    private RiftItems items;
    private long nextAuto;
    private final Random rnd = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        items = new RiftItems(this);
        manager = new RiftManager(this);
        Bukkit.getPluginManager().registerEvents(new RiftListener(manager), this);

        PluginCommand pc = getCommand("rift");
        if (pc != null) {
            RiftCommand rc = new RiftCommand(this);
            pc.setExecutor(rc);
            pc.setTabCompleter(rc);
        }

        // эффекты шаров — раз в секунду
        Bukkit.getScheduler().runTaskTimer(this, () -> items.tickOrbs(), 20L, 20L);

        // автозапуск
        scheduleNext();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!getConfig().getBoolean("auto.enabled", true)) return;
            if (manager.isRunning() || manager.isRestoring()) return;
            if (System.currentTimeMillis() < nextAuto) return;
            if (Bukkit.getOnlinePlayers().size() < getConfig().getInt("auto.min-players", 2)) return;
            manager.start(false);
            scheduleNext();
        }, 200L, 200L);

        getLogger().info("Разлом включён.");
    }

    private void scheduleNext() {
        long base = getConfig().getLong("auto.interval-minutes", 45) * 60_000L;
        double j = getConfig().getInt("auto.jitter-percent", 20) / 100.0;
        double k = 1.0 + (rnd.nextDouble() * 2 - 1) * j;
        nextAuto = System.currentTimeMillis() + (long) (base * k);
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.stop(true);
    }

    public RiftManager manager() { return manager; }
    public RiftItems items() { return items; }
}
