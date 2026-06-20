package com.kaerna.paperbackup.backup;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;

public class BackupNotifier implements Notifier {

    private final Plugin plugin;

    public BackupNotifier(Plugin plugin) {
        this.plugin = plugin;
    }

    public void notifyAdmins(String message) {
        String colored = ChatColor.translateAlternateColorCodes('&', message);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getConsoleSender().sendMessage(colored);
            Bukkit.getOnlinePlayers().stream()
                    .filter(player -> player.hasPermission("backup.admin"))
                    .forEach(player -> player.sendMessage(colored));
        });
    }
}
