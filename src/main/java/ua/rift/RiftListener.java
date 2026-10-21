package ua.rift;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class RiftListener implements Listener {
    private final RiftManager manager;

    public RiftListener(RiftManager manager) {
        this.manager = manager;
    }

    // HIGHEST и ignoreCancelled=false: лут-блоки ломаются даже если приват пытается отменить событие
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent e) {
        manager.handleBreak(e);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        manager.handleMobDeath(e);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        manager.onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.onQuit(e.getPlayer());
    }

    // нельзя открыть меню маяка в центре Разлома
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (!manager.isRunning()) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType() == Material.BEACON) {
            org.bukkit.Location c = manager.getCenter();
            if (c != null && c.getBlockX() == b.getX() && c.getBlockZ() == b.getZ()) e.setCancelled(true);
        }
    }
}
