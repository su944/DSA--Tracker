package dev.dsatracker.backup;

import dev.dsatracker.backup.BackupDtos.Backup;
import dev.dsatracker.backup.BackupDtos.Confirmation;
import dev.dsatracker.backup.BackupDtos.Listing;
import dev.dsatracker.backup.BackupDtos.Pending;
import jakarta.validation.Valid;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/backups", "/api/v1/backups"})
class BackupController {
    private static final Logger log = LoggerFactory.getLogger(BackupController.class);
    private final DatabaseBackups backups;

    BackupController(DatabaseBackups backups) {
        this.backups = backups;
    }

    @GetMapping
    Listing list() throws IOException {
        return backups.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Backup create() throws IOException, SQLException {
        return backups.create();
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Pending restore(@PathVariable UUID id, @Valid @RequestBody Confirmation input) throws IOException, SQLException {
        return backups.stage(id, input.confirmation());
    }

    @DeleteMapping("/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel() throws IOException {
        backups.cancel();
    }

    @ExceptionHandler({IOException.class, SQLException.class, FlywayException.class})
    ResponseEntity<Map<String, Object>> storageFailure(Exception failure) {
        log.warn("SQLite backup operation failed", failure);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "code", "SERVICE_UNAVAILABLE", "message", "The backup operation could not be completed.", "fields", Map.of()));
    }
}
