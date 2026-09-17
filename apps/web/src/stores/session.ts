import { defineStore } from 'pinia'
import { ApiRequestError, getCurrentIdentity, logout, type MeResponse } from '@/api/client'

interface SessionState {
  identity: MeResponse | null
  status: 'unknown' | 'loading' | 'authenticated' | 'anonymous' | 'error'
  /** Why the last identity load failed, for the recovery prompt; null unless status is 'error'. */
  lastError: string | null
}

/**
 * The server is authoritative for identity and workspace membership; this
 * store only caches the last /me response so views do not each fetch it
 * independently. A personal workspace is the first membership returned --
 * team workspace selection is a later addition once the UI needs it.
 *
 * A 401 is the ordinary signed-out state. Anything else -- the API down,
 * a proxy error, a network failure -- is recorded as 'error' rather than
 * thrown: every view waits on this status, and an exception escaping from
 * the router guard or App.vue's own mount left every page stuck on its
 * loading message with nothing to click. See App.vue for the retry.
 */
export const useSessionStore = defineStore('session', {
  state: (): SessionState => ({
    identity: null,
    status: 'unknown',
    lastError: null,
  }),
  getters: {
    personalWorkspaceId(state): number | undefined {
      return state.identity?.memberships[0]?.workspaceId
    },
  },
  actions: {
    async loadIdentity(): Promise<void> {
      this.status = 'loading'
      this.lastError = null
      try {
        this.identity = await getCurrentIdentity()
        this.status = 'authenticated'
      } catch (error) {
        this.identity = null
        if (error instanceof ApiRequestError && error.status === 401) {
          this.status = 'anonymous'
          return
        }
        this.status = 'error'
        this.lastError =
          error instanceof ApiRequestError
            ? `Brownie could not confirm your sign-in (status ${error.status}).`
            : 'Brownie could not be reached.'
      }
    },

    /**
     * Ends the server session, then reports where the page must navigate
     * to finish signing out at the identity provider. The session is gone
     * once this resolves, whatever happens next.
     */
    async signOut(): Promise<string> {
      const { redirectUrl } = await logout()
      this.identity = null
      this.status = 'anonymous'
      this.lastError = null
      return redirectUrl
    },
  },
})
