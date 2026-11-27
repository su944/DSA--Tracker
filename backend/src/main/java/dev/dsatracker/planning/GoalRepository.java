package dev.dsatracker.planning;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

interface GoalRepository extends JpaRepository<Goal, Long> {
    String SOLVED_COUNT = """
            (select count(distinct p.id) from Problem p
             where p.progress.status = dev.dsatracker.catalog.ProgressStatus.SOLVED
               and p.progress.solvedDate between g.startDate and g.endDate
               and (g.topic is null or g.topic member of p.topics)
               and (g.difficulty is null or p.difficulty = g.difficulty))
            """;
    String WITH_PROGRESS = "select g as goal, " + SOLVED_COUNT + " as solvedCount from Goal g left join fetch g.topic ";

    interface Tally {
        Goal getGoal();
        long getSolvedCount();
    }

    @Query(WITH_PROGRESS + " order by g.startDate desc, g.id desc")
    List<Tally> listWithProgress();

    @Query(WITH_PROGRESS + " where :date between g.startDate and g.endDate order by g.endDate, g.id")
    List<Tally> activeWithProgress(LocalDate date);

    @Query(WITH_PROGRESS + " order by g.startDate desc, g.id desc")
    List<Tally> listWithProgress(Pageable page);

    @Query(WITH_PROGRESS + " where :date between g.startDate and g.endDate order by g.endDate, g.id")
    List<Tally> activeWithProgress(LocalDate date, Pageable page);

    @Query(WITH_PROGRESS + """
            where (:active is null or :active between g.startDate and g.endDate)
              and (:type is null or g.type = :type)
              and (:topic is null or g.topic.id = :topic)
              and (:difficulty is null or g.difficulty = :difficulty)
              and (:start is null or g.startDate >= :start)
              and (:end is null or g.startDate <= :end)
              and (:complete is null or :complete = case when
            """ + SOLVED_COUNT + """
            >= g.targetCount then true else false end)
            order by g.startDate desc, g.id desc
            """)
    org.springframework.data.domain.Slice<Tally> query(LocalDate active, GoalDtos.GoalType type, Long topic,
            dev.dsatracker.catalog.Difficulty difficulty, Boolean complete, LocalDate start, LocalDate end, Pageable page);

    @Query(WITH_PROGRESS + " where g.id = :id")
    Optional<Tally> findWithProgress(long id);
}
