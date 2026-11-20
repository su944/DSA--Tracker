package dev.dsatracker.backup;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BackupDtos {
    private BackupDtos() {}

    public record Backup(UUID id, Instant createdAt, long bytes) {}
    public record Listing(List<Backup> backups, UUID pendingRestoreId) {}
    public record Confirmation(@NotBlank String confirmation) {}
    public record Pending(UUID backupId, boolean restartRequired) {}
}
