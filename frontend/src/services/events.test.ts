import { describe, expect, it } from 'vitest'
import { nearestEnabledEventId } from './events'
import type { SalesEvent } from '@/types/api'

function event(id: string, startDate: string, endDate: string, enabled = true): SalesEvent {
  return {
    id,
    name: id,
    startDate,
    endDate,
    enabled,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

describe('nearestEnabledEventId', () => {
  it('prefers an ongoing enabled exhibition', () => {
    const events = [
      event('past', '2026-06-01', '2026-06-03'),
      event('disabled', '2026-07-12', '2026-07-12', false),
      event('upcoming', '2026-07-14', '2026-07-16'),
      event('ongoing', '2026-07-10', '2026-07-12'),
    ]

    expect(nearestEnabledEventId(events, new Date('2026-07-12T12:00:00Z'))).toBe('ongoing')
  })

  it('uses the closest exhibition period when none is ongoing', () => {
    const events = [
      event('older', '2026-06-01', '2026-06-30'),
      event('next', '2026-07-15', '2026-07-17'),
    ]

    expect(nearestEnabledEventId(events, new Date('2026-07-12T12:00:00Z'))).toBe('next')
  })
})
