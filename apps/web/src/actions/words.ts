import { ApiRequestError, type ActionResponse, type CapabilitiesResponse, type ConnectorAccess, type DriveSaveKind } from '@/api/client'
import { formatBytes } from '@/capabilities'
import { describeConnectorFailure } from '@/connections/words'

/**
 * Everything the pages say about a change Brownie makes in a person's Google
 * account once they approve it: what it will do, where it stands, and why it
 * did not happen. What it will do is read from the very payload the person
 * approves, never from anything else the page knows, so that what is shown is
 * what the approval covers.
 */

export type ActionType = ActionResponse['type']
export type ActionState = ActionResponse['state']

/** Which changes this Brownie makes, or null when the server did not say (one from before such changes existed). */
export function offeredActions(capabilities: CapabilitiesResponse | null): ActionType[] | null {
  const offered = capabilities?.googleActions
  return Array.isArray(offered) ? offered : null
}

export const DRIVE_SAVE_TYPES: readonly ActionType[] = ['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC']
/** Everything the Google Drive panel lists: saves, and additions to a Google Doc a save made. */
export const DRIVE_PANEL_TYPES: readonly ActionType[] = [...DRIVE_SAVE_TYPES, 'GOOGLE_DOC_APPEND']

export function isDriveSave(action: ActionResponse): boolean {
  return DRIVE_SAVE_TYPES.includes(action.type)
}

/** A save to Google Drive exactly as its payload states it. */
export interface DriveSavePreview {
  kind: DriveSaveKind
  fileName: string
  bytes: number
  /** The exported file's fingerprint, which tells a save of the very same file from another. */
  sha256: string
  accountEmail: string | null
  documentTitle: string
}

function objectOf(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null
}

/**
 * The save a payload describes, or null when it is not one this page can
 * state in full: a payload that puts the file anywhere but the top of My
 * Drive, shares it, or is of a shape this page does not know. A save the page
 * cannot state is never offered for approval.
 */
export function driveSavePreview(action: ActionResponse): DriveSavePreview | null {
  const payload = objectOf(action.payload)
  const document = objectOf(payload?.document)
  const account = objectOf(payload?.account)
  const target = objectOf(payload?.target)
  const content = objectOf(payload?.content)
  const effect = objectOf(payload?.effect)
  if (payload === null || document === null || account === null || target === null || content === null || effect === null) {
    return null
  }
  if (payload.schema !== 'brownie.action/1' || payload.type !== action.type || !isDriveSave(action)) {
    return null
  }
  if (target.place !== 'MY_DRIVE_TOP' || target.sharing !== 'NOBODY' || effect.creates !== 'NEW_FILE') {
    return null
  }
  const conversion = action.type === 'DRIVE_SAVE_AS_GOOGLE_DOC'
  if (effect.conversion !== (conversion ? 'GOOGLE_DOC' : 'NONE')) {
    return null
  }
  const format = content.format
  if ((format !== 'DOCX' && format !== 'PDF') || (conversion && format !== 'DOCX')) {
    return null
  }
  const { fileName, bytes, sha256 } = content
  const email = account.email ?? null
  const title = document.title
  if (typeof fileName !== 'string' || fileName === '' || typeof bytes !== 'number' || !Number.isInteger(bytes) || bytes < 0) {
    return null
  }
  if (typeof sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(sha256)) {
    return null
  }
  if ((email !== null && typeof email !== 'string') || typeof title !== 'string') {
    return null
  }
  return {
    kind: conversion ? 'GOOGLE_DOC' : format === 'PDF' ? 'PDF_FILE' : 'WORD_FILE',
    fileName,
    bytes,
    sha256,
    accountEmail: email,
    documentTitle: title,
  }
}

/** The choices a person picks from, as the page names them. */
export const DRIVE_SAVE_CHOICES: Record<DriveSaveKind, string> = {
  WORD_FILE: 'The Word file (.docx), exactly as exported',
  PDF_FILE: 'The PDF file, exactly as exported',
  GOOGLE_DOC: 'A Google Doc, converted by Google from the Word file',
}

/** What approving makes, in a sentence. */
export function driveSaveWhat(preview: DriveSavePreview): string {
  const size = formatBytes(preview.bytes)
  switch (preview.kind) {
    case 'WORD_FILE':
      return `A new Word file named "${preview.fileName}"${size ? `, ${size}` : ''}: exactly the file you exported.`
    case 'PDF_FILE':
      return `A new PDF file named "${preview.fileName}"${size ? `, ${size}` : ''}: exactly the file you exported.`
    case 'GOOGLE_DOC':
      return (
        `A new Google Doc named "${preview.fileName}", which Google makes from the Word file you exported. ` +
        "Google's conversion can change how it looks: layout, tables and lists may not come out the same."
      )
  }
}

/** Where it goes, in a sentence. */
export function driveSaveWhere(preview: DriveSavePreview): string {
  const account = preview.accountEmail ? `the Google Drive of ${preview.accountEmail}` : 'your Google Drive'
  return `At the top of My Drive in ${account}, shared with no one.`
}

/** What Brownie does after sending it, in a sentence. */
export function driveSaveAfterwards(preview: DriveSavePreview): string {
  return preview.kind === 'GOOGLE_DOC'
    ? 'When you approve, Brownie sends the Word file, then reads back only this Google Doc: to check it is where it should be, ' +
        'and to look for your filled-in values in its text.'
    : 'When you approve, Brownie sends the file, then reads back only this file, to check it arrived exactly as approved.'
}

const FAILURES: Record<NonNullable<ActionResponse['failure']>, string> = {
  CONNECTION_CHANGED: 'The Google connection changed after this save was prepared, so nothing was saved. Prepare it again.',
  DOCUMENT_GONE: 'The document was moved to the trash, so nothing was saved.',
  DOCUMENT_CHANGED:
    'The document changed after this save was prepared, so nothing was saved. Export it again, then prepare the save again.',
  EXPORT_CHANGED:
    'The document was exported again after this save was prepared, so nothing was saved. Prepare it again from the latest export.',
  TARGET_CHANGED: 'What this would have changed is no longer as it was when it was prepared, so nothing was saved.',
  CONTENT_CHANGED:
    'The exported file is no longer exactly the one prepared, so nothing was saved. Export again, then prepare the save again.',
  PROVIDER_REFUSED: 'Google refused this, so nothing was saved.',
  STORAGE_FULL: 'Your Google Drive has no room left, so nothing was saved. Make room there, then prepare the save again.',
  BLOCKED_BY_ORGANIZATION: 'The organization that manages this Google account does not allow this, so nothing was saved.',
  PERMISSION_REFUSED: 'Google did not let Brownie save there, so nothing was saved.',
  TARGET_UNAVAILABLE: 'What this would have changed is no longer there, so nothing was saved.',
  LIMIT_REACHED: "Google's limit on requests was reached, so nothing was saved. Prepare the save again later.",
  READBACK_MISMATCH:
    'Google made a file, but when Brownie read it back it was not exactly what you approved, or it had been moved, renamed ' +
    'or put in the trash since. Look at it in your Google Drive: Brownie did not change or remove it.',
}

const timeFormatter = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' })
const exactTimeFormatter = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', second: '2-digit' })

/** A time of day from the server, or null for anything that is not one. */
export function timeOf(value: string | null | undefined): string | null {
  if (!value) {
    return null
  }
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : timeFormatter.format(parsed)
}

/** A time of day to the second, which tells apart two things prepared in the same minute. */
export function exactTimeOf(value: string | null | undefined): string | null {
  if (!value) {
    return null
  }
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : exactTimeFormatter.format(parsed)
}

/** A change waiting for approval, or approved and not made, which the person can still withdraw. */
export function withdrawable(action: ActionResponse): boolean {
  return action.state === 'AWAITING_APPROVAL' || action.state === 'APPROVED'
}

/**
 * Whether the person can say they looked for themselves: an outcome Brownie
 * cannot know, or an attempt that stopped without finishing and can no
 * longer be asked about through the account that made it.
 */
export function mayAcknowledge(action: ActionResponse): boolean {
  if (action.state === 'OUTCOME_UNKNOWN') return !action.outcomeAcknowledged
  return (action.state === 'EXECUTING' || action.state === 'RECONCILING') && action.attemptStopped === true
}

/**
 * Where one save stands, in one or two sentences, read only from the action
 * itself. A save whose outcome is unknown says so, and says it was not sent
 * again: the one thing that must never be guessed is that it did not happen.
 */
export function driveSaveSentence(action: ActionResponse, preview: DriveSavePreview | null): string {
  const name = preview ? `"${preview.fileName}"` : 'this file'
  const Name = preview ? `"${preview.fileName}"` : 'This file'
  switch (action.state) {
    case 'AWAITING_APPROVAL': {
      const until = timeOf(action.expiresAt)
      return `Ready to save ${name}. Nothing is saved until you approve it${until ? `, which you can do until ${until}` : ''}.`
    }
    case 'APPROVED': {
      const until = timeOf(action.approvalExpiresAt)
      return `${Name} was not saved: it did not reach Google, or Google turned it away for now. You can try again${
        until ? ` until ${until}` : ''
      }, or cancel.`
    }
    case 'EXECUTING':
      return `Brownie is saving ${name}, or was when this page last asked. Check again to see where it stands.`
    case 'RECONCILING':
      return `Brownie is asking Google what became of ${name}.`
    case 'SUCCEEDED':
      if (action.verification === 'REMOVED_AFTERWARDS') {
        return `${Name} was saved to your Google Drive, and has since been deleted there.`
      }
      return preview?.kind === 'GOOGLE_DOC' ? `Saved ${name} to your Google Drive as a Google Doc.` : `Saved ${name} to your Google Drive.`
    case 'FAILED':
      return (action.failure ? FAILURES[action.failure] : undefined) ?? 'This save did not happen.'
    case 'OUTCOME_UNKNOWN':
      if (!action.sent) {
        return `Nothing was sent for ${name}, so it was not saved. The try that stopped is closed.`
      }
      if (action.outcomeAcknowledged) {
        return `You said you looked in your Google Drive for ${name}. Brownie still cannot tell whether it was saved.`
      }
      return (
        `Brownie cannot tell yet whether ${name} was saved as you approved it: Google's answer did not arrive, or did not ` +
        'say enough. Brownie did not send it again. ' +
        (preview?.kind === 'GOOGLE_DOC'
          ? 'Ask Google what happened, or look for it in your Google Drive.'
          : 'Ask Google what happened; if Google still cannot say, look for it in your Google Drive.')
      )
    case 'CANCELLED':
      return `Cancelled. ${Name} was not saved.`
    case 'EXPIRED':
      return action.approvedAt
        ? `The approval ran out before ${name} could be saved, so nothing was saved.`
        : `This was not approved in time, so nothing was saved.`
    default:
      return ''
  }
}

/**
 * What reading a converted Google Doc back found: how many of the filled-in
 * values Brownie looked for are in its text. Formatting is never compared,
 * and the sentence says so.
 */
export function conversionSentence(check: ActionResponse['conversionCheck']): string | null {
  if (check === null || check === undefined) {
    return null
  }
  const notCompared = 'Layout, tables, lists and formatting were not compared.'
  if (check.total === 0) {
    return `No filled-in value was long enough to look for in the Google Doc's text. ${notCompared}`
  }
  if (check.found === check.total) {
    return check.total === 1
      ? `Brownie found the filled-in value in the Google Doc's text. ${notCompared}`
      : `Brownie found all ${check.total} filled-in values in the Google Doc's text. ${notCompared}`
  }
  return (
    `Brownie found ${check.found} of ${check.total} filled-in values in the Google Doc's text; the others are not there as ` +
    `written, so compare the Google Doc with the Word file. ${notCompared}`
  )
}

/** The page at Google that opens what a change made, only ever as an https address on Google's own pages for it. */
export function actionLink(action: ActionResponse): string | null {
  const link = action.externalLink
  if (typeof link !== 'string') {
    return null
  }
  try {
    const url = new URL(link)
    if (url.protocol !== 'https:' || url.username !== '' || url.password !== '') {
      return null
    }
    const googlePage =
      url.hostname === 'drive.google.com' ||
      url.hostname === 'docs.google.com' ||
      url.hostname === 'calendar.google.com' ||
      (url.hostname === 'www.google.com' && url.pathname.startsWith('/calendar/'))
    return googlePage ? url.href : null
  } catch {
    return null
  }
}

/** An addition to a Google Doc exactly as its payload states it. */
export interface DocAppendPreview {
  /** The save that made the Doc. */
  savedBy: number
  /** The version of the document whose text is added. */
  documentRevision: number
  docTitle: string
  text: string
  shared: boolean
  accountEmail: string | null
}

/** How long a text is as a person counts it: one for each character, however the browser stores it. */
export function characterCount(text: string): number {
  return [...text].length
}

/**
 * The addition a payload describes, or null when it is not one this page can
 * state in full. An addition the page cannot state is never offered for
 * approval.
 */
export function docAppendPreview(action: ActionResponse): DocAppendPreview | null {
  const payload = objectOf(action.payload)
  const account = objectOf(payload?.account)
  const target = objectOf(payload?.target)
  const content = objectOf(payload?.content)
  const effect = objectOf(payload?.effect)
  if (payload === null || account === null || target === null || content === null || effect === null) return null
  if (payload.schema !== 'brownie.action/1' || payload.type !== 'GOOGLE_DOC_APPEND' || action.type !== 'GOOGLE_DOC_APPEND') return null
  if (target.kind !== 'SAVED_GOOGLE_DOC' || target.place !== 'END_OF_FIRST_TAB' || effect.appends !== 'TEXT' || effect.onlyIfUnchanged !== true) {
    return null
  }
  const document = objectOf(payload.document)
  const { savedBy, title, shared } = target
  const { text } = content
  const email = account.email ?? null
  const documentRevision = document?.revision
  if (typeof savedBy !== 'number' || typeof title !== 'string' || typeof shared !== 'boolean') return null
  if (typeof documentRevision !== 'number') return null
  if (typeof text !== 'string' || !text.startsWith('\n') || (email !== null && typeof email !== 'string')) return null
  return { savedBy, documentRevision, docTitle: title, text: text.slice(1), shared, accountEmail: email }
}

const APPEND_FAILURES: Record<NonNullable<ActionResponse['failure']>, string> = {
  CONNECTION_CHANGED: 'The Google connection changed after this addition was prepared, so nothing was added. Prepare it again.',
  DOCUMENT_GONE: 'The document was moved to the trash, so nothing was added.',
  DOCUMENT_CHANGED: 'The document changed after this addition was prepared, so nothing was added. Export it again, then prepare it again.',
  EXPORT_CHANGED: 'The document was exported again after this addition was prepared, so nothing was added. Prepare it again.',
  TARGET_CHANGED:
    'The Google Doc changed after this addition was prepared: someone edited it, shared it differently, or moved it to the ' +
    'trash, or it is no longer one Brownie saved for you. Nothing was added. Prepare it again to add to the Doc as it is now.',
  CONTENT_CHANGED: 'The text to add is no longer exactly the text prepared, so nothing was added. Prepare it again.',
  PROVIDER_REFUSED: 'Google refused this addition, so nothing was added.',
  STORAGE_FULL: 'Google refused this addition, so nothing was added.',
  BLOCKED_BY_ORGANIZATION: 'The organization that manages this Google account does not allow this, so nothing was added.',
  PERMISSION_REFUSED: 'Google did not let Brownie add to that Google Doc, so nothing was added.',
  TARGET_UNAVAILABLE: 'That Google Doc is no longer there, so nothing was added.',
  LIMIT_REACHED: "Google's limit on requests was reached, so nothing was added. Prepare it again later.",
  READBACK_MISMATCH:
    'Google took the addition, but when Brownie read the Doc back it did not end with exactly the text you approved. Look at ' +
    'the end of the Doc: Brownie did not change or remove anything there.',
}

/** Where one addition stands; an unknown outcome is said as unknown, and says it was not sent again. */
export function docAppendSentence(action: ActionResponse, preview: DocAppendPreview | null): string {
  const doc = preview ? `"${preview.docTitle}"` : 'the Google Doc'
  switch (action.state) {
    case 'AWAITING_APPROVAL': {
      const until = timeOf(action.expiresAt)
      return `Ready to add the document's text, as exported when this was prepared, to the end of ${doc}. Nothing is added until you approve it${
        until ? `, which you can do until ${until}` : ''
      }.`
    }
    case 'APPROVED': {
      const until = timeOf(action.approvalExpiresAt)
      return `Nothing was added to ${doc}: the addition did not reach Google, or Google turned it away for now. You can try again${
        until ? ` until ${until}` : ''
      }, or cancel.`
    }
    case 'EXECUTING':
      return `Brownie is adding the text to ${doc}, or was when this page last asked. Check again to see where it stands.`
    case 'RECONCILING':
      return `Brownie is reading ${doc} to see what became of the addition.`
    case 'SUCCEEDED':
      return (
        `Added the document's text, as exported when this was prepared, to the end of ${doc}. Brownie read the Doc back: ` +
        "the text is at the end of its first tab, and the rest of that tab's text is as it was."
      )
    case 'FAILED':
      return (action.failure ? APPEND_FAILURES[action.failure] : undefined) ?? 'Nothing was added.'
    case 'OUTCOME_UNKNOWN':
      if (!action.sent) {
        return `Nothing was sent for this addition, so nothing was added to ${doc}. The try that stopped is closed.`
      }
      if (action.outcomeAcknowledged) {
        return `You said you looked at ${doc}. Brownie still cannot tell whether the text was added.`
      }
      return (
        `Brownie cannot tell yet whether the text was added to ${doc}: Google's answer did not arrive, or the Doc has changed in ` +
        'some other way since, or the same text was added again since. Brownie did not send it again. Ask Google what happened, ' +
        'or look at the end of the Doc.'
      )
    case 'CANCELLED':
      return 'Cancelled. Nothing was added.'
    case 'EXPIRED':
      return action.approvedAt
        ? 'The approval ran out before the text could be added, so nothing was added.'
        : 'This was not approved in time, so nothing was added.'
    default:
      return ''
  }
}

/** Whether a save still needs something of the person, so it stays on the list however old it is. */
export function needsAttention(action: ActionResponse): boolean {
  switch (action.state) {
    case 'AWAITING_APPROVAL':
    case 'APPROVED':
    case 'EXECUTING':
    case 'RECONCILING':
      return true
    case 'OUTCOME_UNKNOWN':
      return !action.outcomeAcknowledged
    default:
      return false
  }
}

/** Why a change could not be prepared, where the reason is the same whatever the change. */
const NOT_PROPOSABLE: Record<string, string> = {
  NO_EXPORT: 'This document has not been exported yet. Brownie saves the exported file, so export it first.',
  EXPORT_STALE: 'The document changed after it was last exported. Export it again first.',
  FORMAT_NOT_EXPORTED: 'The latest export does not include that file. Approve and export that format first.',
  FILE_TOO_LARGE: 'The exported file is larger than Brownie saves to Google Drive.',
}

/**
 * Codes that mean the approval sent nothing and changed nothing: refused
 * before any change could leave. Any other failure of an approval may have
 * come after the change was sent, so the page reads the action again rather
 * than say what happened. "Not found" is not among them: a save whose
 * document was deleted just after it was sent is not found either.
 */
const NOTHING_SENT = new Set([
  'MALFORMED_REQUEST',
  'FORBIDDEN',
  'ACTION_PAYLOAD_MISMATCH',
  'ACTION_NOT_OFFERED',
  'ACTION_SIBLING_UNRESOLVED',
  'CONNECTION_RECONNECT_REQUIRED',
  'CONNECTION_NOT_FOUND',
  'CONNECTOR_PROVIDER_UNAVAILABLE',
  'CONNECTOR_MISCONFIGURED',
  'CONNECTOR_NOT_CONFIGURED',
  'CONNECTOR_BLOCKED_BY_ORGANIZATION',
  'STORAGE_UNAVAILABLE',
  'RATE_LIMITED',
])

/**
 * Whether a request to prepare a change certainly recorded nothing: refused
 * with Brownie's own answer before anything was kept, or asked of a server
 * that has no such route. Any other failure may have come after the
 * proposal was kept, so the list is read again rather than say none was.
 */
export function proposalRecordedNothing(error: unknown): boolean {
  if (!(error instanceof ApiRequestError)) {
    return false
  }
  return error.routeMissing || approvalSentNothing(error) || (error.problem !== undefined && error.status >= 400 && error.status < 500)
}

export function approvalSentNothing(error: unknown): boolean {
  if (!(error instanceof ApiRequestError) || error.routeMissing) {
    return false
  }
  // A session that ended is refused before any route runs.
  if (error.status === 401) {
    return true
  }
  return error.problem !== undefined && (error.status === 429 || NOTHING_SENT.has(error.problem.code))
}

/** How the failures of one kind of change are worded, so each kind's page speaks of what it does. */
export interface ActionKindWords {
  access: ConnectorAccess
  notOffered: string
  cannot: string
  mismatch: string
  sibling: string
  connectionUnusable: string
  accessNotOffered: string
  notFound: string
  hidden: string
  /** Why this kind could not be prepared, where it differs from what any change would say. */
  notProposable?: Record<string, string>
}

export const DRIVE_SAVE_WORDS: ActionKindWords = {
  access: 'DRIVE_SAVING',
  notOffered: 'This Brownie does not save to Google Drive at the moment.',
  cannot: 'This cannot be saved to Google Drive as it is.',
  mismatch: 'What was approved is not what this save would do, so it was not approved. Look at it again.',
  sibling: 'The same save is under way, or an earlier try may already have made it. Check that one first.',
  connectionUnusable: 'Brownie can only ask about this with the Google account that made it. Connect that account for saving again to ask.',
  accessNotOffered: 'This Brownie does not save to Google Drive, so there is nothing to connect for it.',
  notFound: 'This document, or this save, is no longer available.',
  hidden: "The document's title holds characters that reorder text or cannot be seen, and Brownie does not send those as a file name.",
}

export const DOC_APPEND_WORDS: ActionKindWords = {
  access: 'DRIVE_SAVING',
  notOffered: 'This Brownie does not add text to Google Docs at the moment.',
  cannot: 'This text cannot be added to that Google Doc as it is.',
  mismatch: 'What was approved is not what this addition would do, so it was not approved. Look at it again.',
  sibling: 'The same text is being added to this Google Doc, or an earlier try may already have added it. Check that one first.',
  connectionUnusable:
    'Brownie can only ask about this with the Google account that saved the Doc. Connect that account for saving again to ask.',
  accessNotOffered: 'This Brownie does not save to Google Drive, so there is nothing to connect for it.',
  notFound: 'This document, the save that made the Google Doc, or this addition is no longer available.',
  hidden:
    "This version's text holds characters that reorder it or cannot be seen, and Brownie does not send those. Remove them " +
    'from the filled-in values, then prepare it again.',
  notProposable: {
    NO_EXPORT: 'This document has not been exported yet. Brownie adds the text of the exported version, so export it first.',
  },
}

/**
 * The sentence for a failed request about a change. Brownie's own codes come
 * first; everything else is worded as for any Google connection. A proposal
 * refused for a reason only the server can put precisely (which value, which
 * time) is said in the server's own words.
 */
export function describeActionFailure(error: unknown, what: string, kind: ActionKindWords = DRIVE_SAVE_WORDS): string | null {
  if (error instanceof ApiRequestError && !error.routeMissing) {
    switch (error.problem?.code) {
      case 'ACTION_NOT_PROPOSABLE': {
        const reason = error.problem.reason ?? ''
        if ((reason === 'INVALID' || reason === 'TIME_SKIPPED') && error.problem.detail) {
          return error.problem.detail
        }
        return reason === 'HIDDEN_CHARACTERS' ? kind.hidden : (kind.notProposable?.[reason] ?? NOT_PROPOSABLE[reason] ?? kind.cannot)
      }
      case 'ACTION_NOT_OFFERED':
        return kind.notOffered
      case 'ACTION_PAYLOAD_MISMATCH':
        return kind.mismatch
      case 'ACTION_SIBLING_UNRESOLVED':
        return kind.sibling
      case 'ACTION_CONNECTION_UNUSABLE':
        return kind.connectionUnusable
      case 'CONNECTION_ACCESS_NOT_OFFERED':
        return kind.accessNotOffered
      case 'FORBIDDEN':
        return 'Your role here does not allow changes in connected accounts.'
      case 'NOT_FOUND':
        return kind.notFound
      default:
        break
    }
  }
  return describeConnectorFailure(error, what, kind.access)
}
