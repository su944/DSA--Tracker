package dev.dsatracker.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ProblemRepository extends JpaRepository<Problem, Long>, JpaSpecificationExecutor<Problem> {
    @Override
    @EntityGraph(attributePaths = "progress")
    Page<Problem> findAll(Specification<Problem> specification, Pageable pageable);

    boolean existsByLeetcodeId(long leetcodeId);

    @Query("select p.id from Problem p where p.leetcodeId = :leetcodeId")
    Optional<Long> findIdByLeetcodeId(long leetcodeId);

    @Query("select p.leetcodeId from Problem p")
    List<Long> leetcodeIds();

    @EntityGraph(attributePaths = {"progress", "topics"})
    @Query("select p from Problem p order by p.leetcodeId")
    List<Problem> forExport();
    boolean existsByLeetcodeIdAndIdNot(long leetcodeId, long id);

    @Query("select count(p) from Problem p join p.topics t where t.id = :topicId")
    long countByTopic(long topicId);
}
