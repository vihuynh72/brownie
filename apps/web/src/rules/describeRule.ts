import type { RulePayloadRequest, RuleScopeRequest } from '@/api/client'

/** Plain-language rendering of a rule's scope and payload, shared by template teaching and the document's read-only Rules tab. */
export function describeScope(scope: RuleScopeRequest): string {
  if (scope.kind === 'WHOLE_TEMPLATE') return 'Whole template'
  return scope.fieldId ? `Field: ${scope.fieldId}` : 'One field'
}

const DATE_FORMAT_LABELS: Record<string, string> = {
  LONG: 'long (September 10, 2026)',
  SHORT: 'short (9/10/2026)',
  ISO: 'ISO (2026-09-10)',
}
const EMPTY_VALUE_LABELS: Record<string, string> = {
  OMIT: 'omit it',
  BLANK: 'leave it blank',
  PLACEHOLDER_TEXT: 'show placeholder text',
}
const OVERFLOW_LABELS: Record<string, string> = {
  BLOCK_EXPORT: 'block export',
  ALLOW_REFLOW: 'allow the layout to reflow',
}
/** A source of kind ARTIFACT is a file someone uploaded, which is what a person calls it. */
const SOURCE_KIND_LABELS: Record<string, string> = {
  ARTIFACT: 'uploaded files',
  GOOGLE_CALENDAR: 'events copied from Google Calendar',
  GOOGLE_DRIVE: 'files copied from Google Drive',
}

/**
 * The server always names the style, resolution or source kind a rule uses, but a newer server can name one this
 * page has no words for. Each of those rules is then described without the unknown part, so the sentence stays true
 * and never shows "undefined" or the server's own code for it.
 */
export function describePayload(payload: RulePayloadRequest): string {
  switch (payload.kind) {
    case 'REQUIRED_FIELDS': {
      const fieldIds = payload.fieldIds ?? []
      return fieldIds.length > 0 ? `Require: ${fieldIds.join(', ')}` : 'Require fields to be filled in'
    }
    case 'MAX_TEXT_LENGTH':
      return forField(payload.fieldId, atMost(payload.maxCharacters, 'character', 'characters') ?? 'a length limit')
    case 'MAX_ITEM_COUNT':
      return forField(payload.fieldId, atMost(payload.maxItems, 'item', 'items') ?? 'a limit on how many items')
    case 'ALLOWED_SECTION_ORDER': {
      const sectionIds = payload.orderedSectionIds ?? []
      return sectionIds.length > 0 ? `Section order: ${sectionIds.join(' → ')}` : 'Keep sections in a set order'
    }
    case 'DATE_DISPLAY_FORMAT': {
      const format = labelFor(DATE_FORMAT_LABELS, payload.dateFormatStyle)
      return forField(payload.fieldId, format ? `show dates as ${format}` : 'show dates in one set format')
    }
    case 'ALLOWED_SOURCE_KINDS': {
      const sources = sourceWords(payload.allowedSourceKinds)
      return forField(payload.fieldId, sources ? `only allow values from ${sources}` : 'limit where its values may come from')
    }
    case 'MISSING_VALUE_BEHAVIOR': {
      const resolution = labelFor(EMPTY_VALUE_LABELS, payload.emptyValueResolution)
      return forField(payload.fieldId, resolution ? `when empty, ${resolution}` : 'a set way to handle it when empty')
    }
    case 'ALLOWED_OVERFLOW_BEHAVIOR': {
      const resolution = labelFor(OVERFLOW_LABELS, payload.overflowResolution)
      return forField(payload.fieldId, resolution ? `on overflow, ${resolution}` : 'a set way to handle overflow')
    }
    case 'REPEATABLE_REGION_EMPTY_BEHAVIOR': {
      const resolution = labelFor(EMPTY_VALUE_LABELS, payload.emptyValueResolution)
      return forField(payload.fieldId, resolution ? `when no items, ${resolution}` : 'a set way to handle having no items')
    }
    case 'PROTECTED_REGION': {
      // A region can also be named by its place in the document's structure, which has no name a person would know.
      const tag = payload.protectedRegionTarget?.tag?.trim()
      return tag ? `Protect content control "${tag}" from edits` : 'Protect a region of the template from edits'
    }
    default:
      return 'A kind of rule this page cannot describe'
  }
}

/** "meeting.title: at most 5 items"; without a field name the description stands on its own instead of reading "undefined: ...". */
function forField(fieldId: string | null | undefined, text: string): string {
  if (fieldId) return `${fieldId}: ${text}`
  return text.charAt(0).toUpperCase() + text.slice(1)
}

function atMost(count: unknown, one: string, many: string): string | null {
  if (typeof count !== 'number' || !Number.isInteger(count) || count < 1) return null
  return `at most ${count} ${count === 1 ? one : many}`
}

function labelFor(labels: Record<string, string>, value: string | null | undefined): string | null {
  return value != null && Object.hasOwn(labels, value) ? labels[value] : null
}

/** Null when the list is empty or names a kind this page has no words for, since leaving one out would narrow the rule. */
function sourceWords(kinds: readonly string[] | null | undefined): string | null {
  const words: string[] = []
  for (const kind of kinds ?? []) {
    const word = labelFor(SOURCE_KIND_LABELS, kind)
    if (word === null) return null
    if (!words.includes(word)) words.push(word)
  }
  if (words.length === 0) return null
  if (words.length === 1) return words[0]
  return `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`
}
