import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'

const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))

vi.mock('@/services/api', () => ({
  api: { get: mocks.get, post: mocks.post, put: mocks.put, delete: mocks.delete },
  configureSessionHandlers: vi.fn(),
  refreshSession: vi.fn(),
  setAccessToken: vi.fn(),
  warmBackend: vi.fn(),
}))

import { i18n, setAppLocale } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import EventsView from './EventsView.vue'

describe('EventsView expenses', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAppLocale('en')
    mocks.get.mockImplementation((url: string) => {
      if (url === '/sales-events') {
        return Promise.resolve({
          data: [
            {
              id: 'event-1',
              name: 'Paris Expo',
              startDate: '2026-07-10',
              endDate: '2026-07-12',
              enabled: false,
            },
          ],
        })
      }
      if (url === '/sales-events/event-1/expenses') {
        return Promise.resolve({
          data: {
            items: [
              {
                id: 'expense-1',
                eventId: 'event-1',
                categoryId: 'category-1',
                categoryName: 'Transport',
                amount: 20,
                currency: 'EUR',
                expenseDate: '2026-07-11',
                note: 'Train',
                status: 'ACTIVE',
                version: 1,
              },
            ],
            page: 0,
            size: 100,
            totalElements: 1,
            totalPages: 1,
          },
        })
      }
      if (url === '/expense-categories') {
        return Promise.resolve({
          data: [
            { id: 'category-1', name: 'Transport', enabled: true, version: 1 },
            { id: 'category-2', name: 'Accommodation', enabled: false, version: 2 },
          ],
        })
      }
      return Promise.resolve({ data: [] })
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('opens an expense ledger and shows the tenant currency as read-only in the form', async () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.$patch({
      user: {
        id: 'user-1',
        email: 'user@example.test',
        displayName: 'User',
        role: 'USER',
        preferredLocale: 'en',
        tenant: {
          id: 'tenant-1',
          name: 'Tenant',
          defaultCurrency: 'EUR',
          timezone: 'Europe/Paris',
          locale: 'en',
        },
      },
    })
    const wrapper = mount(EventsView, {
      attachTo: document.body,
      global: {
        plugins: [pinia, i18n, ElementPlus],
        stubs: { PageHeader: true, EmptyState: true },
      },
    })
    await flushPromises()

    await wrapper.get('button[aria-label="Manage expenses"]').trigger('click')
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledWith('/expense-categories', {
      params: { includeDisabled: true },
    })
    expect(document.body.textContent).toContain('Transport')
    expect(document.body.textContent).toContain('Train')
    expect(document.body.textContent).toContain('20.00')

    const addExpense = [...document.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent?.trim() === 'Add expense',
    )
    addExpense?.click()
    await flushPromises()
    expect(
      [...document.querySelectorAll<HTMLInputElement>('input:disabled')].map(
        (input) => input.value,
      ),
    ).toContain('EUR')

    const manageCategories = [...document.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent?.trim() === 'Expense categories',
    )
    manageCategories?.click()
    await flushPromises()
    expect(document.body.textContent).toContain('Accommodation')
    expect(document.body.textContent).toContain('Disabled')
  })

  it('summarizes total, enabled, ongoing, and finished exhibitions separately', async () => {
    const date = (offset: number) => {
      const value = new Date()
      value.setUTCDate(value.getUTCDate() + offset)
      return value.toISOString().slice(0, 10)
    }
    mocks.get.mockImplementation((url: string) => {
      if (url === '/sales-events') {
        return Promise.resolve({
          data: [
            {
              id: 'past',
              name: 'Past Expo',
              startDate: date(-3),
              endDate: date(-1),
              enabled: false,
            },
            {
              id: 'ongoing',
              name: 'Current Expo',
              startDate: date(-1),
              endDate: date(1),
              enabled: true,
            },
            {
              id: 'future',
              name: 'Future Expo',
              startDate: date(2),
              endDate: date(3),
              enabled: true,
            },
          ],
        })
      }
      return Promise.resolve({ data: [] })
    })

    const wrapper = mount(EventsView, {
      global: {
        plugins: [createPinia(), i18n, ElementPlus],
        stubs: { PageHeader: true, EmptyState: true },
      },
    })
    await flushPromises()

    const summary = wrapper.findAll('.event-summary > div').map((item) => item.text())
    expect(summary).toEqual(['Total exhibitions3', 'Enabled2', 'In progress1', 'Finished1'])
  })
})
