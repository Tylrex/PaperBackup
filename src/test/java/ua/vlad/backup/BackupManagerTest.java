package ua.vlad.backup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupManagerTest {

    @TempDir
    Path tempDir;

    private File serverRoot;
    private File backupDir;
    private List<String> excludePaths;
    private BackupManager backupManager;

    @BeforeEach
    void setUp() throws IOException {
        serverRoot = tempDir.toFile().getCanonicalFile();
        backupDir = new File(serverRoot, "backups").getCanonicalFile();
        
        excludePaths = Arrays.asList(
            "backups",
            "cache",
            "logs",
            "plugins/PaperBackup/backups",
            ".git",
            "session.lock",
            "world/session.lock",
            "world_nether/session.lock",
            "world_the_end/session.lock"
        );

        backupManager = new BackupManager(serverRoot, backupDir, excludePaths);
        
        // Create directories
        Files.createDirectories(tempDir.resolve("backups"));
        Files.createDirectories(tempDir.resolve("cache"));
        Files.createDirectories(tempDir.resolve("logs"));
        Files.createDirectories(tempDir.resolve("plugins/PaperBackup/backups"));
        Files.createDirectories(tempDir.resolve("world"));
    }

    @Test
    void testIsExcluded_ExcludedFolders() throws IOException {
        File cacheFile = new File(serverRoot, "cache/data.db");
        File logFile = new File(serverRoot, "logs/latest.log");
        File nestedBackupFile = new File(serverRoot, "plugins/PaperBackup/backups/backup.zip");
        File backupDirSelf = new File(serverRoot, "backups");
        File backupFile = new File(backupDirSelf, "backup-1.zip");

        // Ensure directories and files exist for canonical check
        Files.createFile(cacheFile.toPath());
        Files.createFile(logFile.toPath());
        Files.createFile(nestedBackupFile.toPath());
        Files.createFile(backupFile.toPath());

        assertTrue(backupManager.isExcluded(cacheFile), "Files in cache/ should be excluded");
        assertTrue(backupManager.isExcluded(logFile), "Files in logs/ should be excluded");
        assertTrue(backupManager.isExcluded(nestedBackupFile), "Files in plugins/PaperBackup/backups/ should be excluded");
        assertTrue(backupManager.isExcluded(backupDirSelf), "Backup directory itself should be excluded");
        assertTrue(backupManager.isExcluded(backupFile), "Backup files should be excluded");
    }

    @Test
    void testIsExcluded_IncludedFiles() throws IOException {
        File configFile = new File(serverRoot, "plugins/PaperBackup/google-drive-config.yml");
        File worldRegionFile = new File(serverRoot, "world/region/r.0.0.mca");
        File serverPropertiesFile = new File(serverRoot, "server.properties");

        // Create parents and files
        Files.createDirectories(configFile.getParentFile().toPath());
        Files.createDirectories(worldRegionFile.getParentFile().toPath());
        Files.createFile(configFile.toPath());
        Files.createFile(worldRegionFile.toPath());
        Files.createFile(serverPropertiesFile.toPath());

        assertFalse(backupManager.isExcluded(configFile), "google-drive-config.yml should NOT be excluded");
        assertFalse(backupManager.isExcluded(worldRegionFile), "World region files should NOT be excluded");
        assertFalse(backupManager.isExcluded(serverPropertiesFile), "server.properties should NOT be excluded");
    }

    @Test
    void testIsExcluded_SessionLock() throws IOException {
        File sessionLock = new File(serverRoot, "world/session.lock");
        Files.createFile(sessionLock.toPath());

        assertTrue(backupManager.isExcluded(sessionLock), "session.lock files should be excluded");
    }

    @Test
    void testIsExcluded_OutsideRoot() throws IOException {
        File outsideFile = new File(serverRoot.getParentFile(), "outside.txt");
        // We don't need to create it, getCanonicalPath will resolve it relative to parent.
        assertTrue(backupManager.isExcluded(outsideFile), "Files outside server root should be excluded");
    }
}
