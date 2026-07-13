import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { parseApiError } from '@/services/api'

const errorKeys: Record<string, string> = {
  ACCESS_DENIED: 'errors.forbidden',
  FORBIDDEN: 'errors.forbidden',
  UNAUTHENTICATED: 'errors.sessionExpired',
  VALIDATION_ERROR: 'errors.validation',
  INVALID_REQUEST: 'errors.invalidRequest',
  DATA_CONFLICT: 'errors.conflict',
  RESOURCE_NOT_FOUND: 'errors.resourceNotFound',
  INVALID_CREDENTIALS: 'auth.loginFailed',
  INVALID_PASSWORD: 'errors.invalidPassword',
  DUPLICATE_USER: 'errors.duplicateUser',
  DUPLICATE_SKU: 'errors.duplicateSku',
  DUPLICATE_TENANT_SLUG: 'errors.duplicateTenantSlug',
  DUPLICATE_EVENT_NAME: 'errors.duplicateEventName',
  SALES_EVENT_DISABLED: 'errors.salesEventDisabled',
  SALES_EVENT_IN_USE: 'errors.salesEventInUse',
  INSUFFICIENT_STOCK: 'errors.insufficientStock',
  STOCK_UNCHANGED: 'inventory.stockUnchanged',
  ORDER_OUTSIDE_EVENT: 'errors.orderOutsideEvent',
  INVALID_LOCALE: 'errors.invalidLocale',
  UNSUPPORTED_LOCALE: 'errors.invalidLocale',
  OPTIMISTIC_LOCK_CONFLICT: 'errors.conflict',
}

export function useApiFeedback() {
  const { t } = useI18n()
  function showError(error: unknown) {
    const parsed = parseApiError(error)
    let message: string
    if (parsed.uncertainWrite) message = t('errors.writeOutcomeUnknown')
    else if (parsed.networkError) message = t('errors.network')
    else if (Object.keys(parsed.fieldErrors).length) {
      const details = [...new Set(Object.values(parsed.fieldErrors))].join('; ')
      message = `${t(errorKeys[parsed.code] || 'errors.validation')} ${details}`
    } else if (errorKeys[parsed.code]) message = t(errorKeys[parsed.code])
    else if (parsed.status === 401) message = t('errors.sessionExpired')
    else if (parsed.status === 403) message = t('errors.forbidden')
    else if (parsed.status && parsed.status >= 400 && parsed.status < 500 && parsed.message)
      message = parsed.message
    else {
      message = t('errors.generic')
      if (parsed.traceId) message += ` ${t('errors.traceId', { traceId: parsed.traceId })}`
    }
    ElMessage.error(message)
    return parsed.fieldErrors
  }
  return { showError }
}
