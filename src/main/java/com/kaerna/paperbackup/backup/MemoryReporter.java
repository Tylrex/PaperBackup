package com.kaerna.paperbackup.backup;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Locale;
import java.util.logging.Logger;

public class MemoryReporter {

    private final Plugin plugin;
    private final Logger logger;
    private boolean cleanupAfterBackup;
    private int delayedLogSeconds;

    public MemoryReporter(Plugin plugin, Logger logger, boolean cleanupAfterBackup, int delayedLogSeconds) {
        this.plugin = plugin;
        this.logger = logger;
        this.cleanupAfterBackup = cleanupAfterBackup;
        this.delayedLogSeconds = delayedLogSeconds;
    }

    public void reload(boolean cleanupAfterBackup, int delayedLogSeconds) {
        this.cleanupAfterBackup = cleanupAfterBackup;
        this.delayedLogSeconds = delayedLogSeconds;
    }

    public void logMemory(String stage) {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long committedMb = runtime.totalMemory() / (1024L * 1024L);
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long nonHeapUsedMb = nonHeap.getUsed() / (1024L * 1024L);
        long nonHeapCommittedMb = nonHeap.getCommitted() / (1024L * 1024L);
        long directMb = getBufferPoolUsedMb("direct");
        long mappedMb = getBufferPoolUsedMb("mapped");
        logger.info(String.format(Locale.ROOT,
                "Memory %s: heap-used=%d MB, heap-committed=%d MB, heap-max=%d MB, nonheap-used=%d MB, nonheap-committed=%d MB, direct=%d MB, mapped=%d MB",
                stage, usedMb, committedMb, maxMb, nonHeapUsedMb, nonHeapCommittedMb, directMb, mappedMb));
    }

    public void cleanup() {
        if (!cleanupAfterBackup) {
            return;
        }
        logger.info("Requesting JVM garbage collection after backup because memory.cleanup-after-backup is enabled.");
        System.gc();
    }

    public void scheduleDelayedLog(String stage) {
        if (delayedLogSeconds <= 0) {
            return;
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            cleanup();
            logMemory(stage);
        }, delayedLogSeconds * 20L);
    }

    private long getBufferPoolUsedMb(String poolName) {
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (pool.getName().equalsIgnoreCase(poolName)) {
                return pool.getMemoryUsed() / (1024L * 1024L);
            }
        }
        return 0L;
    }
}
