package com.kaerna.paperbackup.retention;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetentionPolicyTest {

    @Test
    void minimumBackupsToKeep_storedCorrectly() {
        assertEquals(3, new RetentionPolicy(10, 1024, 3).minimumBackupsToKeep);
    }

    @Test
    void minimumBackupsToKeep_flooredAtOne_whenZero() {
        assertEquals(1, new RetentionPolicy(10, 1024, 0).minimumBackupsToKeep);
    }

    @Test
    void minimumBackupsToKeep_flooredAtOne_whenNegative() {
        assertEquals(1, new RetentionPolicy(10, 1024, -99).minimumBackupsToKeep);
    }

    @Test
    void otherFields_storedAsIs() {
        RetentionPolicy p = new RetentionPolicy(7, 2048, 2);
        assertEquals(7, p.maxBackups);
        assertEquals(2048, p.maxTotalSizeMb);
    }

    @Test
    void negativeMaxBackups_allowed() {
        assertEquals(-1, new RetentionPolicy(-1, -1, 1).maxBackups);
    }
}
