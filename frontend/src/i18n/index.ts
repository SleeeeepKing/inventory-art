import { createI18n } from 'vue-i18n'
import { en } from './locales/en'
import { frFR } from './locales/fr-FR'
import { zhCN } from './locales/zh-CN'
import type { SupportedLocale } from '@/types/api'

export const supportedLocales: SupportedLocale[] = ['en', 'zh-CN', 'fr-FR']

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en, 'zh-CN': zhCN, 'fr-FR': frFR },
})

export function isSupportedLocale(value: string | null | undefined): value is SupportedLocale {
  return supportedLocales.includes(value as SupportedLocale)
}

export function setAppLocale(locale: SupportedLocale) {
  i18n.global.locale.value = locale
  document.documentElement.lang = locale
}
