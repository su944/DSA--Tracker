import type { Goal } from './api'

export function GoalProgressMeter({ goal }: { goal: Goal }) {
  const { solvedCount, targetCount, complete } = goal.progress
  return (
    <>
      <p className="planning-progress-label">{solvedCount} / {targetCount} solved</p>
      <progress className="planning-goal-meter" aria-label={`${goal.name} progress`}
        value={Math.min(solvedCount, targetCount)} max={targetCount} />
      {complete && <p className="planning-complete">Target reached</p>}
    </>
  )
}
