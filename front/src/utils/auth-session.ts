export interface LoginSession {
  token: string
  tokenType: string
  username: string
  expiresAt: string
}

const SESSION_KEY = 'freyja_login_session'
let expiryTimer: ReturnType<typeof setTimeout> | undefined

export function getLoginSession(): LoginSession | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(SESSION_KEY) || 'null') as LoginSession | null
    return value && typeof value.token === 'string' && Date.parse(value.expiresAt) > Date.now() ? value : null
  } catch { return null }
}

export function saveLoginSession(session: LoginSession) {
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
  scheduleExpiry()
}

export function clearLoginSession() {
  sessionStorage.removeItem(SESSION_KEY)
  clearTimeout(expiryTimer)
  window.dispatchEvent(new Event('freyja-logged-out'))
}

export function safeLoginRedirect(value: unknown): string {
  return typeof value === 'string' && /^\/(?![\\/])/.test(value) && !value.startsWith('/login') ? value : '/dashboard'
}

export function expireLogin() {
  clearLoginSession()
  if (window.location.pathname !== '/login') {
    const redirect = window.location.pathname + window.location.search + window.location.hash
    window.location.replace(`/login?redirect=${encodeURIComponent(redirect)}`)
  }
}

function scheduleExpiry() {
  clearTimeout(expiryTimer)
  const session = getLoginSession()
  if (session) expiryTimer = setTimeout(expireLogin, Date.parse(session.expiresAt) - Date.now())
}

scheduleExpiry()
