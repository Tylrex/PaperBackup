package com.kaerna.paperbackup.config;

import com.kaerna.paperbackup.backup.zip.ExclusionMatcher;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class ConfigService {

    private static final String CONFIG_FILE_NAME = "google-drive-config.yml";
    private static final String LEGACY_CONFIG_FILE_NAME = "config.yml";

    private final JavaPlugin plugin;
    private File configFile;
    private FileConfiguration config;

    public ConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        configFile = new File(plugin.getDataFolder(), CONFIG_FILE_NAME);
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for " + CONFIG_FILE_NAME + ".");
        }

        if (!configFile.exists()) {
            File legacyFile = new File(plugin.getDataFolder(), LEGACY_CONFIG_FILE_NAME);
            if (legacyFile.exists()) {
                try {
                    Files.copy(legacyFile.toPath(), configFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                    plugin.getLogger().info("Migrated " + LEGACY_CONFIG_FILE_NAME + " to " + CONFIG_FILE_NAME + ".");
                } catch (IOException exception) {
                    plugin.getLogger().warning("Could not migrate " + LEGACY_CONFIG_FILE_NAME + " to "
                            + CONFIG_FILE_NAME + ": " + exception.getMessage());
                }
            } else {
                plugin.saveResource(CONFIG_FILE_NAME, false);
            }
        }

        load();
        applyDefaults();
    }

    public void reload() {
        load();
        applyDefaults();
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public BackupConfig buildConfig() {
        File serverRoot;
        try {
            serverRoot = plugin.getServer().getWorldContainer().getCanonicalFile();
        } catch (IOException exception) {
            serverRoot = new File(".").getAbsoluteFile();
        }

        String folderPath = config.getString("backup-folder", "backups");
        File configuredFolder = new File(folderPath == null || folderPath.isBlank() ? "backups" : folderPath);
        if (!configuredFolder.isAbsolute()) {
            configuredFolder = new File(serverRoot, configuredFolder.getPath());
        }
        File backupDir = canonicalOrAbsolute(configuredFolder);

        int maxBackups = config.getInt("max-backups", 10);
        long maxTotalSizeMb = config.getLong("max-total-size-mb", 10240);
        int minimumBackupsToKeep = Math.max(1, config.getInt("minimum-backups-to-keep", 1));
        boolean saveWorldsBeforeBackup = config.getBoolean("save-worlds-before-backup", true);
        boolean cleanupAfterBackup = config.getBoolean("memory.cleanup-after-backup",
                config.getBoolean("memory.request-gc-after-backup", true));
        int delayedMemoryLogSeconds = Math.max(0, config.getInt("memory.delayed-log-seconds", 15));
        boolean googleDriveEnabled = config.getBoolean("google-drive.enabled", false);
        int backupIntervalMinutes = config.getInt("backup-interval-minutes", 60);
        long startupDelaySeconds = Math.max(0L, config.getLong("startup-delay-seconds", 60L));
        boolean catchUpMissedBackup = config.getBoolean("catch-up-missed-backup-on-start", true);

        String serviceAccountPath = config.getString("google-drive.service-account-file",
                "plugins/PaperBackup/google-service-account.json");
        File serviceAccountFile = resolveFile(serverRoot, serviceAccountPath);

        int uploadChunkSizeKb = resolveUploadChunkSizeKb(maxBackups);

        return new BackupConfig(
                serverRoot, backupDir,
                maxBackups, maxTotalSizeMb, minimumBackupsToKeep,
                saveWorldsBeforeBackup, cleanupAfterBackup, delayedMemoryLogSeconds,
                googleDriveEnabled, ExclusionMatcher.normalizeExcludes(config.getStringList("exclude-paths")),
                backupIntervalMinutes, startupDelaySeconds, catchUpMissedBackup,
                config.getString("google-drive.auth-mode", "OAUTH"),
                serviceAccountFile,
                config.getString("google-drive.oauth.client-id", ""),
                config.getString("google-drive.oauth.client-secret", ""),
                config.getString("google-drive.oauth.refresh-token", ""),
                config.getString("google-drive.folder-id", ""),
                config.getInt("google-drive.max-backups", maxBackups),
                config.getLong("google-drive.max-total-size-mb", maxTotalSizeMb),
                Math.max(1, config.getInt("google-drive.minimum-backups-to-keep", minimumBackupsToKeep)),
                uploadChunkSizeKb,
                Math.max(256, config.getInt("google-drive.pipe-buffer-size-kb", 256)),
                config.getBoolean("google-drive.keep-client-between-backups", false)
        );
    }

    private int resolveUploadChunkSizeKb(int maxBackups) {
        if (config.isSet("google-drive.upload-chunk-size-kb")) {
            return Math.max(256, config.getInt("google-drive.upload-chunk-size-kb", 256));
        }
        int legacyMb = config.getInt("google-drive.upload-chunk-size-mb", 1);
        return Math.max(256, legacyMb * 1024);
    }

    private void load() {
        if (configFile == null) {
            configFile = new File(plugin.getDataFolder(), CONFIG_FILE_NAME);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        try (InputStream stream = plugin.getResource(CONFIG_FILE_NAME)) {
            if (stream != null) {
                FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not load bundled " + CONFIG_FILE_NAME + " defaults: " + exception.getMessage());
        }
    }

    private void applyDefaults() {
        try (InputStream stream = plugin.getResource(CONFIG_FILE_NAME)) {
            if (stream == null) {
                plugin.getLogger().warning("Could not find bundled " + CONFIG_FILE_NAME + " for migration.");
                return;
            }
            FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            saveConfig();
            load();
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not migrate " + CONFIG_FILE_NAME + ": " + exception.getMessage());
        }
    }

    private void saveConfig() {
        if (config == null || configFile == null) {
            return;
        }
        try {
            config.save(configFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save " + CONFIG_FILE_NAME + ": " + exception.getMessage());
        }
    }

    private static File resolveFile(File serverRoot, String path) {
        String value = path == null || path.isBlank() ? "plugins/PaperBackup/google-service-account.json" : path;
        File file = new File(value);
        if (file.isAbsolute()) {
            return canonicalOrAbsolute(file);
        }
        return canonicalOrAbsolute(new File(serverRoot, value));
    }

    private static File canonicalOrAbsolute(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException exception) {
            return file.getAbsoluteFile();
        }
    }
}
