import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import {
  beginBackendRead,
  isOnline,
  markBackendAvailable,
  markBackendRetrying,
  markBackendUnavailable,
} from '@/services/connectivity'
import type { ApiError, AuthResponse } from '@/types/api'

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api/v1'

export function resolveApiUrl(path: string) {
  if (/^https?:\/\//i.test(path)) return path
  if (/^https?:\/\//i.test(baseURL)) return new URL(path, new URL(baseURL).origin).toString()
  return path
}

export const api: AxiosInstance = axios.create({
  baseURL,
  withCredentials: true,
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
})

const sessionClient = axios.create({ baseURL, withCredentials: true, timeout: 30_000 })
const safeMethods = new Set(['get', 'head'])
const retryableStatuses = new Set([502, 503, 504])
const coldStartRetryDelays = [750, 1_500] as const
let accessToken: string | null = null
let refreshPromise: Promise<AuthResponse> | null = null
let onSessionUpdated: ((session: AuthResponse) => void) | undefined
let onSessionExpired: (() => void) | undefined

export function setAccessToken(token: string | null) {
  accessToken = token
}

export function configureSessionHandlers(handlers: {
  updated: (session: AuthResponse) => void
  expired: () => void
}) {
  onSessionUpdated = handlers.updated
  onSessionExpired = handlers.expired
}

export async function refreshSession(): Promise<AuthResponse> {
  if (!refreshPromise) {
    refreshPromise = sessionClient
      .post<AuthResponse>('/auth/refresh')
      .then(({ data }) => {
        setAccessToken(data.accessToken)
        onSessionUpdated?.(data)
        return data
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

type RetryConfig = InternalAxiosRequestConfig & {
  _authRetried?: boolean
  _coldStartAttempt?: number
  _finishBackendRead?: () => void
  _skipAuth?: boolean
}

export function shouldRetryColdStart(input: {
  method?: string
  attempt: number
  status?: number
  networkError: boolean
  canceled: boolean
  online: boolean
}) {
  const method = input.method?.toLowerCase()
  return (
    Boolean(method && safeMethods.has(method)) &&
    input.attempt < coldStartRetryDelays.length &&
    input.online &&
    !input.canceled &&
    (input.networkError || Boolean(input.status && retryableStatuses.has(input.status)))
  )
}

export function coldStartDelayMs(attempt: number) {
  return coldStartRetryDelays[attempt] ?? coldStartRetryDelays.at(-1)!
}

function finishBackendRead(request?: RetryConfig) {
  request?._finishBackendRead?.()
  if (request) request._finishBackendRead = undefined
}

api.interceptors.request.use((config: RetryConfig) => {
  if (accessToken && !config._skipAuth) config.headers.Authorization = `Bearer ${accessToken}`
  if (safeMethods.has(config.method?.toLowerCase() || '')) {
    config._finishBackendRead = beginBackendRead()
  }
  return config
})

api.interceptors.response.use(
  (response) => {
    finishBackendRead(response.config as RetryConfig)
    markBackendAvailable()
    return response
  },
  async (error: AxiosError<ApiError>) => {
    const request = error.config as RetryConfig | undefined
    finishBackendRead(request)
    const attempt = request?._coldStartAttempt || 0
    const retryColdStart = Boolean(
      request &&
      shouldRetryColdStart({
        method: request.method,
        attempt,
        status: error.response?.status,
        networkError: !error.response,
        canceled: axios.isCancel(error) || error.code === AxiosError.ERR_CANCELED,
        online: isOnline.value,
      }),
    )

    if (request && retryColdStart) {
      request._coldStartAttempt = attempt + 1
      markBackendRetrying()
      await new Promise((resolve) => window.setTimeout(resolve, coldStartDelayMs(attempt)))
      return api(request)
    }

    const exhaustedRetryableRead = Boolean(
      request &&
      safeMethods.has(request.method?.toLowerCase() || '') &&
      isOnline.value &&
      !axios.isCancel(error) &&
      (!error.response || retryableStatuses.has(error.response.status)),
    )
    if (exhaustedRetryableRead) markBackendUnavailable()

    const isAuthRequest = request?.url?.startsWith('/auth/')
    if (error.response?.status !== 401 || !request || request._authRetried || isAuthRequest) {
      return Promise.reject(error)
    }
    request._authRetried = true
    try {
      const session = await refreshSession()
      request.headers.Authorization = `Bearer ${session.accessToken}`
      return await api(request)
    } catch (refreshError) {
      setAccessToken(null)
      onSessionExpired?.()
      return Promise.reject(refreshError)
    }
  },
)

export async function warmBackend() {
  await api.get(resolveApiUrl('/actuator/health'), { _skipAuth: true } as RetryConfig)
}

export function apiErrorCode(error: unknown): string {
  return parseApiError(error).code
}

export function apiFieldErrors(error: unknown): Record<string, string> {
  return parseApiError(error).fieldErrors
}

export interface ParsedApiError {
  code: string
  status?: number
  message?: string
  fieldErrors: Record<string, string>
  traceId?: string
  networkError: boolean
  uncertainWrite: boolean
}

export function parseApiError(error: unknown): ParsedApiError {
  if (!axios.isAxiosError<ApiError>(error)) {
    return { code: 'UNKNOWN', fieldErrors: {}, networkError: false, uncertainWrite: false }
  }
  const data = error.response?.data
  const method = error.config?.method?.toLowerCase()
  const networkError = !error.response
  return {
    code: data?.code || 'UNKNOWN',
    status: error.response?.status ?? data?.status,
    message: data?.message,
    fieldErrors: data?.fieldErrors || {},
    traceId: data?.traceId,
    networkError,
    uncertainWrite: networkError && Boolean(method && !safeMethods.has(method)),
  }
}
