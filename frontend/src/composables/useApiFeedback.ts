import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { apiErrorCode } from '@/services/api'

const errorKeys: Record<string, string> = {
  ACCESS_DENIED: 'errors.forbidden',
  FORBIDDEN: 'errors.forbidden',
  VALIDATION_ERROR: 'errors.validation',
  DUPLICATE_SKU: 'errors.duplicateSku',
  INSUFFICIENT_STOCK: 'errors.insufficientStock',
  INVALID_LOCALE: 'errors.invalidLocale',
  OPTIMISTIC_LOCK_CONFLICT: 'errors.conflict',
  DUPLICATE_IMPORT_FILE: 'import.duplicate',
}

export function useApiFeedback() {
  const { t } = useI18n()
  function showError(error: unknown) {
    const code = apiErrorCode(error)
    ElMessage.error(t(errorKeys[code] || 'errors.generic'))
  }
  return { showError }
}
