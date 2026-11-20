package dev.dsatracker.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface TopicRepository extends JpaRepository<Topic, Long> {
    List<Topic> findAllByOrderByNameKeyAsc();
    boolean existsByNameKey(String nameKey);
    boolean existsByNameKeyAndIdNot(String nameKey, long id);
}
