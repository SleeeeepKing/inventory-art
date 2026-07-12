import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'

export function useFormatters() {
  const { locale } = useI18n()
  const auth = useAuthStore()
  const timezone = computed(() => auth.user?.tenant?.timezone || 'UTC')
  const defaultCurrency = computed(() => auth.user?.tenant?.defaultCurrency || 'EUR')

  function money(value: number | string | null | undefined, currency = defaultCurrency.value) {
    return new Intl.NumberFormat(locale.value, { style: 'currency', currency }).format(
      Number(value || 0),
    )
  }

  function number(value: number | string | null | undefined) {
    return new Intl.NumberFormat(locale.value).format(Number(value || 0))
  }

  function date(value: string | Date | null | undefined, options?: Intl.DateTimeFormatOptions) {
    if (!value) return '—'
    return new Intl.DateTimeFormat(locale.value, {
      timeZone: timezone.value,
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      ...options,
    }).format(new Date(value))
  }

  function dateTime(value: string | Date | null | undefined) {
    return date(value, { hour: '2-digit', minute: '2-digit' })
  }

  return { money, number, date, dateTime, timezone, defaultCurrency }
}
