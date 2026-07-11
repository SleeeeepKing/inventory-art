import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
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

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`
  return config
})

type RetryConfig = InternalAxiosRequestConfig & { _retried?: boolean }

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiError>) => {
    const request = error.config as RetryConfig | undefined
    const isAuthRequest = request?.url?.startsWith('/auth/')
    if (error.response?.status !== 401 || !request || request._retried || isAuthRequest) {
      return Promise.reject(error)
    }
    request._retried = true
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

export function apiErrorCode(error: unknown): string {
  if (axios.isAxiosError<ApiError>(error)) return error.response?.data?.code || 'UNKNOWN'
  return 'UNKNOWN'
}

export function apiFieldErrors(error: unknown): Record<string, string> {
  if (axios.isAxiosError<ApiError>(error)) return error.response?.data?.fieldErrors || {}
  return {}
}
