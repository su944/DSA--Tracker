package dev.dsatracker.planning;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface GoalRepository extends JpaRepository<Goal, Long> {
    String WITH_PROGRESS = """
            select g as goal,
                   (select count(distinct p.id) from Problem p
                    where p.progress.status = dev.dsatracker.catalog.ProgressStatus.SOLVED
                      and p.progress.solvedDate between g.startDate and g.endDate
                      and (g.topic is null or g.topic member of p.topics)) as solvedCount
            from Goal g left join fetch g.topic
            """;

    interface Tally {
        Goal getGoal();
        long getSolvedCount();
    }

    @Query(WITH_PROGRESS + " order by g.startDate desc, g.id desc")
    List<Tally> listWithProgress();

    @Query(WITH_PROGRESS + " where :date between g.startDate and g.endDate order by g.endDate, g.id")
    List<Tally> activeWithProgress(LocalDate date);

    @Query(WITH_PROGRESS + " where g.id = :id")
    Optional<Tally> findWithProgress(long id);
}
