package dev.dsatracker.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    HikariDataSource dataSource(@Value("${tracker.database-path}") String databasePath) throws IOException {
        if (databasePath.isBlank()) {
            throw new IllegalArgumentException("tracker.database-path must name a database file");
        }

        Path database = Path.of(databasePath).toAbsolutePath().normalize();
        if (database.getParent() == null || Files.isDirectory(database)) {
            throw new IllegalArgumentException("tracker.database-path must name a database file");
        }
        Files.createDirectories(database.getParent());

        var sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setBusyTimeout(5_000);
        var sqlite = new SQLiteDataSource(sqliteConfig);
        sqlite.setUrl("jdbc:sqlite:" + database);

        var hikari = new HikariConfig();
        hikari.setDataSource(sqlite);
        hikari.setMaximumPoolSize(4);
        return new HikariDataSource(hikari);
    }
}
