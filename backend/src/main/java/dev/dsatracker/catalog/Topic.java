package dev.dsatracker.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "topics")
class Topic {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, unique = true)
    String name;

    @Column(name = "name_key", nullable = false, unique = true)
    String nameKey;

    @Convert(converter = DateColumns.Timestamp.class)
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    protected Topic() {}

    Topic(String name, Instant now) {
        rename(name);
        createdAt = now;
    }

    void rename(String name) {
        this.name = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
        this.nameKey = comparisonKey(this.name);
    }

    static String comparisonKey(String name) {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
