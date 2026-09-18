import { defineStore } from 'pinia'
import { listTemplates, type TemplateResponse } from '@/api/client'

interface TemplatesState {
  templates: TemplateResponse[]
  status: 'idle' | 'loading' | 'loaded' | 'error'
}

/**
 * The workspace's templates, kept in one place because two long-lived
 * parts of the interface show them: the sidebar's list, which stays
 * mounted across every navigation, and the template screen, which adds to
 * it. Without a shared copy the sidebar would either refetch on every
 * route change or quietly go stale the moment a template is activated.
 *
 * A failed load is a status, not a thrown error: the sidebar shows a short
 * line instead of the list and the rest of the page is unaffected.
 */
export const useTemplatesStore = defineStore('templates', {
  state: (): TemplatesState => ({ templates: [], status: 'idle' }),
  getters: {
    /** Only an activated template can start a document, so only those are offered as links. */
    usable(state): TemplateResponse[] {
      return state.templates.filter((template) => template.currentActiveVersionId != null)
    },
  },
  actions: {
    /** Loads once per workspace; call refresh() to pick up a template that was just activated. */
    async load(workspaceId: number): Promise<void> {
      if (this.status === 'loading' || this.status === 'loaded') return
      await this.refresh(workspaceId)
    },

    async refresh(workspaceId: number): Promise<void> {
      this.status = 'loading'
      try {
        this.templates = await listTemplates(workspaceId)
        this.status = 'loaded'
      } catch {
        this.status = 'error'
      }
    },
  },
})
