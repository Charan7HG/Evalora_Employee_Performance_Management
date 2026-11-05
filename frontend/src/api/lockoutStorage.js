/**
 * Local copy of the backend lockout deadline.
 *
 * The backend is the only authority on the lock: this record merely lets the
 * login screen keep showing the countdown after a refresh. It stores only the
 * email the visitor already typed and the server-provided expiry time - never
 * a password, token or security answer.
 */
const LOCKOUT_STORAGE_KEY = 'evalora.login-lockout'

export function saveLockout(email, untilEpochMs) {
  try {
    localStorage.setItem(LOCKOUT_STORAGE_KEY, JSON.stringify({ email, untilEpochMs }))
  } catch {
    // A browser with storage disabled still gets the countdown for this page load.
  }
}

export function getLockout() {
  try {
    const raw = localStorage.getItem(LOCKOUT_STORAGE_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw)
    if (typeof parsed?.untilEpochMs !== 'number') {
      return null
    }
    return parsed
  } catch {
    return null
  }
}

export function clearLockout() {
  try {
    localStorage.removeItem(LOCKOUT_STORAGE_KEY)
  } catch {
    // Nothing to do: there is no stored value to clear.
  }
}

export { LOCKOUT_STORAGE_KEY }
