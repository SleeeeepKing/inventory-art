import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('@/services/api', () => ({
  api: { get: mocks.get },
  configureSessionHandlers: vi.fn(),
  refreshSession: vi.fn(),
  setAccessToken: vi.fn(),
}))

import { i18n, setAppLocale } from '@/i18n'
import ReportsView from './ReportsView.vue'

const dashboard = {
  timezone: 'Europe/Paris',
  defaultCurrency: 'EUR',
  granularity: 'DAY',
  currencies: [
    {
      currency: 'EUR',
      totalSales: 100,
      totalExpenses: 30,
      balance: 70,
      transactionCount: 4,
      averageTransactionValue: 25,
    },
  ],
  salesTrend: [],
  byEvent: [
    {
      eventId: 'event-1',
      label: 'Paris Expo',
      currency: 'EUR',
      totalSales: 100,
      totalExpenses: 30,
      balance: 70,
      transactions: 4,
      expenseCount: 2,
    },
  ],
  expensesByCategory: [
    {
      categoryId: 'category-1',
      label: 'Transport',
      currency: 'EUR',
      totalExpenses: 30,
      expenseCount: 2,
    },
  ],
}

const inventory = {
  timezone: 'Europe/Paris',
  summary: { units: 0, batches: 0 },
  byProduct: [],
  byEvent: [],
}

describe('ReportsView filters', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAppLocale('en')
    mocks.get.mockImplementation((url: string) =>
      Promise.resolve({ data: url.endsWith('inventory-sales') ? inventory : dashboard }),
    )
  })

  it('keeps quick ranges as drafts until apply and reset reloads the defaults', async () => {
    const wrapper = mount(ReportsView, {
      global: {
        plugins: [createPinia(), i18n, ElementPlus],
        stubs: { ChartCanvas: true },
      },
    })
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(2)

    await wrapper.findAll('.report-presets button')[2].trigger('click')
    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(wrapper.findAllComponents({ name: 'ElRadioButton' })[1].props('disabled')).toBe(true)

    await wrapper.findAll('.report-filter-actions button')[1].trigger('click')
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(4)
    expect(mocks.get.mock.calls.at(-1)?.[1].params).toMatchObject({
      granularity: 'DAY',
    })

    await wrapper.findAll('.report-filter-actions button')[0].trigger('click')
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(6)
    const resetParams = mocks.get.mock.calls.at(-1)?.[1].params
    expect(resetParams.granularity).toBe('DAY')
    expect(
      (new Date(resetParams.end).getTime() - new Date(resetParams.start).getTime()) / 86_400_000,
    ).toBe(29)
  })

  it('shows revenue, expenses, balance, event totals, and category totals', async () => {
    const wrapper = mount(ReportsView, {
      global: {
        plugins: [createPinia(), i18n, ElementPlus],
        stubs: { ChartCanvas: true },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Sales amount')
    expect(wrapper.text()).toContain('Expenses')
    expect(wrapper.text()).toContain('Exhibition balance')
    expect(wrapper.text()).toContain('Paris Expo')
    expect(wrapper.text()).toContain('Transport')
    expect(wrapper.text()).toContain('€70.00')
    const chart = wrapper.get('.report-chart')
    const events = wrapper.get('.event-report-panel')
    expect(chart.element.compareDocumentPosition(events.element)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    )
    expect(wrapper.find('.report-grid .event-report-panel').exists()).toBe(false)
  })

  it('loads top-product images through the authenticated preview endpoint', async () => {
    const createObjectUrl = vi.fn(() => 'blob:secure-report-preview')
    const revokeObjectUrl = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectUrl })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectUrl })
    mocks.get.mockImplementation((url: string) => {
      if (url === '/files/file-1/preview') return Promise.resolve({ data: new Blob(['preview']) })
      if (url.endsWith('inventory-sales')) {
        return Promise.resolve({
          data: {
            ...inventory,
            summary: { units: 3, batches: 1 },
            byProduct: [
              {
                productId: 'product-1',
                sku: 'ART-001',
                label: 'Blue Print',
                productImageUrl: '/files/file-1/preview',
                units: 3,
                batches: 1,
              },
            ],
          },
        })
      }
      return Promise.resolve({ data: dashboard })
    })

    const wrapper = mount(ReportsView, {
      global: {
        plugins: [createPinia(), i18n, ElementPlus],
        stubs: { ChartCanvas: true },
      },
    })
    await flushPromises()

    const product = wrapper.get('.inventory-product-cell')
    expect(product.text()).toContain('Blue Print')
    expect(product.text()).toContain('ART-001')
    expect(product.get('img').attributes('src')).toBe('blob:secure-report-preview')
    expect(mocks.get).toHaveBeenCalledWith(
      '/files/file-1/preview',
      expect.objectContaining({ responseType: 'blob', signal: expect.any(AbortSignal) }),
    )
    expect(createObjectUrl).toHaveBeenCalledOnce()
  })
})
