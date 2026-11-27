package dev.dsatracker.planning;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlanItemRepository extends JpaRepository<PlanItem, Long> {
    @EntityGraph(attributePaths = {"problem", "problem.progress"})
    List<PlanItem> findByDateBetweenOrderByDateAscIdAsc(LocalDate start, LocalDate end);

    @EntityGraph(attributePaths = {"problem", "problem.progress"})
    @org.springframework.data.jpa.repository.Query("""
            select item from PlanItem item where item.date between :start and :end
              and (:difficulty is null or item.problem.difficulty = :difficulty)
              and (:state is null or :state = case
                   when item.skipped = true then 'SKIPPED'
                   when item.problem.progress.status = dev.dsatracker.catalog.ProgressStatus.SOLVED
                     and item.problem.progress.solvedDate = item.date then 'COMPLETED'
                   else 'PLANNED' end)
            order by item.date, item.id
            """)
    org.springframework.data.domain.Slice<PlanItem> query(LocalDate start, LocalDate end, String state,
            dev.dsatracker.catalog.Difficulty difficulty, org.springframework.data.domain.Pageable page);

    boolean existsByProblemId(long problemId);

    boolean existsByProblemIdAndDate(long problemId, LocalDate date);
}
