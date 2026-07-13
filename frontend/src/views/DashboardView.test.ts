import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'

const mocks = vi.hoisted(() => ({ get: vi.fn(), push: vi.fn() }))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('@/services/api', () => ({
  api: { get: mocks.get },
  configureSessionHandlers: vi.fn(),
  refreshSession: vi.fn(),
  setAccessToken: vi.fn(),
  warmBackend: vi.fn(),
}))

import { i18n, setAppLocale } from '@/i18n'
import DashboardView from './DashboardView.vue'

describe('DashboardView low stock', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAppLocale('en')
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn(() => 'blob:dashboard-product'),
    })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
    mocks.get.mockImplementation((url: string) => {
      if (url === '/reports/dashboard') {
        return Promise.resolve({
          data: {
            defaultCurrency: 'EUR',
            currencies: [],
            salesTrend: [],
          },
        })
      }
      if (url === '/orders') {
        return Promise.resolve({
          data: { items: [], page: 0, size: 5, totalElements: 0, totalPages: 0 },
        })
      }
      if (url === '/products') {
        return Promise.resolve({
          data: {
            items: [
              {
                id: 'product-1',
                sku: 'ART-001',
                name: 'Blue Print',
                salePrice: 10,
                currency: 'EUR',
                currentStock: 1,
                lowStockThreshold: 2,
                enabled: true,
                imageUrl: '/files/file-1/preview',
              },
            ],
            page: 0,
            size: 5,
            totalElements: 1,
            totalPages: 1,
          },
        })
      }
      if (url === '/files/file-1/preview') {
        return Promise.resolve({ data: new Blob(['preview'], { type: 'image/webp' }) })
      }
      return Promise.resolve({ data: [] })
    })
  })

  afterEach(() => vi.restoreAllMocks())

  it('shows low-stock product images through the authenticated preview endpoint', async () => {
    const wrapper = mount(DashboardView, {
      global: {
        plugins: [createPinia(), i18n, ElementPlus],
        stubs: { PageHeader: true, ChartCanvas: true, EmptyState: true },
      },
    })
    await flushPromises()

    const product = wrapper.get('.inventory-product-cell')
    expect(product.text()).toContain('Blue Print')
    expect(product.get('img').attributes('src')).toBe('blob:dashboard-product')
    expect(mocks.get).toHaveBeenCalledWith('/products', {
      params: { page: 0, size: 5, enabled: true, lowStock: true },
    })
    expect(mocks.get).toHaveBeenCalledWith(
      '/files/file-1/preview',
      expect.objectContaining({ responseType: 'blob', signal: expect.any(AbortSignal) }),
    )
  })
})
