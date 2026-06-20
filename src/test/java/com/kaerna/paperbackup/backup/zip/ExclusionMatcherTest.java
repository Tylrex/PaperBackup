package com.kaerna.paperbackup.backup.zip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExclusionMatcherTest {

    @TempDir
    Path tempDir;

    private File serverRoot;
    private File backupDir;
    private ExclusionMatcher matcher;

    @BeforeEach
    void setUp() throws IOException {
        serverRoot = tempDir.toFile().getCanonicalFile();
        backupDir = new File(serverRoot, "backups").getCanonicalFile();

        matcher = new ExclusionMatcher(serverRoot, backupDir, Arrays.asList(
                "backups",
                "cache",
                "logs",
                "plugins/PaperBackup/backups",
                ".git",
                "session.lock",
                "world/session.lock",
                "world_nether/session.lock",
                "world_the_end/session.lock"
        ), null);

        Files.createDirectories(tempDir.resolve("backups"));
        Files.createDirectories(tempDir.resolve("cache"));
        Files.createDirectories(tempDir.resolve("logs"));
        Files.createDirectories(tempDir.resolve("plugins/PaperBackup/backups"));
        Files.createDirectories(tempDir.resolve("world"));
    }

    // ── isExcluded: excluded paths ─────────────────────────────────────────────

    @Test
    void filesInExcludedFolders_areExcluded() throws IOException {
        File cacheFile   = new File(serverRoot, "cache/data.db");
        File logFile     = new File(serverRoot, "logs/latest.log");
        File nestedFile  = new File(serverRoot, "plugins/PaperBackup/backups/backup.zip");
        File backupFile  = new File(backupDir, "backup-1.zip");

        Files.createFile(cacheFile.toPath());
        Files.createFile(logFile.toPath());
        Files.createFile(nestedFile.toPath());
        Files.createFile(backupFile.toPath());

        assertTrue(matcher.isExcluded(cacheFile),  "cache/data.db");
        assertTrue(matcher.isExcluded(logFile),     "logs/latest.log");
        assertTrue(matcher.isExcluded(nestedFile),  "plugins/PaperBackup/backups/backup.zip");
        assertTrue(matcher.isExcluded(backupDir),   "backups/ dir itself");
        assertTrue(matcher.isExcluded(backupFile),  "backups/backup-1.zip");
    }

    @Test
    void sessionLockFiles_areExcluded() throws IOException {
        File lock = new File(serverRoot, "world/session.lock");
        Files.createFile(lock.toPath());

        assertTrue(matcher.isExcluded(lock));
    }

    @Test
    void filesOutsideServerRoot_areExcluded() {
        File outside = new File(serverRoot.getParentFile(), "outside.txt");
        assertTrue(matcher.isExcluded(outside));
    }

    @Test
    void backupDirItself_isExcluded() {
        assertTrue(matcher.isExcluded(backupDir));
    }

    // ── isExcluded: included paths ─────────────────────────────────────────────

    @Test
    void includedFiles_areNotExcluded() throws IOException {
        File config      = new File(serverRoot, "plugins/PaperBackup/google-drive-config.yml");
        File region      = new File(serverRoot, "world/region/r.0.0.mca");
        File serverProps = new File(serverRoot, "server.properties");

        Files.createDirectories(config.getParentFile().toPath());
        Files.createDirectories(region.getParentFile().toPath());
        Files.createFile(config.toPath());
        Files.createFile(region.toPath());
        Files.createFile(serverProps.toPath());

        assertFalse(matcher.isExcluded(config),      "google-drive-config.yml");
        assertFalse(matcher.isExcluded(region),       "world region file");
        assertFalse(matcher.isExcluded(serverProps),  "server.properties");
    }

    @Test
    void serverRoot_isNotExcluded() {
        assertFalse(matcher.isExcluded(serverRoot), "server root itself must never be excluded");
    }

    @Test
    void partialPrefixMatch_isNotExcluded() throws IOException {
        // "cache" is excluded but "cache2" must not be
        Files.createDirectories(tempDir.resolve("cache2"));
        File f = new File(serverRoot, "cache2/data.db");
        Files.createFile(f.toPath());

        assertFalse(matcher.isExcluded(f), "cache2/ must not match the 'cache' exclusion");
    }

    // ── normalizeExcludes ──────────────────────────────────────────────────────

    @Nested
    class NormalizeExcludesTest {

        @Test
        void stripsLeadingDotSlash() {
            List<String> result = ExclusionMatcher.normalizeExcludes(List.of("./logs", "./cache"));
            assertTrue(result.contains("logs"));
            assertTrue(result.contains("cache"));
        }

        @Test
        void normalizesBackslashesToForwardSlashes() {
            List<String> result = ExclusionMatcher.normalizeExcludes(List.of("plugins\\PaperBackup\\backups"));
            assertEquals("plugins/PaperBackup/backups", result.get(0));
        }

        @Test
        void stripsTrailingSlashes() {
            List<String> result = ExclusionMatcher.normalizeExcludes(List.of("logs/", "cache/"));
            assertTrue(result.contains("logs"));
            assertTrue(result.contains("cache"));
        }

        @Test
        void trimsSurroundingWhitespace() {
            List<String> result = ExclusionMatcher.normalizeExcludes(List.of("  logs  ", " cache "));
            assertTrue(result.contains("logs"));
            assertTrue(result.contains("cache"));
        }

        @Test
        void dropsBlankAndNullEntries() {
            List<String> result = ExclusionMatcher.normalizeExcludes(Arrays.asList("logs", "", null, "  "));
            assertEquals(1, result.size());
            assertEquals("logs", result.get(0));
        }

        @Test
        void nullList_returnsEmpty() {
            assertTrue(ExclusionMatcher.normalizeExcludes(null).isEmpty());
        }

        @Test
        void emptyList_returnsEmpty() {
            assertTrue(ExclusionMatcher.normalizeExcludes(List.of()).isEmpty());
        }

        @Test
        void multipleDotSlashPrefixes_allStripped() {
            List<String> result = ExclusionMatcher.normalizeExcludes(List.of("././logs"));
            assertEquals("logs", result.get(0));
        }
    }
}
