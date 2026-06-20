package com.kaerna.paperbackup.backup.zip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipBackupWriterEdgeCaseTest {

    @TempDir Path tempDir;

    private File serverRoot;
    private File backupDir;
    private static final Logger LOG = Logger.getLogger("test");

    @BeforeEach
    void setUp() throws IOException {
        serverRoot = tempDir.toFile().getCanonicalFile();
        backupDir = new File(serverRoot, "backups").getCanonicalFile();
    }

    private ZipBackupWriter writer(List<String> extraExcludes) {
        ExclusionMatcher matcher = new ExclusionMatcher(
                serverRoot, backupDir, extraExcludes, LOG);
        return new ZipBackupWriter(matcher, serverRoot, LOG);
    }

    // ── canonicalized serverRoot is cached ────────────────────────────────────

    @Test
    void getRelativePath_usesCanonicalRoot_notAbsolutePath() throws IOException {
        // Even if serverRoot has an unusual path string, canonical form is used
        Files.writeString(tempDir.resolve("hello.txt"), "world");

        Set<String> entries = zipEntryNames(writer(List.of()));
        assertTrue(entries.contains("hello.txt"), "file relative to canonical root should be present");
    }

    // ── deeply nested files ────────────────────────────────────────────────────

    @Test
    void deeplyNested_fileAndDirs_allPresent() throws IOException {
        Files.createDirectories(tempDir.resolve("a/b/c/d"));
        Files.writeString(tempDir.resolve("a/b/c/d/deep.txt"), "content");

        Set<String> entries = zipEntryNames(writer(List.of()));
        assertTrue(entries.contains("a/b/c/d/deep.txt"), "deeply nested file must appear");
        assertTrue(entries.contains("a/"), "top dir entry must appear");
        assertTrue(entries.contains("a/b/"), "mid dir entry must appear");
        assertTrue(entries.contains("a/b/c/d/"), "leaf dir entry must appear");
    }

    // ── multiple excludes applied ──────────────────────────────────────────────

    @Test
    void multipleExcludedPaths_allExcluded() throws IOException {
        Files.createDirectories(tempDir.resolve("logs"));
        Files.createDirectories(tempDir.resolve("cache"));
        Files.createDirectories(tempDir.resolve("world"));
        Files.writeString(tempDir.resolve("logs/latest.log"),    "log");
        Files.writeString(tempDir.resolve("cache/data.db"),      "db");
        Files.writeString(tempDir.resolve("world/level.dat"),    "level");
        Files.writeString(tempDir.resolve("server.properties"),  "config");

        Set<String> entries = zipEntryNames(writer(List.of("logs", "cache")));

        assertFalse(entries.stream().anyMatch(e -> e.startsWith("logs")),  "logs/ must be excluded");
        assertFalse(entries.stream().anyMatch(e -> e.startsWith("cache")), "cache/ must be excluded");
        assertTrue(entries.contains("world/level.dat"),                    "world files must be included");
        assertTrue(entries.contains("server.properties"),                  "server.properties must be included");
    }

    // ── empty server root ─────────────────────────────────────────────────────

    @Test
    void emptyServerRoot_producesValidZip_withNoEntries() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> writer(List.of()).write(out));

        // A zip with no entries still has valid header bytes
        assertTrue(out.size() > 0, "even an empty zip has header bytes");
        Set<String> entries = entryNames(out.toByteArray());
        assertTrue(entries.isEmpty(), "no entries expected for empty root");
    }

    // ── file content integrity across many files ───────────────────────────────

    @Test
    void multipleFiles_allContentsPreserved() throws IOException {
        int fileCount = 20;
        for (int i = 0; i < fileCount; i++) {
            Files.writeString(tempDir.resolve("file-" + i + ".txt"), "content-" + i);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer(List.of()).write(out);
        byte[] zipBytes = out.toByteArray();

        for (int i = 0; i < fileCount; i++) {
            byte[] content = readEntry(zipBytes, "file-" + i + ".txt");
            assertNotNull(content, "file-" + i + ".txt must be in zip");
            assertEquals("content-" + i, new String(content, StandardCharsets.UTF_8));
        }
    }

    // ── backupDir always excluded even with empty exclusion list ──────────────

    @Test
    void backupDir_alwaysExcluded_regardlessOfExclusionList() throws IOException {
        Files.createDirectories(backupDir.toPath());
        Files.writeString(backupDir.toPath().resolve("backup-old.zip"), "old backup data");
        Files.writeString(tempDir.resolve("server.properties"), "key=value");

        // Empty exclusion list — backupDir is still excluded via the ExclusionMatcher constructor
        Set<String> entries = zipEntryNames(writer(List.of()));

        assertFalse(entries.stream().anyMatch(e -> e.startsWith("backups")), "backupDir must be excluded");
        assertTrue(entries.contains("server.properties"));
    }

    // ── session.lock is excluded when in exclusion list ───────────────────────

    @Test
    void sessionLock_excluded_whenInExclusionList() throws IOException {
        Files.createDirectories(tempDir.resolve("world"));
        Files.writeString(tempDir.resolve("world/level.dat"), "level");
        Files.writeString(tempDir.resolve("world/session.lock"), "lock");

        Set<String> entries = zipEntryNames(writer(List.of("world/session.lock")));

        assertTrue(entries.contains("world/level.dat"),        "level.dat must be included");
        assertFalse(entries.contains("world/session.lock"),    "session.lock must be excluded");
    }

    // ── no duplicate zip entries ────────────────────────────────────────────────

    @Test
    void zipEntries_noDuplicates() throws IOException {
        Files.createDirectories(tempDir.resolve("world"));
        Files.writeString(tempDir.resolve("world/level.dat"), "level");
        Files.writeString(tempDir.resolve("server.properties"), "key=value");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer(List.of()).write(out);

        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName());
                zis.closeEntry();
            }
        }
        assertEquals(names.size(), new HashSet<>(names).size(), "no duplicate entries");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Set<String> zipEntryNames(ZipBackupWriter w) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        w.write(out);
        return entryNames(out.toByteArray());
    }

    private static Set<String> entryNames(byte[] zipBytes) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
                zis.closeEntry();
            }
        }
        return names;
    }

    private static byte[] readEntry(byte[] zipBytes, String name) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return zis.readAllBytes();
                }
                zis.closeEntry();
            }
        }
        return null;
    }
}
