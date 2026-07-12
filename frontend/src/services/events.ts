import type { SalesEvent } from '@/types/api'

function dateValue(value: string) {
  return Date.parse(`${value}T00:00:00Z`)
}

export function nearestEnabledEventId(events: SalesEvent[], now = new Date()) {
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())
  const distance = (event: SalesEvent) => {
    const start = dateValue(event.startDate)
    const end = dateValue(event.endDate)
    if (today < start) return start - today
    if (today > end) return today - end
    return 0
  }

  return (
    events
      .filter((event) => event.enabled)
      .sort(
        (left, right) =>
          distance(left) - distance(right) || right.startDate.localeCompare(left.startDate),
      )[0]?.id ?? ''
  )
}
