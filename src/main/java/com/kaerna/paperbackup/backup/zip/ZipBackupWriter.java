package com.kaerna.paperbackup.backup.zip;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipBackupWriter {

    private static final int COPY_BUFFER_SIZE = 8192;

    private final ExclusionMatcher exclusionMatcher;
    private final File serverRoot;
    private final String serverRootCanonical;
    private final Logger logger;

    public ZipBackupWriter(ExclusionMatcher exclusionMatcher, File serverRoot, Logger logger) {
        this.exclusionMatcher = exclusionMatcher;
        this.serverRoot = serverRoot;
        this.logger = logger;
        String canonical;
        try {
            canonical = serverRoot.getCanonicalPath();
        } catch (IOException e) {
            canonical = serverRoot.getAbsolutePath();
        }
        this.serverRootCanonical = canonical;
    }

    public void write(OutputStream outputStream) throws IOException {
        // Single buffer reused for every file in this backup run — avoids one 8 KB allocation per file.
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (ZipOutputStream zipOutput = new ZipOutputStream(outputStream)) {
            writeZip(serverRoot.toPath(), zipOutput, buffer);
        }
    }

    private void writeZip(Path root, ZipOutputStream zipOutput, byte[] buffer) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                File directory = dir.toFile();
                if (exclusionMatcher.isExcluded(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String relativePath = getRelativePath(directory);
                if (!relativePath.isEmpty()) {
                    zipOutput.putNextEntry(new ZipEntry(relativePath + "/"));
                    zipOutput.closeEntry();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                File source = file.toFile();
                if (!exclusionMatcher.isExcluded(source)) {
                    addFile(source, zipOutput, buffer);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                logger.warning("Skipping inaccessible path " + file + ": " + exception.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void addFile(File source, ZipOutputStream zipOutput, byte[] buffer) {
        String relativePath = getRelativePath(source);
        if (relativePath.isEmpty()) {
            return;
        }

        boolean entryOpen = false;
        try (FileInputStream input = new FileInputStream(source)) {
            zipOutput.putNextEntry(new ZipEntry(relativePath));
            entryOpen = true;

            int length;
            while ((length = input.read(buffer)) >= 0) {
                zipOutput.write(buffer, 0, length);
            }
        } catch (IOException exception) {
            logger.warning("Skipping file " + source.getPath() + ": " + exception.getMessage());
        } finally {
            if (entryOpen) {
                try {
                    zipOutput.closeEntry();
                } catch (IOException exception) {
                    logger.warning("Could not close zip entry for " + source.getPath() + ": " + exception.getMessage());
                }
            }
        }
    }

    private String getRelativePath(File file) {
        try {
            String fileCanonical = file.getCanonicalPath();
            if (fileCanonical.equals(serverRootCanonical)) {
                return "";
            }
            return fileCanonical.substring(serverRootCanonical.length() + 1).replace('\\', '/');
        } catch (IOException exception) {
            return "";
        }
    }
}
