import { describe, expect, it } from 'vitest'
import type { Product } from '@/types/api'
import { mergeProductSearch } from './productSelection'

const product = (id: string, enabled = true): Product => ({
  id,
  sku: `SKU-${id}`,
  name: `Product ${id}`,
  salePrice: 10,
  currency: 'EUR',
  currentStock: 3,
  enabled,
  imageUrl: `/files/${id}/preview`,
})

describe('mergeProductSearch', () => {
  it('retains selected image metadata and disabled historical products across remote searches', () => {
    const selected = product('selected', false)
    const result = mergeProductSearch(
      [selected, product('old')],
      [product('new'), product('selected')],
      ['selected'],
    )

    expect(result.map((item) => item.id)).toEqual(['selected', 'new'])
    expect(result[0]).toBe(selected)
    expect(result[0].imageUrl).toBe('/files/selected/preview')
    expect(result[0].enabled).toBe(false)
  })
})
