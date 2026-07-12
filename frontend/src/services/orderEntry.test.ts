import { describe, expect, it } from 'vitest'
import { currentOrderDate, pageAfterBulkDelete } from './orderEntry'

describe('currentOrderDate', () => {
  it('uses the current instant for a newly opened order batch', () => {
    expect(currentOrderDate(new Date('2026-07-12T14:37:25.123Z'))).toBe('2026-07-12T14:37:25.123Z')
  })

  it('returns to the previous page only when the current page becomes empty', () => {
    expect(pageAfterBulkDelete(3, 2, 2)).toBe(2)
    expect(pageAfterBulkDelete(3, 5, 2)).toBe(3)
    expect(pageAfterBulkDelete(1, 2, 2)).toBe(1)
  })
})
