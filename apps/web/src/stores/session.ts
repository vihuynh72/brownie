import { defineStore } from 'pinia'
import { ApiRequestError, getCurrentIdentity, type MeResponse } from '@/api/client'

interface SessionState {
  identity: MeResponse | null
  status: 'unknown' | 'loading' | 'authenticated' | 'anonymous'
}

/**
 * The server is authoritative for identity and workspace membership; this
 * store only caches the last /me response so views do not each fetch it
 * independently. A personal workspace is the first membership returned --
 * team workspace selection is a later addition once the UI needs it.
 */
export const useSessionStore = defineStore('session', {
  state: (): SessionState => ({
    identity: null,
    status: 'unknown',
  }),
  getters: {
    personalWorkspaceId(state): number | undefined {
      return state.identity?.memberships[0]?.workspaceId
    },
  },
  actions: {
    async loadIdentity(): Promise<void> {
      this.status = 'loading'
      try {
        this.identity = await getCurrentIdentity()
        this.status = 'authenticated'
      } catch (error) {
        if (error instanceof ApiRequestError && error.status === 401) {
          this.identity = null
          this.status = 'anonymous'
          return
        }
        throw error
      }
    },
  },
})
