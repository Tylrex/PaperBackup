package com.kaerna.paperbackup.retention;

import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.FileList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleDriveRetentionServiceTest {

    @Mock Drive drive;
    @Mock Drive.Files files;
    @Mock Drive.Files.List listRequest;
    @Mock Drive.Files.Delete deleteRequest;

    private static final Logger LOG = Logger.getLogger("test");

    @BeforeEach
    void setUp() throws IOException {
        when(drive.files()).thenReturn(files);
        when(files.list()).thenReturn(listRequest);
        when(listRequest.setQ(anyString())).thenReturn(listRequest);
        when(listRequest.setSpaces(anyString())).thenReturn(listRequest);
        when(listRequest.setFields(anyString())).thenReturn(listRequest);
        when(listRequest.setPageToken(any())).thenReturn(listRequest);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private com.google.api.services.drive.model.File driveFile(
            String id, String name, long sizeBytes, Long createdMillis) {
        com.google.api.services.drive.model.File f = new com.google.api.services.drive.model.File();
        f.setId(id);
        f.setName(name);
        f.setSize(sizeBytes);
        if (createdMillis != null) {
            f.setCreatedTime(new DateTime(createdMillis));
        }
        return f;
    }

    private void stubList(com.google.api.services.drive.model.File... driveFiles) throws IOException {
        FileList response = new FileList().setFiles(List.of(driveFiles)).setNextPageToken(null);
        when(listRequest.execute()).thenReturn(response);
    }

    private void stubEmptyList() throws IOException {
        when(listRequest.execute()).thenReturn(
                new FileList().setFiles(Collections.emptyList()).setNextPageToken(null));
    }

    private GoogleDriveRetentionService service(int maxBackups, long maxSizeMb, int minKeep) {
        return new GoogleDriveRetentionService(
                null, new RetentionPolicy(maxBackups, maxSizeMb, minKeep), LOG);
    }

    // ── empty list ─────────────────────────────────────────────────────────────

    @Test
    void prune_emptyFileList_noDeletes() throws IOException {
        stubEmptyList();

        service(5, 100, 1).prune(drive);

        verify(files, never()).delete(any());
    }

    // ── count-based pruning ────────────────────────────────────────────────────

    @Test
    void prune_byCount_deletesOldestFirst() throws IOException {
        var oldest = driveFile("id-1", "backup-2024-01-01_00-00-01.zip", 100, 1_000L);
        var middle = driveFile("id-2", "backup-2024-01-01_00-00-02.zip", 100, 2_000L);
        var newest = driveFile("id-3", "backup-2024-01-01_00-00-03.zip", 100, 3_000L);
        stubList(newest, oldest, middle); // intentionally unsorted

        when(files.delete("id-1")).thenReturn(deleteRequest);

        service(2, -1, 1).prune(drive);

        verify(files, times(1)).delete("id-1");
        verify(files, never()).delete("id-2");
        verify(files, never()).delete("id-3");
    }

    @Test
    void prune_byCount_deletesMultiple_whenNecessary() throws IOException {
        // Only stub the ids that will actually be deleted (id-1, id-2, id-3)
        when(files.delete("id-1")).thenReturn(deleteRequest);
        when(files.delete("id-2")).thenReturn(deleteRequest);
        when(files.delete("id-3")).thenReturn(deleteRequest);

        stubList(
                driveFile("id-1", "backup-2024-01-01_00-00-01.zip", 100, 1_000L),
                driveFile("id-2", "backup-2024-01-01_00-00-02.zip", 100, 2_000L),
                driveFile("id-3", "backup-2024-01-01_00-00-03.zip", 100, 3_000L),
                driveFile("id-4", "backup-2024-01-01_00-00-04.zip", 100, 4_000L),
                driveFile("id-5", "backup-2024-01-01_00-00-05.zip", 100, 5_000L)
        );

        service(2, -1, 1).prune(drive);

        verify(files).delete("id-1");
        verify(files).delete("id-2");
        verify(files).delete("id-3");
        verify(files, never()).delete("id-4");
        verify(files, never()).delete("id-5");
    }

    @Test
    void prune_byCount_respectsMinimumFloor() throws IOException {
        stubList(
                driveFile("id-1", "backup-2024-01-01_00-00-01.zip", 100, 1_000L),
                driveFile("id-2", "backup-2024-01-01_00-00-02.zip", 100, 2_000L)
        );

        service(1, -1, 2).prune(drive); // max 1 but min 2 → nothing deleted

        verify(files, never()).delete(any());
    }

    @Test
    void prune_byCount_disabled_whenNegative() throws IOException {
        stubList(
                driveFile("id-1", "backup-2024-01-01_00-00-01.zip", 100, 1_000L),
                driveFile("id-2", "backup-2024-01-01_00-00-02.zip", 100, 2_000L)
        );

        service(-1, -1, 1).prune(drive);

        verify(files, never()).delete(any());
    }

    // ── size-based pruning ────────────────────────────────────────────────────

    @Test
    void prune_bySize_deletesOldestFirst() throws IOException {
        long mb3 = 3L * 1024 * 1024;
        var oldest = driveFile("id-1", "backup-2024-01-01_00-00-01.zip", mb3, 1_000L);
        var newest = driveFile("id-2", "backup-2024-01-01_00-00-02.zip", mb3, 2_000L);
        stubList(oldest, newest);
        when(files.delete("id-1")).thenReturn(deleteRequest);

        service(-1, 1, 1).prune(drive); // max 1 MB, have 6 MB, min 1

        verify(files).delete("id-1");
        verify(files, never()).delete("id-2");
    }

    @Test
    void prune_bySize_doesNothing_whenUnderLimit() throws IOException {
        stubList(driveFile("id-1", "backup-2024-01-01_00-00-01.zip", 100, 1_000L));

        service(-1, 1000, 1).prune(drive);

        verify(files, never()).delete(any());
    }

    @Test
    void prune_bySize_respectsMinimumFloor() throws IOException {
        long mb3 = 3L * 1024 * 1024;
        stubList(
                driveFile("id-1", "backup-2024-01-01_00-00-01.zip", mb3, 1_000L),
                driveFile("id-2", "backup-2024-01-01_00-00-02.zip", mb3, 2_000L)
        );

        service(-1, 0, 2).prune(drive); // over limit but min 2 → nothing deleted

        verify(files, never()).delete(any());
    }

    // ── null timestamp fix ─────────────────────────────────────────────────────

    @Test
    void prune_nullTimestamp_treatedAsNewest_survivesOverOlder() throws IOException {
        long mb3 = 3L * 1024 * 1024;
        var withTime = driveFile("id-1", "backup-2024-01-01_00-00-01.zip", mb3, 1_000L);
        var nullTime = driveFile("id-2", "backup-2024-01-01_00-00-02.zip", mb3, null); // no createdTime
        stubList(withTime, nullTime);
        when(files.delete("id-1")).thenReturn(deleteRequest);

        // max 1 → one must go; null-timestamp file should be kept (treated as newest)
        service(1, -1, 1).prune(drive);

        verify(files).delete("id-1");
        verify(files, never()).delete("id-2");
    }

    @Test
    void prune_allNullTimestamps_minimumFloor_preventsAnyDeletion() throws IOException {
        stubList(
                driveFile("id-1", "backup-2024-01-01_00-00-01.zip", 100, null),
                driveFile("id-2", "backup-2024-01-01_00-00-02.zip", 100, null)
        );

        service(1, -1, 2).prune(drive); // max 1, min 2 → nothing deleted

        verify(files, never()).delete(any());
    }

    // ── name filtering ─────────────────────────────────────────────────────────

    @Test
    void prune_nonMatchingFileNames_areIgnored() throws IOException {
        // Two real backups + non-matching files; max 1 → oldest real backup deleted,
        // non-matching files never touched.
        var real1 = driveFile("id-1", "backup-2024-01-01_00-00-01.zip", 100, 1_000L);
        var real2 = driveFile("id-2", "backup-2024-01-01_00-00-02.zip", 100, 2_000L);
        var fakeA = driveFile("id-3", "config.zip",  100, 3_000L);
        var fakeB = driveFile("id-4", "worlds.zip",  100, 4_000L);
        FileList response = new FileList()
                .setFiles(List.of(real1, real2, fakeA, fakeB)).setNextPageToken(null);
        when(listRequest.execute()).thenReturn(response);
        when(files.delete("id-1")).thenReturn(deleteRequest);

        service(1, -1, 1).prune(drive); // max 1 real backup → delete oldest

        verify(files).delete("id-1");
        verify(files, never()).delete("id-2");
        verify(files, never()).delete("id-3");
        verify(files, never()).delete("id-4");
    }

    // ── folder ID in query ────────────────────────────────────────────────────

    @Test
    void prune_withFolderId_includesFolderIdInQuery() throws IOException {
        stubEmptyList();
        new GoogleDriveRetentionService(
                "myFolderIdABC", new RetentionPolicy(5, 100, 1), LOG).prune(drive);

        verify(listRequest).setQ(argThat(q -> q.contains("myFolderIdABC")));
    }

    @Test
    void prune_withNullFolderId_noParentsClauseInQuery() throws IOException {
        stubEmptyList();
        new GoogleDriveRetentionService(
                null, new RetentionPolicy(5, 100, 1), LOG).prune(drive);

        verify(listRequest).setQ(argThat(q -> !q.contains("parents")));
    }

    @Test
    void prune_withBlankFolderId_treatedAsNoFolder() throws IOException {
        stubEmptyList();
        new GoogleDriveRetentionService(
                "   ", new RetentionPolicy(5, 100, 1), LOG).prune(drive);

        verify(listRequest).setQ(argThat(q -> !q.contains("parents")));
    }
}
