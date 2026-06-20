package com.kaerna.paperbackup.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class StateService {

    private final JavaPlugin plugin;
    private File stateFile;
    private FileConfiguration stateConfig;

    public StateService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for state.yml.");
        }
        stateFile = new File(plugin.getDataFolder(), "state.yml");
        stateConfig = YamlConfiguration.loadConfiguration(stateFile);
    }

    public long getNextBackupAtMillis() {
        return stateConfig.getLong("next-backup-at-millis", 0L);
    }

    public void setNextBackupAtMillis(long millis) {
        stateConfig.set("next-backup-at-millis", millis);
        stateConfig.set("next-backup-at", formatTime(millis));
        save();
    }

    public long getLastScheduledBackupAtMillis() {
        return stateConfig.getLong("last-scheduled-backup-at-millis", 0L);
    }

    public void setLastScheduledBackupAtMillis(long millis) {
        stateConfig.set("last-scheduled-backup-at-millis", millis);
        stateConfig.set("last-scheduled-backup-at", formatTime(millis));
        save();
    }

    public void save() {
        try {
            stateConfig.save(stateFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save state.yml: " + exception.getMessage());
        }
    }

    public static String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ROOT).format(new Date(millis));
    }
}
