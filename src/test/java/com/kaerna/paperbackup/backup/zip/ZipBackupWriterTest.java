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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipBackupWriterTest {

    @TempDir
    Path tempDir;

    private File serverRoot;
    private File backupDir;
    private ZipBackupWriter writer;
    private static final Logger LOG = Logger.getLogger("test");

    @BeforeEach
    void setUp() throws IOException {
        serverRoot = tempDir.toFile().getCanonicalFile();
        backupDir = new File(serverRoot, "backups").getCanonicalFile();

        ExclusionMatcher matcher = new ExclusionMatcher(
                serverRoot, backupDir, List.of("backups", "logs"), LOG);
        writer = new ZipBackupWriter(matcher, serverRoot, LOG);

        // Included files
        Files.createDirectories(tempDir.resolve("world/region"));
        Files.writeString(tempDir.resolve("server.properties"), "level-name=world");
        Files.writeString(tempDir.resolve("world/region/r.0.0.mca"), "chunk data");
        Files.writeString(tempDir.resolve("world/level.dat"), "level");

        // Excluded directories
        Files.createDirectories(tempDir.resolve("backups"));
        Files.writeString(tempDir.resolve("backups/backup-old.zip"), "old backup");
        Files.createDirectories(tempDir.resolve("logs"));
        Files.writeString(tempDir.resolve("logs/latest.log"), "log output");
    }

    @Test
    void includedFiles_presentInZip() throws IOException {
        Set<String> entries = zipEntryNames();

        assertTrue(entries.contains("server.properties"));
        assertTrue(entries.contains("world/region/r.0.0.mca"));
        assertTrue(entries.contains("world/level.dat"));
    }

    @Test
    void excludedPaths_absentFromZip() throws IOException {
        Set<String> entries = zipEntryNames();

        assertFalse(entries.stream().anyMatch(e -> e.startsWith("backups")), "backups/ must be excluded");
        assertFalse(entries.stream().anyMatch(e -> e.startsWith("logs")),    "logs/ must be excluded");
    }

    @Test
    void directoryEntries_presentInZip() throws IOException {
        Set<String> entries = zipEntryNames();

        assertTrue(entries.contains("world/"),         "world/ dir entry should exist");
        assertTrue(entries.contains("world/region/"),  "world/region/ dir entry should exist");
    }

    @Test
    void fileContents_preservedInZip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out);

        byte[] content = readEntry(out.toByteArray(), "server.properties");
        assertNotNull(content, "server.properties must be present");
        assertEquals("level-name=world", new String(content, StandardCharsets.UTF_8));
    }

    @Test
    void emptyBackupDir_stillProducesValidZip() throws IOException {
        // Use a writer with no exclusions and no files except the excluded dir
        ExclusionMatcher empty = new ExclusionMatcher(
                serverRoot, backupDir, List.of(), LOG);
        ZipBackupWriter emptyWriter = new ZipBackupWriter(empty, serverRoot, LOG);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> emptyWriter.write(out));
        assertTrue(out.size() > 0, "even a zip with dirs produces bytes");
    }

    @Test
    void nestedFile_correctRelativePath() throws IOException {
        Set<String> entries = zipEntryNames();
        // must use forward slashes even on Windows
        assertTrue(entries.contains("world/region/r.0.0.mca"),
                "nested file path must use forward slashes");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Set<String> zipEntryNames() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out);
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
