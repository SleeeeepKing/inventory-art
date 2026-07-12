export type ReportDateRange = [Date, Date]

export function reportPresetRange(days: number, now = new Date()): ReportDateRange {
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const start = new Date(end)
  start.setDate(start.getDate() - (days - 1))
  return [start, end]
}

export function reportDateParam(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function reportRangeDays(range: ReportDateRange) {
  const [start, end] = range.map((date) =>
    Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()),
  )
  return Math.floor((end - start) / 86_400_000) + 1
}

export function reportHourlyAllowed(range: ReportDateRange | null) {
  return Boolean(range && reportRangeDays(range) <= 31)
}

export function reportRangeMatches(range: ReportDateRange | null, days: number, now = new Date()) {
  if (!range) return false
  const preset = reportPresetRange(days, now)
  return (
    reportDateParam(range[0]) === reportDateParam(preset[0]) &&
    reportDateParam(range[1]) === reportDateParam(preset[1])
  )
}
