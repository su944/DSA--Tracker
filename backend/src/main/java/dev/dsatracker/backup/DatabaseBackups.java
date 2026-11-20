package dev.dsatracker.backup;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.backup.BackupDtos.Backup;
import dev.dsatracker.backup.BackupDtos.Listing;
import dev.dsatracker.backup.BackupDtos.Pending;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

public final class DatabaseBackups implements AutoCloseable {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Pattern FILENAME = Pattern.compile("([0-9]{8}T[0-9]{9}Z)-([0-9a-f-]{36})\\.sqlite");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private final Path database;
    private final Path directory;
    private final Path pending;
    private final Clock clock;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private DatabaseBackups(Path database, Path directory, Clock clock, FileChannel lockChannel, FileLock lock) {
        this.database = database;
        this.directory = directory;
        this.pending = directory.resolve("pending-restore");
        this.clock = clock;
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    public static DatabaseBackups open(String databasePath, String backupRoot, Clock clock) throws IOException, SQLException {
        if (databasePath.isBlank() || backupRoot.isBlank()) throw new IllegalArgumentException("Database and backup paths are required.");
        Path requested = Path.of(databasePath).toAbsolutePath().normalize();
        if (requested.getParent() == null || Files.isDirectory(requested) || Files.isSymbolicLink(requested)) {
            throw new IllegalArgumentException("tracker.database-path must name a database file, not a directory or symlink.");
        }
        Files.createDirectories(requested.getParent());
        Path database = requested.getParent().toRealPath().resolve(requested.getFileName());
        Path root = canonical(Path.of(backupRoot).toAbsolutePath().normalize());
        for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("tracker.backup-root must be outside a Git checkout.");
            }
        }
        Path directory = root.resolve(databaseKey(database));
        var channel = FileChannel.open(sidecar(database, "-tracker-lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another DSA Tracker process is already using this database.");
            var store = new DatabaseBackups(database, directory, clock, channel, lock);
            store.applyPending();
            return store;
        } catch (IOException | SQLException | RuntimeException failure) {
            channel.close();
            if (failure instanceof OverlappingFileLockException) {
                throw new IOException("Another DSA Tracker process is already using this database.", failure);
            }
            throw failure;
        }
    }

    public Path database() {
        return database;
    }

    public synchronized Listing list() throws IOException {
        var staged = readPending();
        return new Listing(entries().stream().map(Entry::info).toList(), staged == null ? null : staged.id());
    }

    public synchronized Backup create() throws IOException, SQLException {
        return snapshot().info();
    }

    public synchronized Pending stage(UUID id, String confirmation) throws IOException, SQLException {
        if (!"RESTORE".equals(confirmation)) throw ApiException.invalid("confirmation", "Type RESTORE to queue this backup.");
        var staged = readPending();
        if (staged != null && !staged.id().equals(id)) {
            throw ApiException.conflict("id", "Cancel the pending restore before selecting another backup.");
        }
        var entry = find(id);
        String digest = checksum(entry.file());
        try {
            validate(entry.file());
        } catch (SQLException | FlywayException failure) {
            throw ApiException.invalid("id", "This backup failed SQLite or schema validation.");
        }
        if (!digest.equals(checksum(entry.file()))) {
            throw ApiException.conflict("id", "The backup changed during validation. Select and confirm it again.");
        }
        writePending(new RestoreMarker(id, digest));
        return new Pending(id, true);
    }

    public synchronized void cancel() throws IOException {
        Files.deleteIfExists(pending);
    }

    private void applyPending() throws IOException, SQLException {
        var staged = readPending();
        if (staged == null) return;
        var backup = find(staged.id());
        Path replacement = Files.createTempFile(database.getParent(), ".tracker-restore-", ".sqlite");
        try {
            Files.copy(backup.file(), replacement, StandardCopyOption.REPLACE_EXISTING);
            if (!staged.checksum().equals(checksum(replacement))) {
                throw new IOException("The confirmed backup changed after it was queued. The live database was not replaced.");
            }
            validate(replacement);
            force(replacement);
            if (Files.exists(database)) {
                // Save the final live state before replacing the file, including changes after staging.
                snapshot();
                clearJournal();
            } else if (Files.exists(sidecar(database, "-wal")) || Files.exists(sidecar(database, "-journal"))) {
                throw new IOException("SQLite sidecar files exist without a database. Follow recovery steps before restarting.");
            }
            Files.move(replacement, database, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(pending);
        } finally {
            Files.deleteIfExists(replacement);
        }
    }

    private Entry snapshot() throws IOException, SQLException {
        Files.createDirectories(directory);
        UUID id = UUID.randomUUID();
        Instant created = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Path target = directory.resolve(TIMESTAMP.format(created) + "-" + id + ".sqlite");
        Path temporary = Files.createTempFile(directory, ".backup-", ".sqlite");
        try {
            try (var connection = connect(database, false);
                 var statement = connection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, temporary.toString());
                statement.execute();
            }
            validate(temporary);
            force(temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return new Entry(new Backup(id, created, Files.size(target)), target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void clearJournal() throws IOException, SQLException {
        try (var connection = connect(database, false); var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!result.next() || result.getInt(1) != 0) {
                throw new IOException("SQLite has active connections. Close every database connection before restoring.");
            }
        }
        for (String suffix : List.of("-wal", "-journal")) {
            Path file = sidecar(database, suffix);
            if (Files.exists(file) && Files.size(file) != 0) {
                throw new IOException("SQLite journal is still active. Close every database connection before restoring.");
            }
            Files.deleteIfExists(file);
        }
        Files.deleteIfExists(sidecar(database, "-shm"));
    }

    private void writePending(RestoreMarker marker) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".restore-", ".tmp");
        try {
            Files.writeString(temporary, marker.id() + "\n" + marker.checksum(), StandardCharsets.UTF_8);
            force(temporary);
            Files.move(temporary, pending, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private RestoreMarker readPending() throws IOException {
        if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS) || Files.size(pending) != 101) {
            throw new IOException("Pending restore marker is invalid or from an older version. See the recovery instructions.");
        }
        try {
            String[] fields = Files.readString(pending).split("\n", -1);
            if (fields.length != 2 || !SHA256.matcher(fields[1]).matches()) {
                throw new IllegalArgumentException("Invalid restore checksum.");
            }
            return new RestoreMarker(UUID.fromString(fields[0]), fields[1]);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Pending restore marker is invalid.", malformed);
        }
    }

    private Entry find(UUID id) throws IOException {
        return entries().stream().filter(entry -> entry.info().id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.missing("Backup not found."));
    }

    private List<Entry> entries() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        List<Entry> entries = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path file : (Iterable<Path>) paths::iterator) {
                var name = FILENAME.matcher(file.getFileName().toString());
                if (!name.matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    var id = UUID.fromString(name.group(2));
                    var created = Instant.from(TIMESTAMP.parse(name.group(1)));
                    entries.add(new Entry(new Backup(id, created, Files.size(file)), file));
                } catch (java.time.DateTimeException | IllegalArgumentException ignored) {
                    // Ignore files not created by the application.
                }
            }
        }
        return entries.stream().sorted(Comparator.comparing((Entry entry) -> entry.info().createdAt()).reversed()
                .thenComparing(entry -> entry.info().id())).toList();
    }

    private static void validate(Path file) throws SQLException {
        try (var connection = connect(file, true); var statement = connection.createStatement()) {
            try (var integrity = statement.executeQuery("PRAGMA integrity_check")) {
                if (!integrity.next() || !"ok".equals(integrity.getString(1)) || integrity.next()) {
                    throw new SQLException("SQLite integrity check failed.");
                }
            }
            try (var foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (foreignKeys.next()) throw new SQLException("SQLite foreign key check failed.");
            }
            try (var tables = statement.executeQuery("""
                    SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN
                    ('flyway_schema_history','problems','problem_progress','topics','problem_topics','goals','plan_items','revision_tasks')
                    """)) {
                if (!tables.next() || tables.getInt(1) != 8) throw new SQLException("Required tracker tables are missing.");
            }
        }
        Flyway.configure().dataSource(dataSource(file, true)).ignoreMigrationPatterns("*:pending").load().validate();
    }

    private static Connection connect(Path file, boolean readOnly) throws SQLException {
        return dataSource(file, readOnly).getConnection();
    }

    private static SQLiteDataSource dataSource(Path file, boolean readOnly) {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        config.setReadOnly(readOnly);
        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + file);
        return dataSource;
    }

    private static Path canonical(Path path) throws IOException {
        if (Files.exists(path)) return path.toRealPath();
        return path.getParent() == null ? path : canonical(path.getParent()).resolve(path.getFileName());
    }

    private static String databaseKey(Path database) {
        return HexFormat.of().formatHex(sha256().digest(database.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String checksum(Path file) throws IOException {
        var digest = sha256();
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Path sidecar(Path database, String suffix) {
        return database.resolveSibling(database.getFileName() + suffix);
    }

    private static void force(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            lockChannel.close();
        }
    }

    private record Entry(Backup info, Path file) {}
    private record RestoreMarker(UUID id, String checksum) {}
}
