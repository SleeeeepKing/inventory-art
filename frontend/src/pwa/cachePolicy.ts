export const networkOnlyPathPrefixes = ['/api/', '/actuator/'] as const

export function isNetworkOnlyRequest(url: URL, appOrigin: string) {
  return (
    url.origin !== appOrigin ||
    networkOnlyPathPrefixes.some(
      (prefix) => url.pathname === prefix.slice(0, -1) || url.pathname.startsWith(prefix),
    )
  )
}

export function isNavigationFallbackAllowed(url: URL, appOrigin: string) {
  return url.origin === appOrigin && !isNetworkOnlyRequest(url, appOrigin)
}

export const navigationFallbackDenylist = [/^\/api(?:\/|$)/, /^\/actuator(?:\/|$)/]
