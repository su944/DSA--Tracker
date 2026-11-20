package dev.dsatracker.planning;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlanItemRepository extends JpaRepository<PlanItem, Long> {
    @EntityGraph(attributePaths = {"problem", "problem.progress"})
    List<PlanItem> findByDateBetweenOrderByDateAscIdAsc(LocalDate start, LocalDate end);

    boolean existsByProblemIdAndDate(long problemId, LocalDate date);
}
