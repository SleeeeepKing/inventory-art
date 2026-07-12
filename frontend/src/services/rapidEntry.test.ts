import { describe, expect, it } from 'vitest'
import { advanceAmountEntry, submittedAmounts } from './rapidEntry'

describe('rapid transaction entry', () => {
  it('drops only trailing empty rows before submission', () => {
    expect(submittedAmounts([12.5, 8, null, null])).toEqual([12.5, 8])
    expect(submittedAmounts([12.5, null, 8])).toEqual([12.5, null, 8])
    expect(submittedAmounts([12.5, 0])).toEqual([12.5, 0])
  })

  it('adds and focuses the next row after Enter on a valid final amount', () => {
    expect(advanceAmountEntry([12.5], 0)).toEqual({ values: [12.5, null], focusIndex: 1 })
  })

  it('moves focus without adding a row when a next row already exists', () => {
    const values = [12.5, 8]
    expect(advanceAmountEntry(values, 0)).toEqual({ values, focusIndex: 1 })
  })

  it('does not advance invalid rows or exceed the batch limit', () => {
    expect(advanceAmountEntry([null], 0)).toEqual({ values: [null], focusIndex: 0 })
    expect(advanceAmountEntry([1, 2], 1, 2)).toEqual({ values: [1, 2], focusIndex: 1 })
  })
})
