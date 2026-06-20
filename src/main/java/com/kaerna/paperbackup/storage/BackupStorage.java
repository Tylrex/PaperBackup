package com.kaerna.paperbackup.storage;

import com.kaerna.paperbackup.backup.BackupResult;
import com.kaerna.paperbackup.backup.ZipStreamWriter;

public interface BackupStorage {
    BackupResult save(String fileName, ZipStreamWriter writer) throws Exception;
}
