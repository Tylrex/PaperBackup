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
    private final Logger logger;

    public ZipBackupWriter(ExclusionMatcher exclusionMatcher, File serverRoot, Logger logger) {
        this.exclusionMatcher = exclusionMatcher;
        this.serverRoot = serverRoot;
        this.logger = logger;
    }

    public void write(OutputStream outputStream) throws IOException {
        try (ZipOutputStream zipOutput = new ZipOutputStream(outputStream)) {
            writeZip(serverRoot.toPath(), zipOutput);
        }
    }

    private void writeZip(Path root, ZipOutputStream zipOutput) throws IOException {
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
                    addFile(source, zipOutput);
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

    private void addFile(File source, ZipOutputStream zipOutput) {
        String relativePath = getRelativePath(source);
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
            String serverRootCanonical = serverRoot.getCanonicalPath();
            if (fileCanonical.equals(serverRootCanonical)) {
                return "";
            }
            return fileCanonical.substring(serverRootCanonical.length() + 1).replace('\\', '/');
        } catch (IOException exception) {
            return "";
        }
    }
}
