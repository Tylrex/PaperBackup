package ua.vlad.backup;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class BackupCommand implements CommandExecutor, TabCompleter {

    private final PaperBackup plugin;

    public BackupCommand(PaperBackup plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("backup.admin")) {
            sender.sendMessage("§cYou do not have permission to run this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6=== PaperBackup ===");
            sender.sendMessage("§e/backup run §7- Start backup manually");
            sender.sendMessage("§e/backup reload §7- Reload configuration");
            return true;
        }

        String subCommand = args[0].toLowerCase();
        if (subCommand.equals("run")) {
            if (plugin.getBackupManager().isRunning()) {
                sender.sendMessage("§cBackup is already running!");
                return true;
            }
            sender.sendMessage("§aStarting manual backup...");
            plugin.getBackupManager().runBackup(true);
            return true;
        } else if (subCommand.equals("reload")) {
            plugin.reloadPlugin();
            sender.sendMessage("§aPaperBackup configuration has been reloaded!");
            return true;
        } else {
            sender.sendMessage("§cUnknown subcommand. Use /backup for help.");
            return true;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("backup.admin")) {
            return completions;
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            if ("run".startsWith(input)) completions.add("run");
            if ("reload".startsWith(input)) completions.add("reload");
        }

        return completions;
    }
}
