import type { PatchProposalResponse } from '@/api/client'

/**
 * Showing what a proposal would do before it is approved: the values it carries, laid out the way the page keeps
 * the document's own drafts, so the page can draw them as a preview in the spots they would fill.
 */

/** The values the page shows, by field: a text for a field with one value, one text per row for a repeated field. */
export type PreviewDrafts = Record<string, string | string[]>

export interface PreviewRules {
  /** The fields the page has a spot for; a value for any other field has nowhere to show. */
  fieldIds: ReadonlySet<string>
  /** Fields approving would leave as they are: locked, or typed into since the proposal was made. `repeated` says the field is a table column. */
  held: (fieldId: string, repeated: boolean) => boolean
  /** What the page shows for a field now; a proposed value that matches it is already in the document. */
  shown: (fieldId: string) => string | string[] | undefined
}

function same(proposed: string | string[], shown: string | string[] | undefined): boolean {
  if (typeof proposed === 'string') return typeof shown === 'string' && shown.trim() === proposed.trim()
  return Array.isArray(shown) && shown.length === proposed.length && proposed.every((value, index) => shown[index]!.trim() === value.trim())
}

/**
 * The part of a proposal that is not in the document yet and that approving would write: every proposed value
 * except those the page cannot show, those it would leave alone, and those the document already holds.
 */
export function previewDraftsFrom(proposal: PatchProposalResponse, rules: PreviewRules): PreviewDrafts {
  const drafts: PreviewDrafts = {}
  for (const [fieldId, proposed] of Object.entries(proposal.proposedValues)) {
    const repeated = proposed.cardinality === 'REPEATED'
    if (!rules.fieldIds.has(fieldId) || rules.held(fieldId, repeated)) continue
    const value = repeated ? [...(proposed.values ?? [])] : (proposed.value ?? '')
    const blank = typeof value === 'string' ? value.trim() === '' : value.length === 0
    if (blank || same(value, rules.shown(fieldId))) continue
    drafts[fieldId] = value
  }
  return drafts
}

/** How many fill spots a preview covers: each field with one value is one, and a table of rows is one however many columns it has. */
export function countPreviewSpots(drafts: PreviewDrafts | null): number {
  const values = Object.values(drafts ?? {})
  return values.filter((value) => typeof value === 'string').length + (values.some((value) => Array.isArray(value)) ? 1 : 0)
}
