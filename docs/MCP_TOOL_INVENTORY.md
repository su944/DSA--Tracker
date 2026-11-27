# MCP tool inventory

The signatures use the JSON argument names published by `tools/list`. A question mark marks an optional argument. All groups require `TRACKER_MCP_ENABLED=true`. See the [integration guide](MCP_INTEGRATION.md) for payload schemas, settings, verification, and approval steps.

## Local reads in LEGACY and ALL

Catalog tools use ProblemService or TopicService. TrackerMcpIntegrationTest covers catalog filtering, progress mapping, and read-only behavior.

- `search_problems(query?, leetcodeId?, topicNames?, difficulty?, status?, page?, size?)` returns a page of problems and pagination totals.
- `get_problem(leetcodeId)` returns one problem with current progress.
- `list_topics(query?)` returns a bounded list of matching topics.

Analytics tools use ReportingService. TrackerMcpIntegrationTest covers counts, date boundaries, limits, and REST compatibility.

- `get_dashboard_summary()` returns global problem counts and solve percentage.
- `get_topic_progress(range?)` returns bounded topic counts and solve percentages.
- `get_difficulty_progress(range?)` returns solves by difficulty.
- `get_solving_activity(range?)` returns dated counts and the total in range.
- `get_recent_problems(limit?)` returns recently solved and modified problems.

Planner, goal, and revision reads use PlannerService, GoalService, and RevisionService. TrackerMcpIntegrationTest covers calendar groups, goal progress, and revision date boundaries.

- `get_daily_plan(date?)` returns a day with assignment rows and counts.
- `get_weekly_plan(date?)` returns a Monday–Sunday calendar and counts.
- `list_goals(activeOnly?)` returns bounded goals with progress.
- `get_goal_progress(goalId)` returns one goal and its current progress.
- `get_due_revisions(date?, limit?)` returns pending reviews through the cutoff date.
- `get_upcoming_revisions(fromDate?, days?, limit?)` returns reviews in the requested window.

## Reads in every profile

- `ping_tracker()` returns the MCP connectivity diagnostic. TrackerMcpIntegrationTest covers registration and invocation.
- `recommend_study_plan(topicNames?, difficulty?, startDate?, days?, problemsPerDay?, excludeSolved?, excludeScheduled?, candidateLimit?)` returns a bounded, temporary proposal via StudyPlanRecommendationService. StudyPlanRecommendationIntegrationTest covers ranking, limits, conflicts, and expiry.
- `search_leetcode_problems(query?, topicNames?, difficulty?, includePremium?, limit?, page?)` returns a bounded public metadata page and local membership.
- `get_leetcode_problem(leetcodeId)` returns one public metadata record.
- `preview_leetcode_import(count?, topicNames?, difficulty?, includePremium?)` returns a temporary import preview with candidates. The three LeetCode reads use LeetCodeCatalogService. LeetCodeDiscoveryIntegrationTest covers GraphQL mapping, paging, caching, failures, and read-only behavior through a local mock.

## Typed reads in UNIFIED and ALL

- `query_tracker(domain, query)` returns a bounded page of compact results from TrackerQueryService.
- `describe_tracker()` returns query schema, limits, and local topic choices. TrackerQueryIntegrationTest covers both tools, filters, responses, and profile discovery.

## Writes in every profile when explicitly enabled

All writes require `TRACKER_MCP_WRITES_ENABLED=true` and explicit user consent in the client. Planner writes additionally require authenticated local operator approval.

- `update_problem_progress(leetcodeId, status?, attemptedDate?, solvedDate?, comments?)` applies a partial patch via ProblemService and returns current progress. TrackerMcpWriteIntegrationTest covers omitted fields, invalid transitions, and atomic rollback.
- `schedule_problem(leetcodeId, date)` and `reschedule_problem(plannerEntryId, date)` return the created or updated assignment via PlannerService. Both require a one-use grant for the exact action. TrackerMcpWriteIntegrationTest covers duplicates, date rules, missing grants, and grant replay.
- `create_goal(name, type, startDate, endDate?, targetCount, topicName?, difficulty?)` creates a goal via GoalService and returns its details.
- `complete_revision(leetcodeId, reviewedDate?)` completes the earliest scheduled revision via RevisionService and returns completion details. TrackerMcpWriteIntegrationTest covers goal restrictions and revision completion.
- `import_leetcode_problems(previewId, leetcodeIds)` imports selected cached candidates via LeetCodeImportService and returns imported, skipped, and failed counts. LeetCodeDiscoveryIntegrationTest covers conflicts, replays, and preservation of existing progress.
- `commit_study_plan(proposalId)` commits the exact operator-approved proposal via StudyPlanExecutionService and returns status and created assignments. StudyPlanExecutionIntegrationTest covers expiry, approval, atomic rollback, concurrent calls, and retries.
