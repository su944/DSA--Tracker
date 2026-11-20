package dev.dsatracker.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import dev.dsatracker.backup.DatabaseBackups;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Configuration(proxyBeanMethods = false)
class TrackerConfiguration {
    @Bean
    Clock trackerClock(@Value("${tracker.time-zone}") String timeZone) {
        return Clock.system(timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone));
    }

    @Bean(destroyMethod = "close")
    DatabaseBackups databaseBackups(@Value("${tracker.database-path}") String databasePath,
                                    @Value("${tracker.backup-root}") String backupRoot,
                                    Clock clock) throws IOException, SQLException {
        return DatabaseBackups.open(databasePath, backupRoot, clock);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(DatabaseBackups backups) {
        var sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setBusyTimeout(5_000);
        // Reserve the SQLite writer before a service reads data it will change.
        sqliteConfig.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        var sqlite = new SQLiteDataSource(sqliteConfig);
        sqlite.setUrl("jdbc:sqlite:" + backups.database());

        var hikari = new HikariConfig();
        hikari.setDataSource(sqlite);
        hikari.setMaximumPoolSize(4);
        return new HikariDataSource(hikari);
    }
}
