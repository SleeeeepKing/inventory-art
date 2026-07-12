import { describe, expect, it } from 'vitest'
import {
  reportDateParam,
  reportHourlyAllowed,
  reportPresetRange,
  reportRangeDays,
  reportRangeMatches,
} from './reportFilters'

describe('report filters', () => {
  const now = new Date(2026, 6, 12, 15, 30)

  it('builds inclusive quick ranges ending today', () => {
    const range = reportPresetRange(30, now)
    expect(reportDateParam(range[0])).toBe('2026-06-13')
    expect(reportDateParam(range[1])).toBe('2026-07-12')
    expect(reportRangeDays(range)).toBe(30)
    expect(reportRangeMatches(range, 30, now)).toBe(true)
  })

  it('limits hourly aggregation to thirty-one inclusive days', () => {
    expect(reportHourlyAllowed(reportPresetRange(31, now))).toBe(true)
    expect(reportHourlyAllowed(reportPresetRange(90, now))).toBe(false)
    expect(reportHourlyAllowed(null)).toBe(false)
  })
})
