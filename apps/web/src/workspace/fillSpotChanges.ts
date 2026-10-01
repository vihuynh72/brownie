import { computed, ref, watch, type Ref } from 'vue'
import {
  ApiRequestError,
  changeFillSpots,
  getTemplateVersion,
  moveDocumentToTemplateVersion,
  restoreRevision,
  type DocumentResponse,
  type FieldDefinitionResponse,
  type FillSpotChangeRequest,
  type FillSpotsResponse,
} from '@/api/client'
import { movedWords, spotRefusal, versionDifferenceWords, type SpotAction, type SpotRefusal } from '@/workspace/fillSpotWords'

/*
 * Changing an open document's fill spots, and moving it to the newest version of its form.
 *
 * A change makes a new version of the form, and the document moves to it keeping its values; so before
 * anything is sent, whatever the person typed is saved first (a change is made against the version on
 * screen, which must hold it), and afterwards the page loads the document again, and with it the new
 * version's layout and fill spots. Undo is a restore of the version the change replaced.
 *
 * A document can also be on an older version of its form than the newest, when a spot was added from
 * another document. The page then says what the newer version changes, and moves this one on request.
 */

/** What a change needs from the page it is made on. */
export interface FillSpotChangeHost {
  workspaceId: () => number | undefined
  documentId: () => number
  document: Ref<DocumentResponse | null>
  /** The current version's fill spots, to compare with the newest version's. */
  definitions: Ref<readonly FieldDefinitionResponse[]>
  labelOf: (fieldId: string) => string
  /** Saves what the person typed; null when nothing is left unsaved, else why it could not be saved. */
  saveTypingFirst: () => Promise<string | null>
  /** Loads the document again, with the layout of the version it is on; false when it could not. */
  reload: () => Promise<boolean>
}

/** What Undo of a change needs: the revision before it, and the one it made. */
export interface SpotUndo {
  action: Exclude<SpotAction, 'move'>
  label: string
  previousRevisionId: number
  revisionId: number
}

export type SpotChangeOutcome = { response: FillSpotsResponse } | { refusal: SpotRefusal }

export function useFillSpotChanges(host: FillSpotChangeHost) {
  /** Why the page is out of date, when the refusal of a change made the page load the document again and that failed. */
  async function refuse(error: unknown, action: SpotAction): Promise<SpotRefusal> {
    const refusal = spotRefusal(error, action, host.labelOf)
    if (error instanceof ApiRequestError && error.problem?.code === 'DOCUMENT_TEMPLATE_VERSION_MOVED') offerNewerVersionAgain()
    if (refusal.reload) {
      const reloaded = await host.reload()
      if (!reloaded && refusal.staleRevision) return { ...refusal, message: 'This document changed since you loaded it, so that was not done.' }
    }
    return refusal
  }

  /** Makes one change to the form of the document on screen, typing saved first. */
  async function change(changes: FillSpotChangeRequest[], action: Exclude<SpotAction, 'move'>): Promise<SpotChangeOutcome> {
    const workspaceId = host.workspaceId()
    if (workspaceId === undefined || !host.document.value) {
      return { refusal: { message: 'This document is not loaded yet. Try again in a moment.', reload: false, staleRevision: false } }
    }
    const unsaved = await host.saveTypingFirst()
    if (unsaved !== null) return { refusal: { message: unsaved, reload: false, staleRevision: false } }
    const current = host.document.value
    try {
      const response = await changeFillSpots(
        workspaceId,
        host.documentId(),
        current.currentRevision.id,
        current.templateVersionId,
        changes,
        crypto.randomUUID(),
      )
      await host.reload()
      return { response }
    } catch (error) {
      return { refusal: await refuse(error, action) }
    }
  }

  /** Puts the document back as it was before a change: the form version and the values. Null when done, else why not. */
  async function undo(change: SpotUndo): Promise<string | null> {
    const workspaceId = host.workspaceId()
    if (workspaceId === undefined || !host.document.value) return 'This document is not loaded yet. Try again in a moment.'
    const unsaved = await host.saveTypingFirst()
    if (unsaved !== null) return unsaved
    const current = host.document.value
    try {
      await restoreRevision(workspaceId, host.documentId(), change.previousRevisionId, current.currentRevision.id, crypto.randomUUID(), 'Undid a fill spot change.')
      await host.reload()
      return null
    } catch (error) {
      return (await refuse(error, change.action)).message
    }
  }

  // ---- A newer version of the form ----------------------------------------------------------------

  const latestVersionId = computed(() => host.document.value?.templateLatestVersionId ?? null)
  const behindLatest = computed(() => {
    const document = host.document.value
    return document !== null && latestVersionId.value !== null && latestVersionId.value !== document.templateVersionId
  })
  const latestDefinitions = ref<readonly FieldDefinitionResponse[] | null>(null)
  let latestReadFor: number | null = null
  /** "Not now" for this newest version; a later one is offered again. */
  const setAsideFor = ref<number | null>(null)
  const moving = ref(false)
  const moveError = ref<string | null>(null)

  watch(
    [behindLatest, latestVersionId],
    async () => {
      const workspaceId = host.workspaceId()
      const document = host.document.value
      const latest = latestVersionId.value
      if (!behindLatest.value || workspaceId === undefined || !document || latest === null || latestReadFor === latest) return
      latestReadFor = latest
      latestDefinitions.value = null
      try {
        const version = await getTemplateVersion(workspaceId, document.templateId, latest)
        if (latestReadFor === latest) latestDefinitions.value = version?.fields ?? []
      } catch {
        // The banner still says there is a newer version; only what it changes is unknown.
        if (latestReadFor === latest) latestDefinitions.value = null
      }
    },
    { immediate: true },
  )

  /** The banner's words while a newer version is on offer; null when there is none, or it was set aside. */
  const newerVersionWords = computed(() => {
    if (!behindLatest.value || setAsideFor.value === latestVersionId.value) return null
    const difference = latestDefinitions.value ? versionDifferenceWords(host.definitions.value, latestDefinitions.value) : ''
    return difference ? `This form has a newer version. ${difference}` : 'This form has a newer version.'
  })

  function setNewerVersionAside(): void {
    setAsideFor.value = latestVersionId.value
    moveError.value = null
  }

  /**
   * The newer version is offered again after "Not now": a change to the fill spots was asked for, and the
   * document has to move first, so the way to move it has to be there.
   */
  function offerNewerVersionAgain(): void {
    setAsideFor.value = null
  }

  /** Moves the document to the newest version; the words to report when it moved, or null when it did not (moveError says why). */
  async function moveToLatest(): Promise<string | null> {
    const workspaceId = host.workspaceId()
    const document = host.document.value
    const latest = latestVersionId.value
    if (moving.value || workspaceId === undefined || !document || latest === null) return null
    moving.value = true
    moveError.value = null
    try {
      const unsaved = await host.saveTypingFirst()
      if (unsaved !== null) {
        moveError.value = unsaved
        return null
      }
      // The names of values the move may drop, read while this version still has them.
      const labels = new Map(host.definitions.value.map((field) => [field.fieldId, host.labelOf(field.fieldId)]))
      const current = host.document.value ?? document
      const moved = await moveDocumentToTemplateVersion(workspaceId, host.documentId(), current.currentRevision.id, latest, crypto.randomUUID())
      await host.reload()
      return movedWords((moved.droppedFieldIds ?? []).map((fieldId) => labels.get(fieldId) ?? host.labelOf(fieldId)))
    } catch (error) {
      moveError.value = (await refuse(error, 'move')).message
      return null
    } finally {
      moving.value = false
    }
  }

  return { change, undo, behindLatest, newerVersionWords, setNewerVersionAside, offerNewerVersionAgain, moveToLatest, moving, moveError }
}
