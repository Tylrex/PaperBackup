# PaperBackup

PaperBackup is a lightweight Paper/Purpur plugin for creating ZIP backups of a
Minecraft server. It targets Paper/Purpur 1.21.1 and Java 21.

Backups run asynchronously, so the archive process is kept away from the main
server thread. The plugin can run backups on a wall-clock schedule, keep only a
configured number of archives, and delete old archives when the backup folder
grows beyond a configured size.

## Features

- Creates full server backups as `.zip` archives.
- Runs backup work asynchronously.
- Automatically excludes the backup folder to avoid recursive backups.
- Supports configurable excluded files and folders.
- Supports scheduled automatic backups.
- Persists the next scheduled backup time in `plugins/PaperBackup/state.yml`, so
  server restarts do not reset the interval.
- Supports manual backups through an admin command.
- Prunes old backups by maximum count and maximum total folder size.
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
4. Edit `plugins/PaperBackup/config.yml` if needed.
5. Run `/backup reload` after changing the config.

## Commands

All commands require the `backup.admin` permission. Operators receive this
permission by default.

| Command | Description |
| --- | --- |
| `/backup` | Show command help. |
| `/backup run` | Start a manual backup. |
| `/backup status` | Show the current schedule, retention settings, and running state. |
| `/backup reload` | Reload `config.yml` and reschedule automatic backups. |

## Configuration

The plugin creates `plugins/PaperBackup/config.yml` on first startup.

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

- The configured backup folder is always excluded automatically, even if it is
  not listed in `exclude-paths`.
- Automatic cleanup only targets files named `backup-*.zip` in the configured
  backup folder. Other `.zip` files are ignored.
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
