import { computed, ref, type Ref } from 'vue'
import { ApiRequestError, keepFillSpot, keepFillSpots } from '@/api/client'
import { describeCommonFailure } from '@/api/failures'
import type { EditableField } from '@/workspace/layout'

/**
 * The places Brownie found in a form by itself, which the person has not
 * yet said are right. Each is marked on the page until it is kept, one at a
 * time ("Keep" in the bar about it) or all at once ("Keep all"), or renamed (removing one
 * takes it away, mark and all). Keeping is recorded on the template, so it
 * holds for every document made from the form and for its later versions;
 * the template version says which places are kept.
 */
export function useFoundSpots(options: {
  fields: Ref<readonly EditableField[]>
  workspaceId: () => number | undefined
  templateId: () => number | null
  labelOf: (fieldId: string) => string
  announce: (text: string) => void
}) {
  /** What the template version said was kept, and what was kept on this page since: a slower reload never brings a mark back. */
  const acceptedFromServer = ref<ReadonlySet<string>>(new Set())
  const keptHere = ref<ReadonlySet<string>>(new Set())
  let keptHereFor: number | null = null
  /** The field being kept, or "all"; null when nothing is under way. */
  const keepPending = ref<string | null>(null)
  const keepError = ref<string | null>(null)

  /** Brownie found these itself, in the page's order. */
  const foundFieldIds = computed(() => options.fields.value.filter((field) => field.origin === 'FOUND_BY_BROWNIE').map((field) => field.fieldId))
  /** Found and not yet kept: the ones still marked. */
  const uncheckedFieldIds = computed(() =>
    foundFieldIds.value.filter((fieldId) => !acceptedFromServer.value.has(fieldId) && !keptHere.value.has(fieldId)),
  )
  const uncheckedSet = computed<ReadonlySet<string>>(() => new Set(uncheckedFieldIds.value))

  /** What the template version says has been kept; called whenever the version is loaded. */
  function setAccepted(templateId: number | null, fieldIds: readonly string[] | null | undefined): void {
    if (templateId !== keptHereFor) {
      keptHere.value = new Set()
      keptHereFor = templateId
    }
    acceptedFromServer.value = new Set(fieldIds ?? [])
  }

  function remember(fieldIds: readonly string[]): void {
    keptHereFor = options.templateId()
    keptHere.value = new Set([...keptHere.value, ...fieldIds])
  }

  function failure(error: unknown, what: string): string {
    const common = describeCommonFailure(error, 'a way to keep the places Brownie found')
    if (common) return `${what} ${common}`
    const detail = error instanceof ApiRequestError && error.status < 500 ? error.problem?.detail : undefined
    return `${what} ${detail ?? 'Try again.'}`
  }

  /** Keeps one place; true when it was kept. */
  async function keep(fieldId: string): Promise<boolean> {
    const workspaceId = options.workspaceId()
    const templateId = options.templateId()
    if (workspaceId === undefined || templateId === null || keepPending.value !== null) return false
    keepPending.value = fieldId
    keepError.value = null
    try {
      await keepFillSpot(workspaceId, templateId, fieldId)
      remember([fieldId])
      options.announce(`Kept ${options.labelOf(fieldId)}.`)
      return true
    } catch (error) {
      keepError.value = failure(error, `Could not keep ${options.labelOf(fieldId)}.`)
      return false
    } finally {
      keepPending.value = null
    }
  }

  /**
   * A place the person renamed is one they have checked, so it is kept as well. The rename's own words say
   * what was done, so this says nothing; if it cannot be recorded, the mark stays and "Keep" with it.
   */
  async function keepRenamed(fieldId: string): Promise<void> {
    const workspaceId = options.workspaceId()
    const templateId = options.templateId()
    if (workspaceId === undefined || templateId === null || !uncheckedSet.value.has(fieldId)) return
    try {
      await keepFillSpot(workspaceId, templateId, fieldId)
      remember([fieldId])
    } catch {
      // Left marked: the person can still keep it from the bar about it.
    }
  }

  /** Keeps every place still marked, in one request: all are kept, or none is. True when they were. */
  async function keepAll(): Promise<boolean> {
    const workspaceId = options.workspaceId()
    const templateId = options.templateId()
    const fieldIds = [...uncheckedFieldIds.value]
    if (workspaceId === undefined || templateId === null || keepPending.value !== null || fieldIds.length === 0) return false
    keepPending.value = 'all'
    keepError.value = null
    try {
      await keepFillSpots(workspaceId, templateId, fieldIds)
      remember(fieldIds)
      options.announce(fieldIds.length === 1 ? `Kept ${options.labelOf(fieldIds[0]!)}.` : `Kept all ${fieldIds.length} places Brownie found.`)
      return true
    } catch (error) {
      keepError.value = failure(error, 'Could not keep the places Brownie found, so none was kept.')
      return false
    } finally {
      keepPending.value = null
    }
  }

  return { uncheckedFieldIds, uncheckedSet, keepPending, keepError, setAccepted, keep, keepRenamed, keepAll }
}
