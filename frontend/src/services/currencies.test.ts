import { describe, expect, it } from 'vitest'
import { businessCurrencies } from './currencies'

describe('business currencies', () => {
  it('includes Chinese yuan in product and tenant currency choices', () => {
    expect(businessCurrencies).toContain('CNY')
  })
})
