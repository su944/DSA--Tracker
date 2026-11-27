package dev.dsatracker.catalog;

import java.util.List;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TopicRepository extends JpaRepository<Topic, Long> {
    List<Topic> findAllByOrderByNameKeyAsc();
    List<Topic> findAllByNameKeyContainingOrderByNameKeyAsc(String nameKey);

    @Query("select t.id from Topic t where t.nameKey in :nameKeys")
    List<Long> findIdsByNameKeys(List<String> nameKeys);

    List<Topic> findAllByNameKeyContainingOrderByNameKeyAsc(String nameKey, Pageable page);

    boolean existsByNameKey(String nameKey);
    Optional<Topic> findByNameKey(String nameKey);
    boolean existsByNameKeyAndIdNot(String nameKey, long id);

    @Query("select count(g) from Goal g where g.topic.id = :topicId")
    long countGoalsByTopic(long topicId);
}
