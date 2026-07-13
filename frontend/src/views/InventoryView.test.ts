import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('@/services/api', () => ({
  api: { get: mocks.get, post: vi.fn(), put: vi.fn() },
  configureSessionHandlers: vi.fn(),
  refreshSession: vi.fn(),
  setAccessToken: vi.fn(),
  warmBackend: vi.fn(),
}))

import { i18n, setAppLocale } from '@/i18n'
import InventoryView from './InventoryView.vue'

describe('InventoryView product identity', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAppLocale('en')
    mocks.get.mockImplementation((url: string) => {
      if (url === '/inventory/movements') {
        return Promise.resolve({
          data: {
            items: [
              {
                id: 'movement-1',
                productId: 'product-1',
                productName: 'Blue Horizon',
                productSku: 'ART-001',
                productImageUrl: 'https://images.example.test/blue-horizon.jpg',
                type: 'INITIAL',
                quantity: 2,
                stockBefore: 0,
                stockAfter: 2,
                createdAt: '2026-07-13T10:00:00Z',
              },
            ],
            page: 0,
            size: 20,
            totalElements: 1,
            totalPages: 1,
          },
        })
      }
      if (url === '/products') {
        return Promise.resolve({
          data: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
        })
      }
      return Promise.resolve({ data: [] })
    })
  })

  it('shows the movement product image, name, and SKU', async () => {
    const wrapper = mount(InventoryView, {
      global: {
        plugins: [createPinia(), i18n, ElementPlus],
        stubs: { PageHeader: true, EmptyState: true },
      },
    })
    await flushPromises()

    const product = wrapper.get('.inventory-product-cell')
    expect(product.get('img').attributes('src')).toBe(
      'https://images.example.test/blue-horizon.jpg',
    )
    expect(product.text()).toContain('Blue Horizon')
    expect(product.text()).toContain('ART-001')
  })
})
