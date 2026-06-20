package com.kaerna.paperbackup.backup;

public class BackupResult {

    private final String fileName;
    private final long fileSizeBytes;
    private final String driveFileId;
    private final String driveWebViewLink;

    private BackupResult(String fileName, long fileSizeBytes, String driveFileId, String driveWebViewLink) {
        this.fileName = fileName;
        this.fileSizeBytes = fileSizeBytes;
        this.driveFileId = driveFileId;
        this.driveWebViewLink = driveWebViewLink;
    }

    public static BackupResult local(String fileName, long fileSizeBytes) {
        return new BackupResult(fileName, fileSizeBytes, null, null);
    }

    public static BackupResult googleDrive(String fileName, String driveFileId, String driveWebViewLink) {
        return new BackupResult(fileName, -1L, driveFileId, driveWebViewLink);
    }

    public String getFileName() { return fileName; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public String getDriveFileId() { return driveFileId; }
    public String getDriveWebViewLink() { return driveWebViewLink; }
    public boolean isGoogleDrive() { return driveFileId != null; }
}
