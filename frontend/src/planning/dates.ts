const format = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' })
const weekday = new Intl.DateTimeFormat(undefined, { weekday: 'long', timeZone: 'UTC' })

export function displayDate(value: string) {
  return format.format(new Date(`${value}T00:00:00Z`))
}

export function displayWeekday(value: string) {
  return weekday.format(new Date(`${value}T00:00:00Z`))
}

export function dateRange(start: string, end: string) {
  return start === end ? displayDate(start) : `${displayDate(start)} – ${displayDate(end)}`
}

export function shiftDate(value: string, days: number) {
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.getUTCFullYear() < 1 || date.getUTCFullYear() > 9999 ? '' : date.toISOString().slice(0, 10)
}
