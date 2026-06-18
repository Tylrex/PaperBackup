package ua.vlad.backup;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private static final int COPY_BUFFER_SIZE = 8192;

    private final PaperBackup plugin;
    private final AtomicBoolean backupRunning = new AtomicBoolean(false);

    private File serverRoot;
    private File backupDir;
    private int maxBackups;
    private long maxTotalSizeMb;
    private int minimumBackupsToKeep;
    private boolean saveWorldsBeforeBackup;
    private boolean googleDriveEnabled;
    private GoogleDriveStorage googleDriveStorage;
    private List<String> excludePaths;

    public BackupManager(PaperBackup plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    BackupManager(File serverRoot, File backupDir, List<String> excludePaths) {
        this.plugin = null;
        this.serverRoot = canonicalOrAbsolute(serverRoot);
        this.backupDir = canonicalOrAbsolute(backupDir);
        this.excludePaths = normalizeExcludes(excludePaths);
        this.maxBackups = -1;
        this.maxTotalSizeMb = -1;
        this.minimumBackupsToKeep = 1;
    }

    public void loadConfig() {
        if (plugin == null) {
            return;
        }

        try {
            this.serverRoot = plugin.getServer().getWorldContainer().getCanonicalFile();
        } catch (IOException exception) {
            this.serverRoot = new File(".").getAbsoluteFile();
        }

        String folderPath = plugin.getConfig().getString("backup-folder", "backups");
        File configuredFolder = new File(folderPath == null || folderPath.isBlank() ? "backups" : folderPath);
        if (!configuredFolder.isAbsolute()) {
            configuredFolder = new File(serverRoot, configuredFolder.getPath());
        }
        this.backupDir = canonicalOrAbsolute(configuredFolder);

        this.maxBackups = plugin.getConfig().getInt("max-backups", 10);
        this.maxTotalSizeMb = plugin.getConfig().getLong("max-total-size-mb", 10240);
        this.minimumBackupsToKeep = Math.max(1, plugin.getConfig().getInt("minimum-backups-to-keep", 1));
        this.saveWorldsBeforeBackup = plugin.getConfig().getBoolean("save-worlds-before-backup", true);
        this.googleDriveEnabled = plugin.getConfig().getBoolean("google-drive.enabled", false);
        this.googleDriveStorage = null;
        if (googleDriveEnabled) {
            this.googleDriveStorage = new GoogleDriveStorage(
                    plugin.getLogger(),
                    plugin.getConfig().getString("google-drive.auth-mode", "OAUTH"),
                    resolveConfiguredFile(plugin.getConfig().getString("google-drive.service-account-file", "plugins/PaperBackup/google-service-account.json")),
                    plugin.getConfig().getString("google-drive.oauth.client-id", ""),
                    plugin.getConfig().getString("google-drive.oauth.client-secret", ""),
                    plugin.getConfig().getString("google-drive.oauth.refresh-token", ""),
                    plugin.getConfig().getString("google-drive.folder-id", ""),
                    plugin.getConfig().getInt("google-drive.max-backups", maxBackups),
                    plugin.getConfig().getLong("google-drive.max-total-size-mb", maxTotalSizeMb),
                    Math.max(1, plugin.getConfig().getInt("google-drive.minimum-backups-to-keep", minimumBackupsToKeep))
            );
        }
        this.excludePaths = normalizeExcludes(plugin.getConfig().getStringList("exclude-paths"));
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
                notifyAdmins("&e[PaperBackup] Backup is already in progress.");
            }
            if (completionCallback != null) {
                completionCallback.accept(false);
            }
            return;
        }

        if (plugin != null && saveWorldsBeforeBackup) {
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
        notifyAdmins("&a[PaperBackup] Starting server backup...");
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        String backupFileName = "backup-" + timestamp + ".zip";

        if (googleDriveEnabled) {
            return createGoogleDriveBackup(startTime, backupFileName);
        }

        if (!backupDir.exists() && !backupDir.mkdirs()) {
            plugin.getLogger().severe("Failed to create backup directory: " + backupDir.getPath());
            notifyAdmins("&c[PaperBackup] Failed to create backup directory.");
            return false;
        }

        File zipFile = new File(backupDir, backupFileName);

        try (FileOutputStream fileOutput = new FileOutputStream(zipFile);
             ZipOutputStream zipOutput = new ZipOutputStream(fileOutput)) {
            zipDirectory(serverRoot.toPath(), zipOutput, zipFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Failed to write backup zip: " + exception.getMessage());
            notifyAdmins("&c[PaperBackup] Backup failed: " + exception.getMessage());
            deletePartialBackup(zipFile);
            return false;
        }

        long durationSeconds = (System.currentTimeMillis() - startTime) / 1000;
        double sizeMb = zipFile.length() / (1024.0 * 1024.0);
        plugin.getLogger().info(String.format(Locale.ROOT,
                "Backup finished successfully. File: %s, Size: %.2f MB, Time: %d sec",
                zipFile.getName(), sizeMb, durationSeconds));
        notifyAdmins(String.format(Locale.ROOT,
                "&a[PaperBackup] Backup finished. File: &e%s&a, Size: &e%.2f MB&a, Time: &e%d sec",
                zipFile.getName(), sizeMb, durationSeconds));

        pruneOldBackups();
        return true;
    }

    private boolean createGoogleDriveBackup(long startTime, String backupFileName) {
        if (googleDriveStorage == null) {
            plugin.getLogger().severe("Google Drive backup is enabled, but storage is not configured.");
            notifyAdmins("&c[PaperBackup] Google Drive backup is not configured.");
            return false;
        }

        try {
            GoogleDriveStorage.UploadResult uploadResult = googleDriveStorage.upload(backupFileName, outputStream -> {
                try (ZipOutputStream zipOutput = new ZipOutputStream(outputStream)) {
                    zipDirectory(serverRoot.toPath(), zipOutput, null);
                }
            });

            long durationSeconds = (System.currentTimeMillis() - startTime) / 1000;
            plugin.getLogger().info(String.format(Locale.ROOT,
                    "Google Drive backup finished successfully. File: %s, Drive ID: %s, Time: %d sec",
                    uploadResult.fileName(), uploadResult.fileId(), durationSeconds));
            notifyAdmins(String.format(Locale.ROOT,
                    "&a[PaperBackup] Google Drive backup finished. File: &e%s&a, Time: &e%d sec",
                    uploadResult.fileName(), durationSeconds));
            return true;
        } catch (IOException | GeneralSecurityException exception) {
            String message = getGoogleDriveFailureMessage(exception);
            plugin.getLogger().severe("Google Drive backup failed: " + message);
            notifyAdmins("&c[PaperBackup] Google Drive backup failed: " + message);
            return false;
        }
    }

    private String getGoogleDriveFailureMessage(Exception exception) {
        String message = exception.getMessage();
        if (message != null && message.contains("Service Accounts do not have storage quota")) {
            return "Service account cannot upload to a normal personal Google Drive because it has no storage quota. "
                    + "Use google-drive.auth-mode: OAUTH, or use SERVICE_ACCOUNT only with a Google Workspace Shared Drive.";
        }
        return message == null ? exception.getClass().getSimpleName() : message;
    }

    private void zipDirectory(Path root, ZipOutputStream zipOutput, File currentZipFile) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                File directory = dir.toFile();
                if (isExcluded(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                String relativePath = getRelativePathForZip(directory);
                if (!relativePath.isEmpty()) {
                    zipOutput.putNextEntry(new ZipEntry(relativePath + "/"));
                    zipOutput.closeEntry();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                File source = file.toFile();
                if (source.equals(currentZipFile) || isExcluded(source)) {
                    return FileVisitResult.CONTINUE;
                }

                addFileToZip(source, zipOutput);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                plugin.getLogger().warning("Skipping inaccessible path " + file + ": " + exception.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void addFileToZip(File source, ZipOutputStream zipOutput) {
        String relativePath = getRelativePathForZip(source);
        if (relativePath.isEmpty()) {
            return;
        }

        boolean entryOpen = false;
        try (FileInputStream input = new FileInputStream(source)) {
            zipOutput.putNextEntry(new ZipEntry(relativePath));
            entryOpen = true;

            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                zipOutput.write(buffer, 0, length);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Skipping file " + source.getPath() + ": " + exception.getMessage());
        } finally {
            if (entryOpen) {
                try {
                    zipOutput.closeEntry();
                } catch (IOException exception) {
                    plugin.getLogger().warning("Could not close zip entry for " + source.getPath() + ": " + exception.getMessage());
                }
            }
        }
    }

    private File resolveConfiguredFile(String path) {
        String value = path == null || path.isBlank() ? "plugins/PaperBackup/google-service-account.json" : path;
        File file = new File(value);
        if (file.isAbsolute()) {
            return canonicalOrAbsolute(file);
        }
        return canonicalOrAbsolute(new File(serverRoot, value));
    }

    boolean isExcluded(File file) {
        try {
            String fileCanonical = file.getCanonicalPath();
            String serverRootCanonical = serverRoot.getCanonicalPath();
            String backupDirCanonical = backupDir.getCanonicalPath();

            if (fileCanonical.equals(backupDirCanonical) || fileCanonical.startsWith(backupDirCanonical + File.separator)) {
                return true;
            }
            if (fileCanonical.equals(serverRootCanonical)) {
                return false;
            }
            if (!fileCanonical.startsWith(serverRootCanonical + File.separator)) {
                return true;
            }

            String relativePath = fileCanonical.substring(serverRootCanonical.length() + 1).replace('\\', '/');
            for (String exclude : excludePaths) {
                if (relativePath.equals(exclude) || relativePath.startsWith(exclude + "/")) {
                    return true;
                }
            }
        } catch (IOException exception) {
            if (plugin != null) {
                plugin.getLogger().warning("Could not check exclusions for " + file.getPath() + ": " + exception.getMessage());
            }
            return true;
        }
        return false;
    }

    private String getRelativePathForZip(File file) {
        try {
            String fileCanonical = file.getCanonicalPath();
            String serverRootCanonical = serverRoot.getCanonicalPath();
            if (fileCanonical.equals(serverRootCanonical)) {
                return "";
            }
            return fileCanonical.substring(serverRootCanonical.length() + 1).replace('\\', '/');
        } catch (IOException exception) {
            return "";
        }
    }

    private void pruneOldBackups() {
        File[] files = backupDir.listFiles((dir, name) -> {
            String lowerName = name.toLowerCase(Locale.ROOT);
            return lowerName.startsWith("backup-") && lowerName.endsWith(".zip");
        });
        if (files == null || files.length == 0) {
            return;
        }

        List<File> zipFiles = new ArrayList<>(List.of(files));
        zipFiles.sort(Comparator.comparingLong(File::lastModified));

        plugin.getLogger().info(String.format(Locale.ROOT,
                "Backup cleanup check: files=%d, total=%.2f MB, max-backups=%d, minimum-backups-to-keep=%d, max-total-size-mb=%d",
                zipFiles.size(), calculateTotalSize(zipFiles) / (1024.0 * 1024.0),
                maxBackups, minimumBackupsToKeep, maxTotalSizeMb));

        pruneByTotalSize(zipFiles);
        pruneByCount(zipFiles);
    }

    private void pruneByTotalSize(List<File> zipFiles) {
        if (maxTotalSizeMb <= 0) {
            return;
        }

        long maxTotalSizeBytes = maxTotalSizeMb * 1024L * 1024L;
        long currentTotalSize = calculateTotalSize(zipFiles);
        while (currentTotalSize > maxTotalSizeBytes && zipFiles.size() > minimumBackupsToKeep) {
            File oldest = zipFiles.remove(0);
            long fileSize = oldest.length();
            if (oldest.delete()) {
                currentTotalSize -= fileSize;
                plugin.getLogger().info("Deleted oldest backup due to size limit: " + oldest.getName());
                notifyAdmins("&6[PaperBackup] Deleted oldest backup due to size limit: &e" + oldest.getName());
            } else {
                plugin.getLogger().warning("Failed to delete backup file: " + oldest.getName());
                return;
            }
        }
    }

    private void pruneByCount(List<File> zipFiles) {
        if (maxBackups <= 0) {
            return;
        }

        while (zipFiles.size() > maxBackups) {
            if (zipFiles.size() <= minimumBackupsToKeep) {
                return;
            }
            File oldest = zipFiles.remove(0);
            if (oldest.delete()) {
                plugin.getLogger().info("Deleted oldest backup due to count limit: " + oldest.getName());
                notifyAdmins("&6[PaperBackup] Deleted oldest backup due to count limit: &e" + oldest.getName());
            } else {
                plugin.getLogger().warning("Failed to delete backup file: " + oldest.getName());
                return;
            }
        }
    }

    private long calculateTotalSize(List<File> files) {
        long total = 0;
        for (File file : files) {
            total += file.length();
        }
        return total;
    }

    private void deletePartialBackup(File zipFile) {
        if (zipFile.exists() && !zipFile.delete()) {
            plugin.getLogger().warning("Could not delete failed partial backup: " + zipFile.getName());
        }
    }

    private void notifyAdmins(String message) {
        String colored = ChatColor.translateAlternateColorCodes('&', message);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getConsoleSender().sendMessage(colored);
            Bukkit.getOnlinePlayers().stream()
                    .filter(player -> player.hasPermission("backup.admin"))
                    .forEach(player -> player.sendMessage(colored));
        });
    }

    private static File canonicalOrAbsolute(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException exception) {
            return file.getAbsoluteFile();
        }
    }

    private static List<String> normalizeExcludes(List<String> rawExcludes) {
        List<String> normalized = new ArrayList<>();
        if (rawExcludes == null) {
            return normalized;
        }

        for (String raw : rawExcludes) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim().replace('\\', '/');
            while (value.startsWith("./")) {
                value = value.substring(2);
            }
            while (value.endsWith("/") && value.length() > 1) {
                value = value.substring(0, value.length() - 1);
            }
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return normalized;
    }
}
