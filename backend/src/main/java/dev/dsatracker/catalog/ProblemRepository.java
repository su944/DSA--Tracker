package dev.dsatracker.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

interface ProblemRepository extends JpaRepository<Problem, Long>, JpaSpecificationExecutor<Problem> {
    @Override
    @EntityGraph(attributePaths = "progress")
    Page<Problem> findAll(Specification<Problem> specification, Pageable pageable);

    boolean existsByLeetcodeId(long leetcodeId);
    boolean existsByLeetcodeIdAndIdNot(long leetcodeId, long id);

    @Query("select count(p) from Problem p join p.topics t where t.id = :topicId")
    long countByTopic(long topicId);
}
