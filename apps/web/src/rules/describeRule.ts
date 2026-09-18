import type { RulePayloadRequest, RuleScopeRequest } from '@/api/client'

/** Plain-language rendering of a rule's scope and payload, shared by template teaching and the document's read-only Rules tab. */
export function describeScope(scope: RuleScopeRequest): string {
  return scope.kind === 'WHOLE_TEMPLATE' ? 'Whole template' : `Field: ${scope.fieldId}`
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

export function describePayload(payload: RulePayloadRequest): string {
  switch (payload.kind) {
    case 'REQUIRED_FIELDS':
      return `Require: ${(payload.fieldIds ?? []).join(', ')}`
    case 'MAX_TEXT_LENGTH':
      return `${payload.fieldId}: at most ${payload.maxCharacters} characters`
    case 'MAX_ITEM_COUNT':
      return `${payload.fieldId}: at most ${payload.maxItems} items`
    case 'ALLOWED_SECTION_ORDER':
      return `Section order: ${(payload.orderedSectionIds ?? []).join(' → ')}`
    case 'DATE_DISPLAY_FORMAT':
      return `${payload.fieldId}: show dates as ${DATE_FORMAT_LABELS[payload.dateFormatStyle ?? 'LONG']}`
    case 'ALLOWED_SOURCE_KINDS':
      return `${payload.fieldId}: only allow values from ${(payload.allowedSourceKinds ?? []).join(', ').toLowerCase()}`
    case 'MISSING_VALUE_BEHAVIOR':
      return `${payload.fieldId}: when empty, ${EMPTY_VALUE_LABELS[payload.emptyValueResolution ?? 'BLANK']}`
    case 'ALLOWED_OVERFLOW_BEHAVIOR':
      return `${payload.fieldId}: on overflow, ${OVERFLOW_LABELS[payload.overflowResolution ?? 'BLOCK_EXPORT']}`
    case 'REPEATABLE_REGION_EMPTY_BEHAVIOR':
      return `${payload.fieldId}: when no items, ${EMPTY_VALUE_LABELS[payload.emptyValueResolution ?? 'BLANK']}`
    case 'PROTECTED_REGION':
      return `Protect content control "${payload.protectedRegionTarget?.tag ?? ''}" from edits`
    default:
      return payload.kind
  }
}
