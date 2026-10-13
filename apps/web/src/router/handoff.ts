import type { HistoryState } from 'vue-router'
import type { SnapshotResponse } from '@/api/client'

/**
 * What a page that has just created a document hands to the workspace it
 * navigates to: sources it attached along the way, shown at once while the
 * workspace asks the server for its own list, one sentence the person
 * should read on arrival when a source could not be attached, and the notes
 * about an uploaded form (what Brownie found in it and changed in its copy),
 * or the request in the chat that the document was started from.
 * Carried in the browser's history state for the pushed route -- so it
 * survives a reload of the workspace page and the sentences are still there
 * to read -- rather than in a store that a reload would wipe.
 */
export interface DocumentHandoff {
  attachedSources: SnapshotResponse[]
  /** Shown on arrival as a warning, whatever it is about; the workspace reads it by this name. */
  sourceWarning: string | null
  /** Shown on arrival under "About this document", one sentence each: news about the form, not a warning. */
  formNotes: string[]
  /** What the person asked for in the chat before this document existed. Absent for a document started any other way. */
  request?: DocumentRequest
}

/**
 * A request made in the chat, carried to the document it started so the workspace can show it where the person
 * will look and begin reading for it. The person agreed to the plan, so nothing here is a guess: the sentence is
 * theirs, word for word.
 */
export interface DocumentRequest {
  /** The sentence as it was typed. */
  text: string
  /**
   * The `id` of the one entry of `attachedSources` Brownie should read, as the workspace's own list calls it;
   * null when the request came with no source and there is nothing to read yet.
   */
  sourceId: number | null
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
  const { attachedSources, sourceWarning, formNotes, request } = handoff as Partial<DocumentHandoff>
  const asked = readRequest(request)
  return {
    attachedSources: Array.isArray(attachedSources) ? attachedSources : [],
    sourceWarning: typeof sourceWarning === 'string' ? sourceWarning : null,
    // Written by an older page, the state has none; anything that is not a sentence is left out.
    formNotes: Array.isArray(formNotes) ? formNotes.filter((note): note is string => typeof note === 'string' && note !== '') : [],
    // Left out altogether, not set to null, so a handoff with no request reads exactly as it did before there were any.
    ...(asked ? { request: asked } : {}),
  }
}

/** The request in the state, or nothing when it is missing or is not a sentence worth showing. */
function readRequest(value: unknown): DocumentRequest | undefined {
  if (!value || typeof value !== 'object') return undefined
  const { text, sourceId } = value as Partial<Record<keyof DocumentRequest, unknown>>
  if (typeof text !== 'string' || text.trim() === '') return undefined
  return { text, sourceId: typeof sourceId === 'number' && Number.isInteger(sourceId) ? sourceId : null }
}

/**
 * Takes the form notes out of the handoff in the current history entry, so
 * notes the person dismissed do not come back when they reload the page.
 * The router's own entries in the state are kept as they are.
 */
export function forgetFormNotes(): void {
  const state: unknown = window.history.state
  if (!state || typeof state !== 'object') return
  const handoff = (state as Record<string, unknown>)[DOCUMENT_HANDOFF_STATE_KEY]
  if (!handoff || typeof handoff !== 'object') return
  window.history.replaceState({ ...state, [DOCUMENT_HANDOFF_STATE_KEY]: { ...handoff, formNotes: [] } }, '')
}

/**
 * Takes the request out of the handoff in the current history entry, once the workspace has shown it and
 * started reading for it, so reloading the page does not start the same reading again. The sources and
 * everything else in the handoff, and the router's own entries in the state, are kept as they are.
 */
export function forgetDocumentRequest(): void {
  const state: unknown = window.history.state
  if (!state || typeof state !== 'object') return
  const handoff = (state as Record<string, unknown>)[DOCUMENT_HANDOFF_STATE_KEY]
  if (!handoff || typeof handoff !== 'object') return
  const kept: Record<string, unknown> = { ...handoff }
  delete kept.request
  window.history.replaceState({ ...state, [DOCUMENT_HANDOFF_STATE_KEY]: kept }, '')
}
