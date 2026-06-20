package com.kaerna.paperbackup.backup;

import java.io.OutputStream;

@FunctionalInterface
public interface ZipStreamWriter {
    void write(OutputStream outputStream) throws Exception;
}
