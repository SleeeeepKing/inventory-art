import elementEn from 'element-plus/es/locale/lang/en'
import elementFr from 'element-plus/es/locale/lang/fr'
import elementZhCn from 'element-plus/es/locale/lang/zh-cn'
import type { SupportedLocale } from '@/types/api'

const elementLocales = { en: elementEn, 'zh-CN': elementZhCn, 'fr-FR': elementFr }

export function elementLocaleFor(locale: SupportedLocale | string) {
  return elementLocales[locale as SupportedLocale] || elementEn
}
