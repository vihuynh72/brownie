import { ApiRequestError } from '@/api/client'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import { codePointLength } from '@/workspace/anchors'
import { fieldLabel } from '@/workspace/layout'

/*
 * What the page says about changing a form's fill spots from an open document: the rules a new name
 * must meet, what is happening while a change is made, what was done, why a change was refused, and
 * how a newer version of the form differs from the one a document is on. Pure words, so each can be
 * checked alone.
 */

/**
 * What is being done to a fill spot, or to the document's version of its form. `change` is any other
 * change to a spot, such as a box on a PDF page moved or its text size changed.
 */
export type SpotAction = 'add' | 'rename' | 'remove' | 'change' | 'move'

// ---- Names -----------------------------------------------------------------------------------------

/** The longest name a fill spot can have, in characters. */
export const MAX_SPOT_LABEL = 60

/** A name as it is sent: composed, with every run of spaces one space and none at either end. */
export function tidySpotLabel(label: string): string {
  return label.normalize('NFC').replace(/[\s\u00a0]+/gu, ' ').trim()
}

/** Why a name cannot be a fill spot's name, in words that say what to do; null when it can be. */
export function spotLabelProblem(label: string): string | null {
  const tidy = tidySpotLabel(label)
  if (tidy === '') return 'Give the fill spot a name.'
  if (/[\r\n\u2028\u2029]/u.test(label)) return 'Keep the name on one line.'
  if (codePointLength(tidy) > MAX_SPOT_LABEL) return `Keep the name to ${MAX_SPOT_LABEL} characters or fewer.`
  if (!/[\p{L}\p{N}]/u.test(tidy)) return 'Use at least one letter or number in the name.'
  return null
}

// ---- While a change is made, and after --------------------------------------------------------------

/** The status line while a change is sent; a change to the form's file is checked by printing it. */
export function workingWords(action: SpotAction): string {
  switch (action) {
    case 'add':
      return 'Adding the fill spot… Brownie is checking the form still prints correctly.'
    case 'rename':
      return 'Renaming the fill spot…'
    case 'remove':
      return 'Removing the fill spot… Brownie is checking the form still prints correctly.'
    case 'change':
      return 'Changing the fill spot… Brownie is checking the form still prints correctly.'
    case 'move':
      return 'Moving this document to the newest version of its form…'
  }
}

/** Said when others are still on the version this document left, so nobody expects them to have changed. */
function othersWords(others: number): string {
  if (others <= 0) return ''
  return others === 1
    ? ' One other document from this form keeps the earlier version until you move it.'
    : ` ${others} other documents from this form keep the earlier version until you move them.`
}

/** The chat line after a spot was added from the page. */
export function addedWords(label: string, where: string, others = 0): string {
  return `Added a fill spot for ${label} ${where}. New documents from this form will have it too.${othersWords(others)}`
}

export function renamedWords(before: string, after: string, others = 0): string {
  return `Renamed the fill spot ${before} to ${after}. New documents from this form will use the new name too.${othersWords(others)}`
}

export function removedWords(label: string, others = 0): string {
  return `Removed the fill spot ${label}. Its value stays in the version history. New documents from this form will not have it.${othersWords(others)}`
}

/** The question before a spot is removed; a spot that came with the form keeps its own box in the file. */
export function removeQuestion(label: string, hasValue: boolean, fromTheForm: boolean): string[] {
  const words = [`Remove the fill spot ${label}?${hasValue ? ' Its value stays in the version history.' : ''}`]
  if (fromTheForm) words.push('The form keeps its own box; Brownie just stops filling it.')
  return words
}

/** What Undo said it did. */
export function undoneWords(action: Exclude<SpotAction, 'move'>, label: string): string {
  switch (action) {
    case 'add':
      return `Undone: ${label} is no longer a fill spot.`
    case 'rename':
      return `Undone: the fill spot has its earlier name again.`
    case 'remove':
      return `Undone: the fill spot ${label} is back.`
    case 'change':
      return `Undone: the change to ${label} is taken back.`
  }
}

/** After a move to the newest version, which values it had no place for and so dropped. */
export function movedWords(droppedLabels: readonly string[]): string {
  const moved = 'Moved this document to the newest version of its form.'
  if (droppedLabels.length === 0) return moved
  const names = listWords(droppedLabels)
  return droppedLabels.length === 1
    ? `${moved} ${names} has no fill spot in it, so its value was dropped. It stays in the version history.`
    : `${moved} ${names} have no fill spot in it, so their values were dropped. They stay in the version history.`
}

function listWords(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

// ---- Refusals -------------------------------------------------------------------------------------

/** Why a change was not made, and whether the page must load the document again before anything else. */
export interface SpotRefusal {
  message: string
  /** The page is out of date: the document, its form or the page's lines changed. */
  reload: boolean
  /** The revision on screen is no longer current; the page's usual reload message applies. */
  staleRevision: boolean
}

const PLACE_WORDS: Record<string, string> = {
  HEADER_FOOTER: 'Brownie fills only the body of the form, not its header or footer.',
  REPEATING_REGION: 'This part of the form repeats for each row, so a single fill spot cannot go here.',
  INSIDE_LINK: 'Brownie cannot put a fill spot inside a link. Choose a place next to it.',
  INSIDE_FIELD_CODE: 'Brownie cannot put a fill spot inside a page number or another Word field.',
  PROTECTED: 'That part of the form is kept as it is, so a fill spot cannot go there. Choose a place next to it.',
  NOT_REACHABLE: 'Brownie cannot fill in that place when it prints the form. Choose another place.',
}

const STALE_PLACE = 'The page changed; select the place again.'

const VERB: Record<SpotAction, string> = {
  add: 'add the fill spot',
  rename: 'rename the fill spot',
  remove: 'remove the fill spot',
  change: 'change the fill spot',
  move: 'move this document',
}

/** The field a refusal names, when it names one. */
function problemFieldId(error: ApiRequestError): string | null {
  const fieldId = (error.problem as { fieldId?: unknown } | undefined)?.fieldId
  return typeof fieldId === 'string' && fieldId !== '' ? fieldId : null
}

/**
 * Why a change to a fill spot (or a move to the newest version of the form) was not made, in the
 * page's own words for every refusal it can explain, and the server's words for the rest.
 */
export function spotRefusal(error: unknown, action: SpotAction, labelOf: (fieldId: string) => string): SpotRefusal {
  const plain = (message: string, reload = false): SpotRefusal => ({ message, reload, staleRevision: false })
  if (brownieSaysNotThere(error)) {
    return plain('That was not done: this document is no longer available, for example because it was moved to the trash.')
  }
  if (!(error instanceof ApiRequestError)) {
    return plain(describeCommonFailure(error, 'a way to change fill spots') ?? `Brownie could not ${VERB[action]}. Try again.`)
  }
  if (error.status === 412) {
    return { message: 'This document changed since you loaded it, so it was reloaded. Try again on the current version.', reload: true, staleRevision: true }
  }
  const code = error.problem?.code
  switch (code) {
    case 'FILL_SPOT_ANCHOR_STALE':
      return plain(STALE_PLACE, true)
    case 'FILL_SPOT_PLACE_NOT_ALLOWED': {
      const reason = error.problem?.reason ?? ''
      if (reason === 'NOT_FOUND') return plain(STALE_PLACE, true)
      return plain(PLACE_WORDS[reason] ?? error.problem?.detail ?? 'A fill spot cannot go there. Choose another place.')
    }
    case 'TEMPLATE_VERSION_MOVED_ON':
      return plain('This form changed while you were working, so it was reloaded. Try again.', true)
    case 'DOCUMENT_TEMPLATE_VERSION_MOVED':
      return plain('This document is on an older version of its form. Move it to the newest version first, then try again.', true)
    case 'DOCUMENT_ALREADY_ON_VERSION':
      return plain('This document is on the newest version of its form already.', true)
    case 'FILL_SPOT_LOCKED': {
      const fieldId = problemFieldId(error)
      return plain(fieldId ? `Unlock ${labelOf(fieldId)} first; its value would be lost.` : (error.problem?.detail ?? 'Unlock the fill spot first; its value would be lost.'))
    }
    case 'FILL_SPOT_WOULD_NOT_PRINT':
      return plain(
        action === 'remove'
          ? 'Brownie could not remove the fill spot, because the form would not print correctly without it. Nothing was changed.'
          : `Brownie could not ${VERB[action]}, because the form would not print correctly with it. Nothing was changed.`,
      )
  }
  const common = describeCommonFailure(error, 'a way to change fill spots')
  if (common) return plain(common)
  if ((error.status === 400 || error.status === 409 || error.status === 422) && error.problem?.detail) return plain(error.problem.detail)
  return plain(`Brownie could not ${VERB[action]}. Try again.`)
}

// ---- A newer version of the form ------------------------------------------------------------------

interface Definition {
  fieldId: string
  label?: string | null
}

/**
 * How the form's newest version differs from the one this document is on, worked out field by field:
 * "It adds the fill spot "Company"." Empty when no fill spot was added, taken away or renamed (the
 * newer version changed something else, such as where a spot sits).
 */
export function versionDifferenceWords(current: readonly Definition[], latest: readonly Definition[]): string {
  const before = new Map(current.map((field) => [field.fieldId, fieldLabel(field)]))
  const after = new Map(latest.map((field) => [field.fieldId, fieldLabel(field)]))
  const quoted = (labels: string[]) => listWords(labels.map((label) => `"${label}"`))
  const added = [...after].filter(([fieldId]) => !before.has(fieldId)).map(([, label]) => label)
  const removed = [...before].filter(([fieldId]) => !after.has(fieldId)).map(([, label]) => label)
  const renamed = [...after]
    .filter(([fieldId, label]) => before.has(fieldId) && before.get(fieldId) !== label)
    .map(([fieldId, label]) => `"${before.get(fieldId)}" to "${label}"`)
  const parts: string[] = []
  if (added.length > 0) parts.push(`adds the fill ${added.length === 1 ? 'spot' : 'spots'} ${quoted(added)}`)
  if (removed.length > 0) parts.push(`takes away ${quoted(removed)}`)
  if (renamed.length > 0) parts.push(`renames ${listWords(renamed)}`)
  if (parts.length === 0) return ''
  return `It ${listWords(parts)}.`
}
