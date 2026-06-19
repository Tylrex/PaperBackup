# PaperBackup

PaperBackup is a lightweight Paper/Purpur plugin for creating ZIP backups of a
Minecraft server and uploading them to Google Drive. It targets Paper/Purpur
1.21.1 and Java 21.

Backups run asynchronously, so the archive process is kept away from the main
server thread. When Google Drive storage is enabled, the ZIP is streamed directly
to Google Drive and no local `backup-*.zip` file is created on the server.

## Features

- Creates full server backups as `.zip` archives.
- Runs backup work asynchronously.
- Automatically excludes the backup folder to avoid recursive backups.
- Supports configurable excluded files and folders.
- Supports Google Drive uploads through OAuth for normal personal Drive accounts.
- Supports service accounts for Google Workspace Shared Drives.
- Streams backup ZIPs directly to Google Drive without creating local ZIP files.
- Limits Google Drive upload chunk size to reduce temporary memory use.
- Logs JVM memory before and after backups for easier hosting diagnostics.
- Supports scheduled automatic backups.
- Persists the next scheduled backup time in `plugins/PaperBackup/state.yml`, so
  server restarts do not reset the interval.
- Supports manual backups through an admin command.
- Prunes old Google Drive backups by maximum count and maximum total folder size.
- Keeps a configurable minimum number of `backup-*.zip` archives as a safety
  floor during cleanup.
- Can save loaded worlds before the ZIP task starts.

## Requirements

- Paper or Purpur 1.21.1
- Java 21

## Installation

1. Download the built `PaperBackup` jar.
2. Put it into the server `plugins` folder.
3. Restart the server.
4. Edit `plugins/PaperBackup/google-drive-config.yml`.
5. For a normal personal Google Drive account, use `google-drive.auth-mode: OAUTH`.
6. Fill `google-drive.oauth.client-id`, `client-secret`, `refresh-token`, and
   `google-drive.folder-id`.
7. Set `google-drive.enabled: true`.
8. Run `/backup reload` after changing the config.

## Commands

All commands require the `backup.admin` permission. Operators receive this
permission by default.

| Command | Description |
| --- | --- |
| `/backup` | Show command help. |
| `/backup run` | Start a manual backup. |
| `/backup status` | Show the current schedule, retention settings, and running state. |
| `/backup reload` | Reload `google-drive-config.yml` and reschedule automatic backups. |

## Configuration

The plugin creates `plugins/PaperBackup/google-drive-config.yml` on first startup.
If an older `plugins/PaperBackup/config.yml` exists, it is copied to
`google-drive-config.yml` automatically on first startup.

```yaml
# Folder where backups are saved. Relative paths are resolved from the server root.
backup-folder: "backups"

# Maximum number of backup archives to keep.
# Set to -1 to disable this limit.
max-backups: 10

# Safety floor for automatic cleanup.
# PaperBackup never deletes below this many backup-*.zip files.
minimum-backups-to-keep: 1

# Maximum total size of all backup archives in megabytes.
# Set to -1 to disable this limit.
max-total-size-mb: 10240

# Automatic backup interval in minutes.
# Set to 0 or -1 to disable scheduled backups.
backup-interval-minutes: 60

# Delay before a missed scheduled backup starts after server boot, in seconds.
startup-delay-seconds: 60

# If true, one missed scheduled backup runs after startup-delay-seconds.
# If false, missed backups are skipped and the next future interval is used.
catch-up-missed-backup-on-start: true

# Save all loaded worlds before the async ZIP task starts.
save-worlds-before-backup: true

memory:
  # JVM garbage collection is automatic. Enable only if the hosting panel shows
  # RAM staying high after backups and you want PaperBackup to request GC.
  request-gc-after-backup: false

# Google Drive storage.
# When enabled, PaperBackup streams the ZIP directly to Google Drive.
# No local backup-*.zip file is created on the server.
google-drive:
  enabled: true
  auth-mode: "OAUTH"
  service-account-file: "plugins/PaperBackup/google-service-account.json"
  oauth:
    client-id: "PUT_OAUTH_CLIENT_ID_HERE"
    client-secret: "PUT_OAUTH_CLIENT_SECRET_HERE"
    refresh-token: "PUT_OAUTH_REFRESH_TOKEN_HERE"
  folder-id: "PUT_GOOGLE_DRIVE_FOLDER_ID_HERE"
  upload-chunk-size-kb: 256
  pipe-buffer-size-kb: 256
  keep-client-between-backups: false
  max-backups: 10
  minimum-backups-to-keep: 1
  max-total-size-mb: 10240

# Files and folders excluded from backups.
# Paths are relative to the server root.
exclude-paths:
  - "backups"
  - "cache"
  - "logs"
  - "plugins/PaperBackup/backups"
  - ".git"
  - "crash-reports"
  - "webdoc"
  - "session.lock"
  - "world/session.lock"
  - "world_nether/session.lock"
  - "world_the_end/session.lock"
```

## Notes

- With `google-drive.enabled: true`, PaperBackup does not create local ZIP
  files. The ZIP is produced as a stream and uploaded directly to Google Drive.
- PaperBackup does not keep the full ZIP in RAM. Files are read in small chunks,
  the ZIP is streamed, and Google Drive upload chunks are controlled by
  `google-drive.upload-chunk-size-kb`.
- Older configs with `google-drive.upload-chunk-size-mb` still work, but
  `upload-chunk-size-kb: 256` gives the lowest memory upload mode.
- Java may keep allocated heap after the backup until garbage collection. Use
  the memory lines in console to check real JVM heap usage; enable
  `memory.request-gc-after-backup` only if your host needs that behavior.
- The Google Drive retention cleanup only touches files named `backup-*.zip` in
  the configured Google Drive folder.
- The configured backup folder is always excluded automatically, even if it is
  not listed in `exclude-paths`. This still matters if you keep an old local
  backup folder or temporarily disable Google Drive storage.
- The automatic schedule is based on the server machine clock, not on Minecraft
  ticks. The next planned backup time is saved to `plugins/PaperBackup/state.yml`.
  If the server is offline when a backup is due, the plugin runs one catch-up
  backup after startup when `catch-up-missed-backup-on-start` is enabled.
- When `save-worlds-before-backup` is enabled, PaperBackup calls `World#save()`
  for loaded worlds on the main thread before the async ZIP task starts.
- Files that are locked, deleted during the backup, or otherwise unreadable are
  skipped and logged instead of crashing the backup.
- If another backup is already running, `/backup run` will not start a second
  backup.

## Google Drive Setup For Personal Drive

1. Open [Google Cloud Console](https://console.cloud.google.com/).
2. Create or select a project.
3. Enable **Google Drive API** for the project.
4. Go to **APIs & Services** -> **Credentials**.
5. Create an OAuth client ID. For the simplest setup, use a Desktop app client.
6. Use OAuth mode in `google-drive-config.yml`.
7. Generate a refresh token for your own Google account with this scope:
   `https://www.googleapis.com/auth/drive`.
   A quick way is Google OAuth 2.0 Playground:
   - Open [OAuth 2.0 Playground](https://developers.google.com/oauthplayground/).
   - Open the settings gear.
   - Enable **Use your own OAuth credentials**.
   - Paste your OAuth client ID and client secret.
   - Select the Drive API scope `https://www.googleapis.com/auth/drive`.
   - Authorize APIs.
   - Exchange the authorization code for tokens.
   - Copy the refresh token.
8. Open Google Drive in your browser and create a backup folder.
9. Copy the folder ID from the folder URL:

```text
https://drive.google.com/drive/folders/FOLDER_ID_IS_HERE
```

10. Paste it into:

```yaml
google-drive:
  enabled: true
  auth-mode: "OAUTH"
  oauth:
    client-id: "YOUR_OAUTH_CLIENT_ID"
    client-secret: "YOUR_OAUTH_CLIENT_SECRET"
    refresh-token: "YOUR_OAUTH_REFRESH_TOKEN"
  folder-id: "FOLDER_ID_IS_HERE"
```

11. Run `/backup reload`, then `/backup run`.

## Service Account Mode

Service accounts do not have storage quota on normal personal Google Drive.
Use `SERVICE_ACCOUNT` only with Google Workspace Shared Drives or advanced
domain delegation. If you see `Service Accounts do not have storage quota`, use
`OAUTH` mode instead.

## Building

This project uses Maven:

```bash
mvn clean package
```

The compiled jar is created in `target/`.

Tests can be run with:

```bash
mvn test
```
