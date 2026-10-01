import { defineStore } from 'pinia'
import { listTemplates, type TemplateResponse } from '@/api/client'

interface TemplatesState {
  templates: TemplateResponse[]
  status: 'idle' | 'loading' | 'loaded' | 'error'
  /** Counts every template moved to the Trash Bin from this tab, so a Trash Bin page already open can fetch its list again. */
  trashedSerial: number
}

const dayFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

/**
 * The name a document started straight from a template gets: the
 * template's own name and the day, "Club minutes, Sep 29, 2026". Nobody is
 * asked for a title first, and two documents started from the same
 * template on different days can still be told apart in the documents
 * list.
 */
export function documentTitleFor(templateName: string, when: Date = new Date()): string {
  return `${templateName}, ${dayFormatter.format(when)}`
}

/**
 * The workspace's templates, kept in one place because the sidebar's
 * list stays mounted across every navigation while other screens change
 * what is in it: uploading a form adds one, and restoring one from the
 * Trash Bin puts it back. Without a shared copy the sidebar would either
 * refetch on every route change or quietly go stale the moment a template
 * is activated. The other way round works the same: the sidebar moves a
 * template to the Trash Bin while that page may be open beside it, and
 * the page watches trashedSerial to pick it up.
 *
 * A failed load is a status, not a thrown error: the sidebar shows a short
 * line instead of the list and the rest of the page is unaffected.
 */
export const useTemplatesStore = defineStore('templates', {
  state: (): TemplatesState => ({ templates: [], status: 'idle', trashedSerial: 0 }),
  getters: {
    /**
     * Only an activated template can start a document, so only those are
     * offered. A template in the Trash Bin is never in the list the server
     * sends; leaving it out here as well keeps a stale copy from offering it.
     */
    usable(state): TemplateResponse[] {
      return state.templates.filter((template) => template.currentActiveVersionId != null && template.trashedAt == null)
    },
  },
  actions: {
    /** Loads once per workspace; call refresh() to pick up a template that was just activated. */
    async load(workspaceId: number): Promise<void> {
      if (this.status === 'loading' || this.status === 'loaded') return
      await this.refresh(workspaceId)
    },

    /**
     * Fetches the list again. Only the first load says "loading": a list
     * already on screen stays there while the new one is fetched, so a
     * template restored from the Trash Bin appears in place instead of the
     * whole list blinking out and back.
     */
    async refresh(workspaceId: number): Promise<void> {
      if (this.status !== 'loaded') this.status = 'loading'
      try {
        this.templates = await listTemplates(workspaceId)
        this.status = 'loaded'
      } catch {
        // A list already on screen stays, since what it shows is still true; only a first load that failed says so.
        if (this.status !== 'loaded') this.status = 'error'
      }
    },

    /** Takes a template off the list without asking again, once the server has said it is in the Trash Bin. */
    forget(templateId: number): void {
      this.templates = this.templates.filter((template) => template.id !== templateId)
    },

    /** Forgets a template the server has just said is in the Trash Bin, and tells a Trash Bin page open at the same time to look again. */
    markTrashed(templateId: number): void {
      this.forget(templateId)
      this.trashedSerial += 1
    },
  },
})
