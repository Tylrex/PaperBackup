package com.kaerna.paperbackup.backup;

@FunctionalInterface
public interface Notifier {
    void notifyAdmins(String message);
}
