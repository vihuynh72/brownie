import type { HistoryState } from 'vue-router'
import type { SnapshotResponse } from '@/api/client'

/**
 * What the new-document screen hands to the workspace it navigates to:
 * the source it just attached (a workspace-level snapshot the workspace
 * screen has no other way to discover yet, since nothing links a source
 * to one document server-side), and a warning if that attachment failed.
 * Carried in the browser's history state for the pushed route -- so it
 * survives a reload of the workspace page and the warning is still there
 * to read -- rather than in a store that a reload would wipe.
 */
export interface DocumentHandoff {
  attachedSources: SnapshotResponse[]
  sourceWarning: string | null
}

const DOCUMENT_HANDOFF_STATE_KEY = 'documentHandoff'

/** As the `state` of a router push. The cast is only for the router's own history-state typing; the value is plain, structured-clone-safe JSON. */
export function documentHandoffState(handoff: DocumentHandoff): HistoryState {
  return { [DOCUMENT_HANDOFF_STATE_KEY]: handoff as unknown as HistoryState }
}

export function readDocumentHandoff(): DocumentHandoff | null {
  const state: unknown = window.history.state
  if (!state || typeof state !== 'object') return null
  const handoff = (state as Record<string, unknown>)[DOCUMENT_HANDOFF_STATE_KEY]
  if (!handoff || typeof handoff !== 'object') return null
  const { attachedSources, sourceWarning } = handoff as Partial<DocumentHandoff>
  return {
    attachedSources: Array.isArray(attachedSources) ? attachedSources : [],
    sourceWarning: typeof sourceWarning === 'string' ? sourceWarning : null,
  }
}
