package com.kaerna.paperbackup.command;

import com.kaerna.paperbackup.PaperBackup;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class BackupCommand implements CommandExecutor, TabCompleter {

    private final PaperBackup plugin;

    public BackupCommand(PaperBackup plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("backup.admin")) {
            sender.sendMessage(color("&cYou do not have permission to run this command."));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(color("&6=== PaperBackup ==="));
            sender.sendMessage(color("&e/backup run &7- Start backup manually"));
            sender.sendMessage(color("&e/backup status &7- Show backup schedule and retention status"));
            sender.sendMessage(color("&e/backup reload &7- Reload configuration"));
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "run" -> {
                if (plugin.getBackupService().isRunning()) {
                    sender.sendMessage(color("&cBackup is already running!"));
                    return true;
                }
                sender.sendMessage(color("&aStarting manual backup..."));
                plugin.getBackupService().runBackup(true);
            }
            case "status" -> {
                for (String line : plugin.getStatusLines()) {
                    sender.sendMessage(color(line));
                }
            }
            case "reload" -> {
                if (plugin.reloadPlugin()) {
                    sender.sendMessage(color("&aPaperBackup configuration has been reloaded!"));
                } else {
                    sender.sendMessage(color("&cCannot reload while a backup is in progress. Try again after it completes."));
                }
            }
            default -> sender.sendMessage(color("&cUnknown subcommand. Use /backup for help."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("backup.admin")) {
            return completions;
        }
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            if ("run".startsWith(input)) completions.add("run");
            if ("status".startsWith(input)) completions.add("status");
            if ("reload".startsWith(input)) completions.add("reload");
        }
        return completions;
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
