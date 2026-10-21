package ua.rift;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public final class RiftCommand implements CommandExecutor, TabCompleter {
    private final RiftPlugin plugin;

    public RiftCommand(RiftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("rift.admin")) {
            s.sendMessage("\u00a7cНет прав.");
            return true;
        }
        RiftManager m = plugin.manager();
        String sub = a.length == 0 ? "help" : a[0].toLowerCase();
        switch (sub) {
            case "start": {
                boolean quick = a.length > 1 && a[1].equalsIgnoreCase("now");
                if (m.isRunning()) s.sendMessage("\u00a7cРазлом уже идёт.");
                else if (m.isRestoring()) s.sendMessage("\u00a7cИдёт восстановление местности, подождите.");
                else if (!m.start(quick)) s.sendMessage("\u00a7cНе удалось найти подходящее место.");
                else s.sendMessage("\u00a7aРазлом запускается" + (quick ? " (быстрый старт)." : "."));
                return true;
            }
            case "stop":
                if (!m.isRunning()) s.sendMessage("\u00a7cРазлом не активен.");
                else {
                    m.stop(false);
                    s.sendMessage("\u00a7aРазлом остановлен.");
                }
                return true;
            case "info":
                s.sendMessage("\u00a7d[Разлом] \u00a7f" + m.info());
                return true;
            case "tp": {
                if (!(s instanceof Player)) {
                    s.sendMessage("Только для игроков.");
                    return true;
                }
                Location c = m.getCenter();
                if (c == null) {
                    s.sendMessage("\u00a7cРазлом не активен.");
                    return true;
                }
                c.setY(c.getWorld().getHighestBlockYAt(c.getBlockX() + 3, c.getBlockZ()) + 1);
                c.add(3, 0, 0);
                ((Player) s).teleport(c);
                return true;
            }
            case "reload":
                plugin.reloadConfig();
                s.sendMessage("\u00a7aКонфиг перезагружен.");
                return true;
            case "give": {
                if (a.length < 2 || !RiftItems.ALL.contains(a[1].toLowerCase())) {
                    s.sendMessage("\u00a7cИспользование: /rift give <midas|calm|boots|leggings> [игрок]");
                    return true;
                }
                Player target = a.length > 2 ? Bukkit.getPlayer(a[2]) : (s instanceof Player ? (Player) s : null);
                if (target == null) {
                    s.sendMessage("\u00a7cИгрок не найден.");
                    return true;
                }
                ItemStack it = plugin.items().create(a[1].toLowerCase());
                target.getInventory().addItem(it);
                s.sendMessage("\u00a7aВыдано: " + it.getItemMeta().getDisplayName());
                return true;
            }
            default:
                s.sendMessage("\u00a75\u00a7lРазлом \u00a77— команды:");
                s.sendMessage("\u00a7e/rift start [now] \u00a77— запустить (now = без долгого отсчёта)");
                s.sendMessage("\u00a7e/rift stop \u00a77— остановить");
                s.sendMessage("\u00a7e/rift info \u00a77— состояние");
                s.sendMessage("\u00a7e/rift tp \u00a77— телепорт к Разлому");
                s.sendMessage("\u00a7e/rift give <предмет> [игрок] \u00a77— выдать донат-предмет");
                s.sendMessage("\u00a7e/rift reload \u00a77— перезагрузить конфиг");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("rift.admin")) return out;
        if (a.length == 1) {
            for (String x : new String[]{"start", "stop", "info", "tp", "give", "reload"})
                if (x.startsWith(a[0].toLowerCase())) out.add(x);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("start")) {
            out.add("now");
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            for (String x : RiftItems.ALL) if (x.startsWith(a[1].toLowerCase())) out.add(x);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        return out;
    }
}
