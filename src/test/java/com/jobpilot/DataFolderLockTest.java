package com.jobpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataFolderLockTest {

    @Test
    void makesFolderAndFilesPrivate(@TempDir Path tmp) throws Exception {
        Path data = Files.createDirectories(tmp.resolve("data/applications/1"));
        Path db = Files.writeString(tmp.resolve("data/jobpilot.mv.db"), "x");
        Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-rw-r--"));

        JobPilotApplication.lock(tmp.resolve("data"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tmp.resolve("data")))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(data))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(db))).isEqualTo("rw-------");
    }
}
