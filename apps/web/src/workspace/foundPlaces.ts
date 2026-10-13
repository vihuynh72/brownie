import type { EditableField } from '@/workspace/layout'

/**
 * How many places Brownie found, said the way the page draws them: a table of rows is one place however many
 * columns the form lists for it, so the line over the page and the page's own count agree.
 */

/** The places among these fields: each field with one value, and all the table's columns together as one. */
function placeCount(fields: readonly Pick<EditableField, 'cardinality'>[]): number {
  return fields.filter((field) => field.cardinality !== 'REPEATED').length + (fields.some((field) => field.cardinality === 'REPEATED') ? 1 : 0)
}

/** How a place still to check is marked, which says how to tell it: a label on a Word page, a dashed outline on a PDF's own page. */
const CHECK_WORDS = {
  DOCX: { one: 'Check the one marked Found by Brownie, then keep it.', many: 'Check the ones marked Found by Brownie, then keep them.' },
  PDF: { one: 'Check the one with a dashed outline, then keep it.', many: 'Check the ones with a dashed outline, then keep them.' },
} as const

/**
 * The line over the page while any place Brownie found is unchecked: how many it found, or how many of them are
 * left once some are kept, and how to tell which they are. Where the form had places of its own, the ones Brownie
 * found are more beside them. Empty when nothing is left to check.
 */
export function foundPlacesWords(options: {
  /** Every field of the form. */
  fields: readonly EditableField[]
  /** The fields Brownie found that are not kept yet. */
  unchecked: ReadonlySet<string>
  kind: 'DOCX' | 'PDF'
}): string {
  const found = options.fields.filter((field) => field.origin === 'FOUND_BY_BROWNIE')
  const unchecked = placeCount(found.filter((field) => options.unchecked.has(field.fieldId)))
  if (unchecked === 0) return ''
  const total = placeCount(found)
  const check = CHECK_WORDS[options.kind][unchecked === 1 ? 'one' : 'many']
  if (unchecked < total) return `${unchecked} of the ${total} places Brownie found ${unchecked === 1 ? 'is' : 'are'} not checked yet. ${check}`
  const formHasItsOwn = options.fields.some((field) => (field.origin ?? 'FORM') === 'FORM')
  return `Brownie found ${total}${formHasItsOwn ? ' more' : ''} ${total === 1 ? 'place' : 'places'} to fill in. ${check}`
}
