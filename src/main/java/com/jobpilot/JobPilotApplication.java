package com.jobpilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class JobPilotApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(JobPilotApplication.class);
        app.addListeners(lockDataFolder());
        app.run(args);
    }

    /**
     * Before the database opens: make the data folder (database, resume, App Password, PDFs)
     * readable only by your own user account. Skipped on Windows, which has no POSIX permissions.
     */
    static ApplicationListener<ApplicationEnvironmentPreparedEvent> lockDataFolder() {
        return event -> {
            Path dir = Path.of(event.getEnvironment().getProperty("jobpilot.data-dir", "./data"));
            try {
                Files.createDirectories(dir);
                lock(dir);
            } catch (UnsupportedOperationException ignored) {
                // Windows: folders in your user profile are already private to you
            } catch (Exception e) {
                System.err.println("JobPilot: could not make " + dir + " private: " + e.getMessage());
            }
        };
    }

    /** Folders become rwx------ and files rw-------, so other accounts can't read them. */
    static void lock(Path dir) throws java.io.IOException {
        var dirPerms = PosixFilePermissions.fromString("rwx------");
        var filePerms = PosixFilePermissions.fromString("rw-------");
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.toList()) {
                Files.setPosixFilePermissions(p, Files.isDirectory(p) ? dirPerms : filePerms);
            }
        }
    }
}
