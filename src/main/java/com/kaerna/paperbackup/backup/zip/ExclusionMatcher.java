package com.kaerna.paperbackup.backup.zip;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class ExclusionMatcher {

    private final File serverRoot;
    private final File backupDir;
    private final List<String> excludePaths;
    private final Logger logger;

    public ExclusionMatcher(File serverRoot, File backupDir, List<String> rawExcludes, Logger logger) {
        this.serverRoot = canonicalOrAbsolute(serverRoot);
        this.backupDir = canonicalOrAbsolute(backupDir);
        this.excludePaths = normalizeExcludes(rawExcludes);
        this.logger = logger;
    }

    public boolean isExcluded(File file) {
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
            if (logger != null) {
                logger.warning("Could not check exclusions for " + file.getPath() + ": " + exception.getMessage());
            }
            return true;
        }
        return false;
    }

    public static List<String> normalizeExcludes(List<String> rawExcludes) {
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

    private static File canonicalOrAbsolute(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException exception) {
            return file.getAbsoluteFile();
        }
    }
}
