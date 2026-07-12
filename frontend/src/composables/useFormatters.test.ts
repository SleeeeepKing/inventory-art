import { defineComponent, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { i18n, setAppLocale } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { useFormatters } from './useFormatters'

describe('localized business formatters', () => {
  afterEach(() => setAppLocale('en'))

  it('uses the user language with tenant currency and timezone', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.applySession({
      accessToken: 'test-token',
      user: {
        id: 'user-1',
        email: 'artist@example.test',
        displayName: 'Artiste',
        role: 'USER',
        preferredLocale: 'fr-FR',
        tenant: {
          id: 'tenant-1',
          name: 'Atelier',
          defaultCurrency: 'EUR',
          timezone: 'Europe/Paris',
          locale: 'fr-FR',
        },
      },
    })

    const Probe = defineComponent({
      setup() {
        const { money, number, dateTime } = useFormatters()
        return () =>
          h('div', {
            'data-money': money(1234.5),
            'data-number': number(1234.5),
            'data-date': dateTime('2026-07-12T12:00:00Z'),
          })
      },
    })
    const wrapper = mount(Probe, { global: { plugins: [pinia, i18n] } })

    expect(wrapper.attributes('data-money')).toBe(
      new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(1234.5),
    )
    expect(wrapper.attributes('data-number')).toBe(new Intl.NumberFormat('fr-FR').format(1234.5))
    expect(wrapper.attributes('data-date')).toBe(
      new Intl.DateTimeFormat('fr-FR', {
        timeZone: 'Europe/Paris',
        year: 'numeric',
        month: 'short',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      }).format(new Date('2026-07-12T12:00:00Z')),
    )
  })
})
