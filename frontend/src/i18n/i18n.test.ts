import { describe, expect, it } from 'vitest'
import { en } from './locales/en'
import { zhCN } from './locales/zh-CN'
import { frFR } from './locales/fr-FR'
import { i18n, isSupportedLocale, setAppLocale } from './index'
import { elementLocaleFor } from './elementLocale'
import elementEn from 'element-plus/es/locale/lang/en'
import elementFr from 'element-plus/es/locale/lang/fr'
import elementZhCn from 'element-plus/es/locale/lang/zh-cn'

function flatten(value: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(value)
    .flatMap(([key, child]) => {
      const path = prefix ? `${prefix}.${key}` : key
      return typeof child === 'object' && child !== null
        ? flatten(child as Record<string, unknown>, path)
        : [path]
    })
    .sort()
}

describe('i18n catalogue', () => {
  it('keeps exact key parity in English, Simplified Chinese, and French', () => {
    expect(flatten(zhCN)).toEqual(flatten(en))
    expect(flatten(frFR)).toEqual(flatten(en))
  })

  it('contains no empty user-facing messages', () => {
    for (const messages of [en, zhCN, frFR]) {
      for (const key of flatten(messages)) {
        const value = key
          .split('.')
          .reduce<unknown>((node, segment) => (node as Record<string, unknown>)[segment], messages)
        expect(String(value).trim(), key).not.toBe('')
      }
    }
  })

  it('defaults to English and only accepts supported server locales', () => {
    expect(i18n.global.fallbackLocale.value).toEqual('en')
    expect(isSupportedLocale('en')).toBe(true)
    expect(isSupportedLocale('zh-CN')).toBe(true)
    expect(isSupportedLocale('fr-FR')).toBe(true)
    expect(isSupportedLocale('de')).toBe(false)
    setAppLocale('fr-FR')
    expect(i18n.global.locale.value).toBe('fr-FR')
    expect(document.documentElement.lang).toBe('fr-FR')
    setAppLocale('en')
  })

  it('selects the matching Element Plus locale with English fallback', () => {
    expect(elementLocaleFor('en')).toBe(elementEn)
    expect(elementLocaleFor('zh-CN')).toBe(elementZhCn)
    expect(elementLocaleFor('fr-FR')).toBe(elementFr)
    expect(elementLocaleFor('de')).toBe(elementEn)
  })
})
