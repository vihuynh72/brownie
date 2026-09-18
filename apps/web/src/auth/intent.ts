/**
 * Where the person was heading when they were asked to sign in.
 *
 * Signing in leaves this origin entirely: the browser goes to the identity
 * provider, which sends it back to the API's callback, which redirects to
 * this app's home address -- there is no way to carry a destination
 * through that round trip in the URL, because the API decides the landing
 * address, not the page. So the destination is parked in sessionStorage
 * (this tab only, cleared when the tab closes) and picked up once the
 * session exists.
 *
 * Only an in-app path is ever stored or returned. A value that is not a
 * single-slash absolute path is discarded rather than followed, so nothing
 * that reaches this module can turn the post-sign-in landing into a jump
 * to another site.
 */
const INTENT_KEY = 'brownie.signInIntent'

export function isInAppPath(value: unknown): value is string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\')
}

/** Storage is unavailable in a private-mode browser with site data blocked; remembering the path is a convenience, never a requirement. */
export function rememberSignInIntent(path: string): void {
  if (!isInAppPath(path)) return
  try {
    window.sessionStorage.setItem(INTENT_KEY, path)
  } catch {
    /* The person lands on the home page instead, which is a working outcome. */
  }
}

/** Reads the parked destination and forgets it, so a later sign-in on the same tab does not replay an old one. */
export function takeSignInIntent(): string | null {
  try {
    const stored = window.sessionStorage.getItem(INTENT_KEY)
    window.sessionStorage.removeItem(INTENT_KEY)
    return isInAppPath(stored) ? stored : null
  } catch {
    return null
  }
}
