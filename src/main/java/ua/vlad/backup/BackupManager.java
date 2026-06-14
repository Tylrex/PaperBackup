package ua.vlad.backup;

import org.bukkit.Bukkit;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private final PaperBackup plugin;
    private final AtomicBoolean isBackupRunning = new AtomicBoolean(false);
    
    private File serverRoot;
    private File backupDir;
    private int maxBackups;
    private long maxTotalSizeMb;
    private List<String> excludePaths;

    public BackupManager(PaperBackup plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    // Package-private constructor for testing purposes
    BackupManager(File serverRoot, File backupDir, List<String> excludePaths) {
        this.plugin = null;
        this.serverRoot = serverRoot;
        this.backupDir = backupDir;
        this.excludePaths = excludePaths;
    }

    public void loadConfig() {
        plugin.reloadConfig();
        
        try {
            this.serverRoot = plugin.getServer().getWorldContainer().getCanonicalFile();
        } catch (IOException e) {
            this.serverRoot = new File(".").getAbsoluteFile();
        }

        String folderPath = plugin.getConfig().getString("backup-folder", "backups");
        File folder = new File(folderPath);
        if (!folder.isAbsolute()) {
            folder = new File(serverRoot, folderPath);
        }
        try {
            this.backupDir = folder.getCanonicalFile();
        } catch (IOException e) {
            this.backupDir = folder.getAbsoluteFile();
        }

        this.maxBackups = plugin.getConfig().getInt("max-backups", 10);
        this.maxTotalSizeMb = plugin.getConfig().getLong("max-total-size-mb", 10240);
        
        List<String> rawExcludes = plugin.getConfig().getStringList("exclude-paths");
        this.excludePaths = new ArrayList<>();
        if (rawExcludes != null) {
            for (String raw : rawExcludes) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) {
                    this.excludePaths.add(trimmed);
                }
            }
        }
    }

    public boolean isRunning() {
        return isBackupRunning.get();
    }

    public void runBackup(boolean manual) {
        if (!isBackupRunning.compareAndSet(false, true)) {
            if (manual) {
                notifyAdmins("§e[PaperBackup] Backup is already in progress!");
            }
            return;
        }

        // Run the task asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long startTime = System.currentTimeMillis();
            notifyAdmins("§a[PaperBackup] Starting server backup...");

            if (!backupDir.exists()) {
                if (!backupDir.mkdirs()) {
                    plugin.getLogger().severe("Failed to create backup directory: " + backupDir.getPath());
                    notifyAdmins("§c[PaperBackup] Failed to create backup directory!");
                    isBackupRunning.set(false);
                    return;
                }
            }

            String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File zipFile = new File(backupDir, "backup-" + timeStamp + ".zip");

            try (FileOutputStream fos = new FileOutputStream(zipFile);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                
                zos.setLevel(ZipOutputStream.DEFLATED);
                zipDirectory(serverRoot, zos, zipFile);
                
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to write backup zip: " + e.getMessage());
                notifyAdmins("§c[PaperBackup] Backup failed due to an error: " + e.getMessage());
                if (zipFile.exists()) {
                    zipFile.delete();
                }
                isBackupRunning.set(false);
                return;
            }

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            long sizeInBytes = zipFile.length();
            double sizeInMb = sizeInBytes / (1024.0 * 1024.0);

            plugin.getLogger().info(String.format("Backup finished successfully! File: %s, Size: %.2f MB, Time: %d sec",
                    zipFile.getName(), sizeInMb, duration));
            notifyAdmins(String.format("§a[PaperBackup] Backup finished! File: §e%s§a, Size: §e%.2f MB§a, Time: §e%d sec",
                    zipFile.getName(), sizeInMb, duration));

            // Clean up old backups according to config limits
            try {
                pruneOldBackups();
            } catch (Exception e) {
                plugin.getLogger().warning("Error while pruning old backups: " + e.getMessage());
            }

            isBackupRunning.set(false);
        });
    }

    private void zipDirectory(File folderToZip, ZipOutputStream zipOut, File currentZipFile) {
        try {
            java.nio.file.Files.walkFileTree(folderToZip.toPath(), new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    File file = dir.toFile();
                    if (isExcluded(file)) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    
                    String relativePath = getRelativePathForZip(file);
                    if (!relativePath.isEmpty()) {
                        ZipEntry entry = new ZipEntry(relativePath + "/");
                        zipOut.putNextEntry(entry);
                        zipOut.closeEntry();
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    File f = file.toFile();
                    if (isExcluded(f) || f.equals(currentZipFile)) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    String relativePath = getRelativePathForZip(f);
                    try {
                        ZipEntry zipEntry = new ZipEntry(relativePath);
                        zipOut.putNextEntry(zipEntry);
                        
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                            byte[] buffer = new byte[8192];
                            int length;
                            while ((length = fis.read(buffer)) >= 0) {
                                zipOut.write(buffer, 0, length);
                            }
                        }
                        zipOut.closeEntry();
                    } catch (IOException e) {
                        // Skip files that cannot be read (like locks, or files deleted mid-operation)
                        plugin.getLogger().warning("Skipping file " + f.getName() + " due to read error: " + e.getMessage());
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path file, IOException exc) {
                    plugin.getLogger().warning("Failed to access path " + file.toString() + ": " + exc.getMessage());
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            plugin.getLogger().severe("Error walking server root: " + e.getMessage());
        }
    }

    boolean isExcluded(File file) {
        try {
            String fileCanonical = file.getCanonicalPath();
            String serverRootCanonical = serverRoot.getCanonicalPath();
            String backupDirCanonical = backupDir.getCanonicalPath();

            // Always exclude the backup folder itself to avoid recursive backing up
            if (fileCanonical.equals(backupDirCanonical) || fileCanonical.startsWith(backupDirCanonical + File.separator)) {
                return true;
            }

            if (fileCanonical.equals(serverRootCanonical)) {
                return false;
            }

            if (!fileCanonical.startsWith(serverRootCanonical + File.separator)) {
                return true; // Outside server directory
            }

            String relativePath = fileCanonical.substring(serverRootCanonical.length() + 1);
            String relativePathSlash = relativePath.replace('\\', '/');

            for (String exclude : excludePaths) {
                String excludeSlash = exclude.replace('\\', '/');
                if (relativePathSlash.equals(excludeSlash) || relativePathSlash.startsWith(excludeSlash + "/")) {
                    return true;
                }
            }
        } catch (IOException e) {
            if (plugin != null) {
                plugin.getLogger().warning("Could not check exclusions for " + file.getPath() + ": " + e.getMessage());
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
            String rel = fileCanonical.substring(serverRootCanonical.length() + 1);
            return rel.replace('\\', '/');
        } catch (IOException e) {
            return "";
        }
    }

    private void pruneOldBackups() {
        File[] files = backupDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (files == null || files.length == 0) {
            return;
        }

        // Sort files by last modified date (oldest first)
        List<File> zipFiles = new ArrayList<>(Arrays.asList(files));
        zipFiles.sort(Comparator.comparingLong(File::lastModified));

        // 1. Check max total size limit
        if (maxTotalSizeMb > 0) {
            long maxTotalSizeBytes = maxTotalSizeMb * 1024 * 1024;
            long currentTotalSize = calculateTotalSize(zipFiles);

            while (currentTotalSize > maxTotalSizeBytes && !zipFiles.isEmpty()) {
                File oldest = zipFiles.remove(0);
                long fileSize = oldest.length();
                if (oldest.delete()) {
                    currentTotalSize -= fileSize;
                    plugin.getLogger().info("Deleted oldest backup (size limit exceeded): " + oldest.getName());
                    notifyAdmins("§6[PaperBackup] Deleted oldest backup due to size limit: §e" + oldest.getName());
                } else {
                    plugin.getLogger().warning("Failed to delete backup file: " + oldest.getName());
                    break;
                }
            }
        }

        // 2. Check max backup count limit
        if (maxBackups > 0) {
            while (zipFiles.size() > maxBackups) {
                File oldest = zipFiles.remove(0);
                if (oldest.delete()) {
                    plugin.getLogger().info("Deleted oldest backup (count limit exceeded): " + oldest.getName());
                    notifyAdmins("§6[PaperBackup] Deleted oldest backup due to count limit: §e" + oldest.getName());
                } else {
                    plugin.getLogger().warning("Failed to delete backup file: " + oldest.getName());
                    break;
                }
            }
        }
    }

    private long calculateTotalSize(List<File> files) {
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        return total;
    }

    private void notifyAdmins(String message) {
        // Send console message immediately
        Bukkit.getConsoleSender().sendMessage(message);
        
        // Notify online admin players on the main thread to ensure API safety
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getOnlinePlayers().stream()
                    .filter(player -> player.hasPermission("backup.admin"))
                    .forEach(player -> player.sendMessage(message));
        });
    }
}
