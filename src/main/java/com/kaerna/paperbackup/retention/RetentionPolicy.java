package com.kaerna.paperbackup.retention;

public class RetentionPolicy {

    public final int maxBackups;
    public final long maxTotalSizeMb;
    public final int minimumBackupsToKeep;

    public RetentionPolicy(int maxBackups, long maxTotalSizeMb, int minimumBackupsToKeep) {
        this.maxBackups = maxBackups;
        this.maxTotalSizeMb = maxTotalSizeMb;
        this.minimumBackupsToKeep = Math.max(1, minimumBackupsToKeep);
    }
}
