package com.kaerna.paperbackup.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StateServiceStaticTest {

    @Test
    void formatTime_epochZero_returnsNonBlankString() {
        String result = StateService.formatTime(0L);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void formatTime_knownTimestamp_containsExpectedDate() {
        // 2024-01-15 00:00:00 UTC = 1705276800000
        String result = StateService.formatTime(1705276800000L);
        assertTrue(result.contains("2024-01-15"),
                "Expected date 2024-01-15 in formatted string: " + result);
    }

    @Test
    void formatTime_containsDateAndTimeSeparators() {
        String result = StateService.formatTime(1705276800000L);
        assertTrue(result.contains("-"), "Date separator missing: " + result);
        assertTrue(result.contains(":"), "Time separator missing: " + result);
    }

    @Test
    void formatTime_twoDistinctMillis_produceDifferentStrings() {
        String t1 = StateService.formatTime(1_000_000L);
        String t2 = StateService.formatTime(2_000_000_000L);
        assertNotEquals(t1, t2);
    }

    @Test
    void formatTime_sameInput_alwaysProducesSameOutput() {
        long ts = 1_700_000_000_000L;
        assertEquals(StateService.formatTime(ts), StateService.formatTime(ts));
    }
}
