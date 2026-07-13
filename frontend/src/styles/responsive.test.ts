import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync('src/styles/main.css', 'utf8')
const mobileRules = css.slice(css.indexOf('@media (max-width: 768px)'))

describe('mobile-primary layout contract', () => {
  it('keeps mobile navigation and controls touch friendly', () => {
    expect(mobileRules).toContain('.side-nav')
    expect(mobileRules).toMatch(/\.mobile-menu\s*\{[^}]*width:\s*44px[^}]*height:\s*44px/s)
    expect(mobileRules).toContain('min-height: 44px')
  })

  it('keeps dialogs, actions, and data tables inside a phone viewport', () => {
    expect(mobileRules).toContain('max-height: calc(100dvh - 20px)')
    expect(css).toMatch(/\.family-dialog\s*\{[^}]*max-height:\s*calc\(100dvh - 40px\)/s)
    expect(css).toMatch(/\.family-dialog \.el-dialog__body\s*\{[^}]*overflow-y:\s*auto/s)
    expect(mobileRules).toMatch(/\.el-dialog__footer[^}]*flex-direction:\s*column/s)
    expect(mobileRules).toContain('overflow-x: auto')
  })

  it('keeps image-aware product choices recognizable on 375–390px screens', () => {
    expect(css).toMatch(/\.product-option__thumb\s*\{[^}]*width:\s*36px[^}]*height:\s*36px/s)
    expect(css).toMatch(/\.product-option__copy strong,[^}]*text-overflow:\s*ellipsis/s)
    expect(css).toMatch(
      /\.el-select-dropdown__item:has\(\.product-option\)\s*\{[^}]*min-height:\s*48px/s,
    )
    expect(mobileRules).toMatch(/\.inventory-sale-row\s*\{[^}]*min-width:\s*420px/s)
  })

  it('stacks report filters and quick actions without scaling translated labels', () => {
    expect(css).toMatch(/\.report-filters\s*\{[^}]*flex-wrap:\s*wrap/s)
    expect(css).toMatch(/\.report-presets button\s*\{[^}]*white-space:\s*normal/s)
    expect(css).toMatch(
      /\.report-filter-field \.el-radio-button__inner\s*\{[^}]*min-height:\s*44px[^}]*white-space:\s*normal/s,
    )
    expect(mobileRules).toMatch(/\.report-filters\s*\{[^}]*flex-direction:\s*column/s)
    expect(mobileRules).toMatch(/\.quick-actions__list\s*\{[^}]*grid-template-columns:\s*1fr/s)
    expect(css).toMatch(/\.quick-action\s*\{[^}]*font-size:\s*13px/s)
  })

  it('keeps PWA notices clear of notches and standalone chrome', () => {
    expect(css).toContain('@media (display-mode: standalone)')
    expect(css).toContain('env(safe-area-inset-top)')
    expect(css).toContain('env(safe-area-inset-bottom)')
    expect(mobileRules).toMatch(/\.pwa-status\s*\{[^}]*top:/s)
  })
})
