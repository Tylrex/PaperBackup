package com.kaerna.paperbackup.backup;

import com.kaerna.paperbackup.backup.zip.ZipBackupWriter;
import com.kaerna.paperbackup.config.BackupConfig;
import com.kaerna.paperbackup.storage.BackupStorage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class BackupService {

    private final Plugin plugin;
    private final Logger logger;
    private final AtomicBoolean backupRunning = new AtomicBoolean(false);

    private volatile BackupConfig config;
    private volatile BackupStorage storage;
    private volatile ZipBackupWriter zipWriter;
    private volatile BackupNotifier notifier;
    private volatile MemoryReporter memoryReporter;

    public BackupService(
            Plugin plugin,
            Logger logger,
            BackupConfig config,
            BackupStorage storage,
            ZipBackupWriter zipWriter,
            BackupNotifier notifier,
            MemoryReporter memoryReporter
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.config = config;
        this.storage = storage;
        this.zipWriter = zipWriter;
        this.notifier = notifier;
        this.memoryReporter = memoryReporter;
    }

    public void reload(BackupConfig config, BackupStorage storage, ZipBackupWriter zipWriter,
                       BackupNotifier notifier, MemoryReporter memoryReporter) {
        this.config = config;
        this.storage = storage;
        this.zipWriter = zipWriter;
        this.notifier = notifier;
        this.memoryReporter = memoryReporter;
    }

    public boolean isRunning() {
        return backupRunning.get();
    }

    public void runBackup(boolean manual) {
        runBackup(manual, null);
    }

    public void runBackup(boolean manual, Consumer<Boolean> completionCallback) {
        if (!backupRunning.compareAndSet(false, true)) {
            if (manual) {
                notifier.notifyAdmins("&e[PaperBackup] Backup is already in progress.");
            }
            if (completionCallback != null) {
                completionCallback.accept(false);
            }
            return;
        }

        if (config.saveWorldsBeforeBackup) {
            saveWorlds();
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = false;
            try {
                success = createBackup();
            } finally {
                backupRunning.set(false);
                if (completionCallback != null) {
                    completionCallback.accept(success);
                }
            }
        });
    }

    private void saveWorlds() {
        for (World world : Bukkit.getWorlds()) {
            world.save();
        }
    }

    private boolean createBackup() {
        long startTime = System.currentTimeMillis();
        notifier.notifyAdmins("&a[PaperBackup] Starting server backup...");
        memoryReporter.logMemory("before backup");

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        String backupFileName = "backup-" + timestamp + ".zip";

        ZipBackupWriter writer = zipWriter;
        BackupStorage store = storage;

        try {
            BackupResult result = store.save(backupFileName, writer::write);
            long durationSeconds = (System.currentTimeMillis() - startTime) / 1000;

            if (result.isGoogleDrive()) {
                logger.info(String.format(Locale.ROOT,
                        "Google Drive backup finished successfully. File: %s, Drive ID: %s, Time: %d sec",
                        result.getFileName(), result.getDriveFileId(), durationSeconds));
                notifier.notifyAdmins(String.format(Locale.ROOT,
                        "&a[PaperBackup] Google Drive backup finished. File: &e%s&a, Time: &e%d sec",
                        result.getFileName(), durationSeconds));
            } else {
                double sizeMb = result.getFileSizeBytes() / (1024.0 * 1024.0);
                logger.info(String.format(Locale.ROOT,
                        "Backup finished successfully. File: %s, Size: %.2f MB, Time: %d sec",
                        result.getFileName(), sizeMb, durationSeconds));
                notifier.notifyAdmins(String.format(Locale.ROOT,
                        "&a[PaperBackup] Backup finished. File: &e%s&a, Size: &e%.2f MB&a, Time: &e%d sec",
                        result.getFileName(), sizeMb, durationSeconds));
            }

            memoryReporter.cleanup();
            memoryReporter.logMemory("after backup");
            memoryReporter.scheduleDelayedLog("after backup delayed");
            return true;

        } catch (Exception exception) {
            logger.severe("Backup failed: " + exception.getMessage());
            notifier.notifyAdmins("&c[PaperBackup] Backup failed: " + exception.getMessage());
            memoryReporter.cleanup();
            memoryReporter.logMemory("after failed backup");
            memoryReporter.scheduleDelayedLog("after failed backup delayed");
            return false;
        }
    }
}
