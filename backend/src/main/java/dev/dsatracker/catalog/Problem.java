package dev.dsatracker.catalog;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "problems")
class Problem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "leetcode_id", nullable = false, unique = true)
    long leetcodeId;

    @Column(nullable = false)
    String title;

    @Column(nullable = false)
    String slug;

    @Column(nullable = false)
    String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    Difficulty difficulty;

    @Convert(converter = DateColumns.Timestamp.class)
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @Convert(converter = DateColumns.Timestamp.class)
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @ManyToMany
    @JoinTable(name = "problem_topics",
            joinColumns = @JoinColumn(name = "problem_id"),
            inverseJoinColumns = @JoinColumn(name = "topic_id"))
    @BatchSize(size = 32)
    Set<Topic> topics = new HashSet<>();

    @OneToOne(mappedBy = "problem", optional = false, cascade = {CascadeType.PERSIST, CascadeType.REMOVE})
    ProblemProgress progress;

    protected Problem() {}

    Problem(Instant now) {
        createdAt = now;
        progress = new ProblemProgress(this, now);
    }

    void edit(CatalogDtos.ProblemInput input, LeetCodeLink link, Collection<Topic> newTopics, Instant now) {
        leetcodeId = input.leetcodeId();
        title = input.title().strip();
        slug = link.slug();
        url = link.url();
        difficulty = input.difficulty();
        updatedAt = now;
        topics.clear();
        topics.addAll(newTopics);
    }
}
