package dev.dsatracker.planning;

import dev.dsatracker.catalog.Difficulty;
import dev.dsatracker.catalog.ProgressStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Query;

interface GoalRepository extends JpaRepository<Goal, Long> {
    String MATCHES_GOAL = """
            (g.topic is null or g.topic member of p.topics)
            and (g.difficulty is null or p.difficulty = g.difficulty)
            """;
    String SOLVED = "p.progress.status = dev.dsatracker.catalog.ProgressStatus.SOLVED";
    String IN_PERIOD = "p.progress.solvedDate between g.startDate and g.endDate";
    String COUNTS = SOLVED + " and " + IN_PERIOD;
    String OUTSIDE = SOLVED + " and (p.progress.solvedDate < g.startDate or p.progress.solvedDate > g.endDate)";
    String SOLVED_COUNT = "(select count(distinct p.id) from Problem p where " + COUNTS + " and " + MATCHES_GOAL + ")";
    String MATCHING_PROBLEMS = " from Problem p, Goal g where g.id = :id and " + MATCHES_GOAL;
    String FILTERED_PROBLEMS = MATCHING_PROBLEMS + """
            and (:filter = 'ALL'
              or (:filter = 'COUNTED' and
            """ + COUNTS + ") or (:filter = 'OUTSIDE' and " + OUTSIDE + """
            ) or (:filter = 'ATTEMPTED' and p.progress.status = dev.dsatracker.catalog.ProgressStatus.ATTEMPTED)
              or (:filter = 'NOT_STARTED' and p.progress.status = dev.dsatracker.catalog.ProgressStatus.NOT_STARTED))
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

    interface ProblemRow {
        long getId();
        long getLeetcodeId();
        String getTitle();
        Difficulty getDifficulty();
        ProgressStatus getStatus();
        LocalDate getAttemptedDate();
        LocalDate getSolvedDate();
        boolean getCountsTowardGoal();
        boolean getSolvedOutsideRange();
    }

    interface ProblemTopic {
        long getProblemId();
        long getId();
        String getName();
    }

    @Query(value = """
            select p.id as id, p.leetcodeId as leetcodeId, p.title as title, p.difficulty as difficulty,
              p.progress.status as status, p.progress.attemptedDate as attemptedDate,
              p.progress.solvedDate as solvedDate,
              case when
            """ + COUNTS + " then true else false end as countsTowardGoal, case when "
            + OUTSIDE + " then true else false end as solvedOutsideRange " + FILTERED_PROBLEMS + """
            order by case when :sort = 'DIFFICULTY' then case p.difficulty
                when dev.dsatracker.catalog.Difficulty.EASY then 0
                when dev.dsatracker.catalog.Difficulty.MEDIUM then 1 else 2 end else 0 end,
              case when :sort = 'STATUS' then case p.progress.status
                when dev.dsatracker.catalog.ProgressStatus.NOT_STARTED then 0
                when dev.dsatracker.catalog.ProgressStatus.ATTEMPTED then 1 else 2 end else 0 end,
              p.leetcodeId
            """, countQuery = "select count(p.id) " + FILTERED_PROBLEMS)
    Page<ProblemRow> goalProblems(long id, String filter, String sort, Pageable page);

    @Query("select count(p.id) " + MATCHING_PROBLEMS)
    long countMatchingProblems(long id);

    @Query("""
            select p.id as problemId, t.id as id, t.name as name from Problem p join p.topics t
            where p.id in :ids order by t.nameKey, t.id
            """)
    List<ProblemTopic> problemTopics(List<Long> ids);
}
