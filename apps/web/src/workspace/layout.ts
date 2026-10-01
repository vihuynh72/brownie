import type {
  FieldStateResponse,
  TemplateLayoutBlockResponse,
  TemplateLayoutInlineResponse,
  TemplateLayoutPartResponse,
  TemplateLayoutResponse,
  TemplateLayoutRowResponse,
  TemplateLayoutStyleResponse,
} from '@/api/client'

/*
 * Pure helpers behind the document page: how a template's own styles become page styles, what the
 * Rules card says about a fill spot's text style, and how the template's structure becomes the page
 * the person fills in. Nothing here touches the network or the DOM, so every rule can be tested alone.
 */

/** One field the page can edit: from the template version's definitions, or from the revision when those are unavailable. */
export interface EditableField {
  fieldId: string
  type: 'TEXT' | 'DATE'
  cardinality: 'SCALAR' | 'REPEATED'
  requiredness: 'REQUIRED' | 'OPTIONAL' | null
  /** The name the form gives the field ("Date of birth"); null or absent means it is worked out from the id. */
  label?: string | null
  /** Who placed the spot; null or absent means it came with the form. */
  origin?: 'FORM' | 'FOUND_BY_BROWNIE' | 'ADDED_BY_PERSON' | null
}

/** A Vue style object. Every value is built from checked parts, so none can carry a second declaration or a URL. */
export type CssStyle = Record<string, string>

/** Word's own default body size, 11 pt, which the page draws at 1rem. */
export const DEFAULT_BASE_HALF_POINTS = 22

/** A human label from a stable field id: "action.item.due" reads as "Action item due". For a field whose definition is at hand, use fieldLabel. */
export function labelFor(fieldId: string): string {
  const words = fieldId.replace(/[._-]+/g, ' ').trim()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/**
 * The name a person sees for a field: the label stored with it (the form's own words, in any language),
 * or else the one worked out from its id. A server that predates stored labels sends none, and a field
 * known only from a revision has none, so both read as they always did.
 */
export function fieldLabel(field: { fieldId: string; label?: string | null }): string {
  const label = field.label?.trim()
  return label ? label : labelFor(field.fieldId)
}

// ---- Styles ------------------------------------------------------------------------------------

/*
 * A font name reaches the page only when it is plain words: letters, digits, spaces and hyphens. That
 * covers real family names ("Liberation Sans", "Noto Serif CJK-JP") and rules out anything that could
 * close the quoted name and add a declaration of its own, whatever a template file says.
 */
const SAFE_FONT_FAMILY = /^[\p{L}\p{N} -]+$/u
const HEX_COLOUR = /^[0-9A-Fa-f]{6}$/
/** Word's largest font size is 1638 pt; anything past it is not a size a template can hold. */
const MAX_HALF_POINTS = 3276

function validHalfPoints(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 && value <= MAX_HALF_POINTS ? value : null
}

/** The generic family a browser falls back to when the template's own font is not installed on this device. */
function genericFamilyFor(family: string): 'serif' | 'sans-serif' | 'monospace' {
  const name = family.toLowerCase()
  if (/mono|courier|consolas|menlo/.test(name)) return 'monospace'
  if (/sans/.test(name)) return 'sans-serif'
  if (/serif|times|georgia|garamond|cambria|roman|book/.test(name)) return 'serif'
  return 'sans-serif'
}

/**
 * The font names a template gives for some text, first choice first. A copy made from another format can name
 * several, separated by semicolons ("Liberation Serif;Times New Roman"), the first being the one it uses.
 */
function fontNames(family: string | null | undefined): string[] {
  return (family ?? '')
    .split(';')
    .map((name) => name.trim().replace(/\s+/g, ' '))
    .filter((name) => name !== '')
    .slice(0, 4)
}

/**
 * A CSS font-family list for a template's font names, each quoted, with a generic fallback that suits the
 * first; null when any of them is not plain words.
 */
export function cssFontFamily(family: string | null | undefined): string | null {
  const names = fontNames(family)
  if (names.length === 0 || names.some((name) => name.length > 64 || !SAFE_FONT_FAMILY.test(name))) return null
  return `${names.map((name) => `"${name}"`).join(', ')}, ${genericFamilyFor(names[0]!)}`
}

function round(value: number): number {
  return Math.round(value * 10000) / 10000
}

/**
 * The page style of a template run. Sizes are in em against the page's base size, so a heading keeps
 * its proportion to the body text however large the page is drawn. A property the template never set
 * stays unset here, and the run inherits the page's own.
 */
export function styleToCss(style: TemplateLayoutStyleResponse | null | undefined, baseHalfPoints: number | null): CssStyle {
  const css: CssStyle = {}
  if (!style) return css
  if (style.bold === true) css.fontWeight = '700'
  if (style.italic === true) css.fontStyle = 'italic'
  if (style.underline === true) css.textDecorationLine = 'underline'
  const family = cssFontFamily(style.fontFamily)
  if (family) css.fontFamily = family
  const size = validHalfPoints(style.fontSizeHalfPoints)
  if (size !== null) {
    const base = validHalfPoints(baseHalfPoints) ?? DEFAULT_BASE_HALF_POINTS
    // Bounded so one odd run cannot shrink to nothing or swallow the page.
    css.fontSize = `${round(Math.min(8, Math.max(0.25, size / base)))}em`
  }
  if (typeof style.colorHex === 'string' && HEX_COLOUR.test(style.colorHex)) css.color = `#${style.colorHex.toLowerCase()}`
  return css
}

/** What the Rules card says about a text style. */
export interface StyleChips {
  font: string | null
  size: string | null
  weight: 'Regular' | 'Bold'
  italic: boolean
  underline: boolean
}

/** The chips for a text style; a weight the template never set reads as Regular, which is what Word draws. */
export function describeStyle(style: TemplateLayoutStyleResponse | null | undefined): StyleChips {
  const halfPoints = validHalfPoints(style?.fontSizeHalfPoints)
  return {
    font: fontNames(style?.fontFamily)[0] ?? null,
    size: halfPoints === null ? null : `${halfPoints / 2} pt`,
    weight: style?.bold === true ? 'Bold' : 'Regular',
    italic: style?.italic === true,
    underline: style?.underline === true,
  }
}

// ---- Walking a layout --------------------------------------------------------------------------

function partsOf(layout: TemplateLayoutResponse | null | undefined, kind?: TemplateLayoutPartResponse['kind']): TemplateLayoutPartResponse[] {
  const parts = layout?.parts ?? []
  return kind ? parts.filter((part) => part.kind === kind) : parts
}

/** Every inline of some blocks in reading order, table cells included. */
function inlinesOf(blocks: readonly TemplateLayoutBlockResponse[] | null | undefined): TemplateLayoutInlineResponse[] {
  const result: TemplateLayoutInlineResponse[] = []
  for (const block of blocks ?? []) {
    if (block.kind === 'TABLE') {
      for (const row of block.rows ?? []) {
        for (const cell of row.cells ?? []) result.push(...inlinesOf(cell.blocks))
      }
    } else {
      result.push(...(block.inlines ?? []))
    }
  }
  return result
}

function inlinesOfParts(parts: readonly TemplateLayoutPartResponse[]): TemplateLayoutInlineResponse[] {
  return parts.flatMap((part) => inlinesOf(part.blocks))
}

/** The value seen most often, the earliest one on a tie; null when there are none. */
function mostCommon<T>(values: readonly T[], keyOf: (value: T) => string): T | null {
  const counts = new Map<string, { value: T; count: number }>()
  let best: { value: T; count: number } | null = null
  for (const value of values) {
    const key = keyOf(value)
    const entry = counts.get(key) ?? { value, count: 0 }
    entry.count += 1
    counts.set(key, entry)
    if (!best || entry.count > best.count) best = entry
  }
  return best?.value ?? null
}

function styleKey(style: TemplateLayoutStyleResponse): string {
  return JSON.stringify([
    style.bold ?? null,
    style.italic ?? null,
    style.underline ?? null,
    style.fontFamily ?? null,
    style.fontSizeHalfPoints ?? null,
    style.colorHex ?? null,
  ])
}

const PDF_FONT_NAMES = { SANS: 'Liberation Sans', SERIF: 'Liberation Serif', MONO: 'Liberation Mono' } as const

/**
 * A PDF form's boxes in the words of a Word style, for the Rules card: the font Brownie writes the box
 * in, its size and weight. One of the PDF's own fields has no style here (the form sets its look).
 */
function pdfSpotStyles(layout: TemplateLayoutResponse | null | undefined): { fieldId: string; style: TemplateLayoutStyleResponse | null }[] {
  return (layout?.pdf?.spots ?? []).map((spot) => ({
    fieldId: spot.fieldId,
    style: spot.style
      ? { fontFamily: PDF_FONT_NAMES[spot.style.font] ?? PDF_FONT_NAMES.SANS, fontSizeHalfPoints: Math.round(spot.style.sizePt * 2), bold: spot.style.bold }
      : null,
  }))
}

/** The style most fill spots share: what the Rules card shows while no fill spot is selected. */
export function dominantFillSpotStyle(layout: TemplateLayoutResponse | null | undefined): TemplateLayoutStyleResponse | null {
  const styles = layout?.kind === 'PDF'
    ? pdfSpotStyles(layout).flatMap((spot) => (spot.style ? [spot.style] : []))
    : inlinesOfParts(partsOf(layout))
        .filter((inline) => inline.kind === 'FILL_SPOT' && inline.style)
        .map((inline) => inline.style!)
  return mostCommon(styles, styleKey)
}

/** The style a field's value takes when filled: its first fill spot's, in reading order. */
export function fillSpotStyle(layout: TemplateLayoutResponse | null | undefined, fieldId: string): TemplateLayoutStyleResponse | null {
  if (layout?.kind === 'PDF') return pdfSpotStyles(layout).find((spot) => spot.fieldId === fieldId)?.style ?? null
  const spot = inlinesOfParts(partsOf(layout)).find((inline) => inline.kind === 'FILL_SPOT' && inline.fieldId === fieldId)
  return spot?.style ?? null
}

/** The body text size of the document: the size most of its main text and fill spots use. */
export function pageBaseHalfPoints(layout: TemplateLayoutResponse | null | undefined): number {
  const sizes = inlinesOfParts(partsOf(layout, 'MAIN_DOCUMENT'))
    .filter((inline) => inline.kind === 'TEXT' || inline.kind === 'FILL_SPOT')
    .map((inline) => validHalfPoints(inline.style?.fontSizeHalfPoints))
    .filter((size): size is number => size !== null)
  return mostCommon(sizes, String) ?? DEFAULT_BASE_HALF_POINTS
}

/**
 * The sheet's own font and size: the template's body font (the fill spots' font, else its main text's),
 * and a base size that draws the template's 11 pt at the app's 1rem.
 */
export function pageSheetCss(layout: TemplateLayoutResponse | null | undefined): CssStyle {
  if (!layout) return {}
  const css: CssStyle = {}
  const families = inlinesOfParts(partsOf(layout, 'MAIN_DOCUMENT'))
    .filter((inline) => inline.kind === 'TEXT')
    .map((inline) => inline.style?.fontFamily)
    .filter((family): family is string => typeof family === 'string' && family.trim() !== '')
  const family = cssFontFamily(dominantFillSpotStyle(layout)?.fontFamily) ?? cssFontFamily(mostCommon(families, String))
  if (family) css.fontFamily = family
  css.fontSize = `${round(pageBaseHalfPoints(layout) / DEFAULT_BASE_HALF_POINTS)}rem`
  return css
}

// ---- Dates -------------------------------------------------------------------------------------

/** How a filled date reads in the exported document. */
export const DATE_DISPLAY_EXAMPLE = 'September 28, 2026'

const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
]

function daysInMonth(year: number, month: number): number {
  if (month === 2) return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0 ? 29 : 28
  return [4, 6, 9, 11].includes(month) ? 30 : 31
}

/**
 * A stored date (YYYY-MM-DD) the way the exported document prints it: the full month name, the day
 * without a leading zero, and a four-digit year, in US English ("September 28, 2026"). Anything that
 * is not a calendar date comes back unchanged, so a caller can show whatever the field holds.
 */
export function formatDateLikeExport(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!match) return iso
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) return iso
  // The export prints the year of the era, so the ISO year 0 (1 BC) prints as 0001.
  const yearOfEra = String(year === 0 ? 1 : year).padStart(4, '0')
  return `${MONTHS[month - 1]} ${day}, ${yearOfEra}`
}

// ---- Field states ------------------------------------------------------------------------------

const STATE_WORDS: { [Dimension in keyof FieldStateResponse]: Record<string, string> } = {
  authorship: { IMPORTED: 'Imported', AI_COMPOSED: 'From Brownie', USER_AUTHORED: 'Typed by you', MIXED: 'Brownie and you' },
  evidenceSupport: {
    DIRECT: 'Source cited',
    TRANSFORMED: 'Source cited, reworded',
    AMBIGUOUS: 'Evidence unclear',
    UNSUPPORTED: 'Not in the source',
    MISSING: 'No source cited',
  },
  validation: { PASSED: 'Checks passed', WARNING: 'Check warning', BLOCKING: 'Blocks export', UNAVAILABLE: 'Not checked' },
  review: { UNREVIEWED: 'Not reviewed', ACCEPTED: 'Accepted', REJECTED: 'Rejected', NEEDS_CLARIFICATION: 'Needs clarification' },
  lock: { PRESERVE_ON_REGENERATION: 'Kept on regeneration', EXPLICITLY_LOCKED: 'Locked' },
}

/**
 * The words for a field's state, leaving out what only restates the obvious (an unchecked field, an
 * unlocked field, no source for a value the person typed). A value this page has no words for is left
 * out rather than shown as the server's constant.
 */
export function fieldStateWords(state: FieldStateResponse | null | undefined): string[] {
  if (!state) return []
  const words: Array<string | undefined> = [STATE_WORDS.authorship[state.authorship]]
  if (!(state.evidenceSupport === 'MISSING' && state.authorship === 'USER_AUTHORED')) {
    words.push(STATE_WORDS.evidenceSupport[state.evidenceSupport])
  }
  if (state.validation !== 'NOT_RUN') words.push(STATE_WORDS.validation[state.validation])
  words.push(STATE_WORDS.review[state.review])
  if (state.lock !== 'EDITABLE') words.push(STATE_WORDS.lock[state.lock])
  return words.filter((word): word is string => Boolean(word))
}

/** A value someone has to look at again: it blocks export, or a reviewer rejected it or asked about it. */
export function stateNeedsAttention(state: FieldStateResponse | null | undefined): boolean {
  return state?.validation === 'BLOCKING' || state?.review === 'REJECTED' || state?.review === 'NEEDS_CLARIFICATION'
}

/** A value Brownie wrote, alone or together with the person. */
export function stateFromAssist(state: FieldStateResponse | null | undefined): boolean {
  return state?.authorship === 'AI_COMPOSED' || state?.authorship === 'MIXED'
}

// ---- Text entry --------------------------------------------------------------------------------

/*
 * Every kind of line break a paste can carry: CR, LF, vertical tab (Word's manual line break), form
 * feed, and the Unicode line and paragraph separators, built from char codes so the source stays plain.
 */
const UNICODE_SEPARATORS = String.fromCharCode(0x2028, 0x2029)
const LINE_BREAK_RUN = new RegExp(`[ \\t]*(?:\\r\\n|[\\r\\n\\v\\f${UNICODE_SEPARATORS}])+[ \\t]*`, 'g')
const HAS_LINE_BREAK = new RegExp(`[\\r\\n\\v\\f${UNICODE_SEPARATORS}]`)

/** True when a text holds a line break of any kind. */
export function hasLineBreak(text: string): boolean {
  return HAS_LINE_BREAK.test(text)
}

/**
 * A fill spot's value is one line in the exported document (the filler writes it as a single run of
 * text), so each run of line breaks, with the spaces around it, becomes one space.
 */
export function normalizeLineBreaks(text: string): string {
  return text.replace(LINE_BREAK_RUN, ' ')
}

// ---- The page model ----------------------------------------------------------------------------

/** A place on the page where the person types a value. */
export interface PageSpot {
  kind: 'spot'
  key: string
  fieldId: string
  /** The repeated item this spot edits; null for a scalar field. */
  rowIndex: number | null
  /** The control's element id: edit-<fieldId> (or edit-<fieldId>-<row>) for the first place a value appears, --2, --3... after. */
  inputId: string
  placeholder: string | null
  style: TemplateLayoutStyleResponse | null
}

export interface PageText {
  kind: 'text'
  key: string
  text: string
  style: TemplateLayoutStyleResponse | null
  /** Where the text starts in its paragraph's anchor text, in code points; null for text a place cannot be chosen in. */
  anchorStart: number | null
  /** The control the text is shown from, when it comes from one no field names. */
  controlNodeId: string | null
}

export interface PageImage {
  kind: 'image'
  key: string
}

export type PageInline = PageText | PageSpot | PageImage

export interface PageParagraph {
  kind: 'paragraph'
  key: string
  alignment: 'START' | 'CENTER' | 'END' | 'JUSTIFY' | null
  /** The list level of a numbered paragraph (0 is the outermost); null when it is not in a list. */
  listLevel: number | null
  inlines: PageInline[]
  /** Stands in for a repeating paragraph while there are no rows. */
  noRows: boolean
  /** The paragraph's node id in the layout's graph; null from a server that predates choosing places. */
  nodeId: string | null
  /** Whether a fill spot can be added in it (the body, outside the part that repeats). */
  anchorable: boolean
  /** The hash of its anchor text, which a place chosen in it sends back; null when it is not anchorable. */
  anchorTextHash: string | null
  /** The paragraph is, or is drawn inside, the part the filler repeats for each row. */
  repeats: boolean
}

export interface PageCell {
  key: string
  paragraphs: PageParagraph[]
}

export interface PageRow {
  key: string
  cells: PageCell[]
  /** More than zero: this row stands in for the repeating row while there are no rows, spanning that many columns. */
  noRowsColumns: number
}

export interface PageTable {
  kind: 'table'
  key: string
  rows: PageRow[]
}

/** Where the page's one "Add row" button goes: right after the rows it adds to. */
export interface PageAddRow {
  kind: 'add-row'
  key: string
}

export type PageBlock = PageParagraph | PageTable | PageAddRow

export interface PagePart {
  key: string
  blocks: PageBlock[]
}

/** Fields listed as "Label: value" lines and a rows table, for fields the drawn page does not show. */
export interface PageFieldList {
  scalars: PageSpot[]
  /** The repeated fields, one column each. */
  columns: string[]
  /** One entry per row, one spot per column. */
  rows: PageSpot[][]
  addRow: boolean
}

export interface PageModel {
  headers: PagePart[]
  main: PagePart[]
  /** On a drawn page, the fields it has no place for; without a layout, every field. Null when there are none. */
  list: PageFieldList | null
  footers: PagePart[]
  /** Every fill spot in reading order: headers, main document, the list, footers. */
  spots: PageSpot[]
}

function alignmentOf(value: unknown): PageParagraph['alignment'] {
  return value === 'START' || value === 'CENTER' || value === 'END' || value === 'JUSTIFY' ? value : null
}

function listLevelOf(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) return null
  return Math.min(value, 8)
}

function spot(key: string, fieldId: string, rowIndex: number | null, placeholder: string | null, style: TemplateLayoutStyleResponse | null): PageSpot {
  return { kind: 'spot', key, fieldId, rowIndex, inputId: '', placeholder, style }
}

function fieldList(scalars: readonly EditableField[], columns: readonly EditableField[], rowCount: number, addRow: boolean): PageFieldList | null {
  if (scalars.length === 0 && columns.length === 0) return null
  return {
    scalars: scalars.map((field) => spot(`list/${field.fieldId}`, field.fieldId, null, null, null)),
    columns: columns.map((field) => field.fieldId),
    rows: Array.from({ length: columns.length > 0 ? rowCount : 0 }, (_, row) =>
      columns.map((field) => spot(`list/r${row}/${field.fieldId}`, field.fieldId, row, null, null)),
    ),
    addRow: addRow && columns.length > 0,
  }
}

function blockSpots(blocks: readonly PageBlock[]): PageSpot[] {
  const result: PageSpot[] = []
  const fromParagraph = (paragraph: PageParagraph) => {
    for (const inline of paragraph.inlines) if (inline.kind === 'spot') result.push(inline)
  }
  for (const block of blocks) {
    if (block.kind === 'paragraph') fromParagraph(block)
    if (block.kind === 'table') {
      for (const row of block.rows) for (const cell of row.cells) cell.paragraphs.forEach(fromParagraph)
    }
  }
  return result
}

/**
 * Gives every spot its element id in reading order, so the first place a value appears is the one
 * "go to field" lands on, and returns the spots in that order.
 */
function assignInputIds(model: Omit<PageModel, 'spots'>): PageSpot[] {
  const ordered = [
    ...model.headers.flatMap((part) => blockSpots(part.blocks)),
    ...model.main.flatMap((part) => blockSpots(part.blocks)),
    ...(model.list ? [...model.list.scalars, ...model.list.rows.flat()] : []),
    ...model.footers.flatMap((part) => blockSpots(part.blocks)),
  ]
  const seen = new Map<string, number>()
  for (const item of ordered) {
    const target = `${item.fieldId}\n${item.rowIndex ?? ''}`
    const count = (seen.get(target) ?? 0) + 1
    seen.set(target, count)
    const base = item.rowIndex === null ? `edit-${item.fieldId}` : `edit-${item.fieldId}-${item.rowIndex}`
    item.inputId = count === 1 ? base : `${base}--${count}`
  }
  return ordered
}

/** The page for a document without a drawn layout: each field as a "Label: value" line, then the rows. */
export function buildFallbackModel(fields: readonly EditableField[], rowCount: number): PageModel {
  const list = fieldList(
    fields.filter((field) => field.cardinality === 'SCALAR'),
    fields.filter((field) => field.cardinality === 'REPEATED'),
    Math.max(0, Math.floor(rowCount)),
    true,
  )
  const model = { headers: [], main: [], list, footers: [] }
  return { ...model, spots: assignInputIds(model) }
}

/**
 * The page for a drawn layout. It follows what the filler does with the same template, so the page
 * never offers a place the export will not fill:
 * - a scalar field's fill spots all edit its one value;
 * - the repeating row or paragraph (the one the filler clones) is drawn once per row, its repeated
 *   fields editing that row's item; a repeated field's fill spot anywhere else keeps the template's own
 *   text in the export, so it is drawn as that text;
 * - a fill spot for a field this page cannot edit is drawn as the template's text;
 * - any field left without a place to edit it is listed after the main document.
 */
export function buildPageModel(layout: TemplateLayoutResponse, fields: readonly EditableField[], rowCount: number): PageModel {
  const fieldsById = new Map(fields.map((field) => [field.fieldId, field]))
  const hasRepeatedFields = fields.some((field) => field.cardinality === 'REPEATED')
  const rows = Math.max(0, Math.floor(rowCount))
  const placedScalars = new Set<string>()
  const placedRepeated = new Set<string>()
  let addRowPlaced = false

  function inlinesFor(source: TemplateLayoutInlineResponse[] | null | undefined, key: string, rowIndex: number | null): PageInline[] {
    const result: PageInline[] = []
    ;(source ?? []).forEach((inline, index) => {
      const inlineKey = `${key}/i${index}`
      const style = inline.style ?? null
      if (inline.kind === 'TEXT') {
        if (inline.text) {
          const anchorStart = typeof inline.anchorStart === 'number' && inline.anchorStart >= 0 ? inline.anchorStart : null
          result.push({ kind: 'text', key: inlineKey, text: inline.text, style, anchorStart, controlNodeId: inline.controlNodeId ?? null })
        }
      } else if (inline.kind === 'IMAGE') {
        result.push({ kind: 'image', key: inlineKey })
      } else if (inline.kind === 'FILL_SPOT') {
        const field = inline.fieldId ? fieldsById.get(inline.fieldId) : undefined
        const placeholder = inline.placeholder?.trim() || null
        if (field?.cardinality === 'SCALAR') {
          placedScalars.add(field.fieldId)
          result.push(spot(inlineKey, field.fieldId, null, placeholder, style))
        } else if (field?.cardinality === 'REPEATED' && rowIndex !== null) {
          result.push(spot(inlineKey, field.fieldId, rowIndex, placeholder, style))
        } else if (placeholder) {
          result.push({ kind: 'text', key: inlineKey, text: placeholder, style, anchorStart: null, controlNodeId: null })
        }
      }
    })
    return result
  }

  /** Records which repeated fields a repeating region edits, whether or not it has rows to draw yet. */
  function markRepeatedPlaced(blocks: readonly TemplateLayoutBlockResponse[] | null | undefined): void {
    for (const inline of inlinesOf(blocks)) {
      const field = inline.kind === 'FILL_SPOT' && inline.fieldId ? fieldsById.get(inline.fieldId) : undefined
      if (field?.cardinality === 'REPEATED') placedRepeated.add(field.fieldId)
    }
  }

  function paragraphFor(block: TemplateLayoutBlockResponse, key: string, rowIndex: number | null): PageParagraph {
    const repeats = rowIndex !== null || block.repeating === true
    const anchorable = block.anchorable === true && !repeats && typeof block.nodeId === 'string'
    return {
      kind: 'paragraph',
      key,
      alignment: alignmentOf(block.alignment),
      listLevel: listLevelOf(block.listLevel),
      inlines: inlinesFor(block.inlines, key, rowIndex),
      noRows: false,
      nodeId: block.nodeId ?? null,
      anchorable,
      anchorTextHash: anchorable ? (block.anchorTextHash ?? null) : null,
      repeats,
    }
  }

  function repeatParagraph(block: TemplateLayoutBlockResponse, key: string): PageParagraph[] {
    markRepeatedPlaced([block])
    if (rows === 0) {
      return [
        {
          kind: 'paragraph',
          key,
          alignment: alignmentOf(block.alignment),
          listLevel: null,
          inlines: [],
          noRows: true,
          nodeId: block.nodeId ?? null,
          anchorable: false,
          anchorTextHash: null,
          repeats: true,
        },
      ]
    }
    return Array.from({ length: rows }, (_, row) => paragraphFor(block, `${key}@${row}`, row))
  }

  function isRepeating(item: { repeating?: boolean }, main: boolean): boolean {
    // Only the main document's region is cloned by the filler, and only when there are repeated fields to fill it.
    return main && hasRepeatedFields && item.repeating === true
  }

  function cellParagraphs(
    blocks: readonly TemplateLayoutBlockResponse[] | null | undefined,
    key: string,
    rowIndex: number | null,
    main: boolean,
    region: { found: boolean },
  ): PageParagraph[] {
    const result: PageParagraph[] = []
    ;(blocks ?? []).forEach((block, index) => {
      const blockKey = `${key}/b${index}`
      if (block.kind === 'TABLE') {
        // A table inside a table is refused when a template is read, so none should arrive here; if
        // one does, its text is kept, flattened into this cell, rather than dropped.
        ;(block.rows ?? []).forEach((row, r) =>
          (row.cells ?? []).forEach((cell, c) =>
            result.push(...cellParagraphs(cell.blocks, `${blockKey}/r${r}/c${c}`, rowIndex, main, region)),
          ),
        )
      } else if (rowIndex === null && isRepeating(block, main)) {
        region.found = true
        result.push(...repeatParagraph(block, blockKey))
      } else {
        result.push(paragraphFor(block, blockKey, rowIndex))
      }
    })
    return result
  }

  function rowFor(row: TemplateLayoutRowResponse, key: string, rowIndex: number | null, main: boolean, region: { found: boolean }): PageRow {
    return {
      key,
      cells: (row.cells ?? []).map((cell, index) => ({
        key: `${key}/c${index}`,
        paragraphs: cellParagraphs(cell.blocks, `${key}/c${index}`, rowIndex, main, region),
      })),
      noRowsColumns: 0,
    }
  }

  function tableFor(block: TemplateLayoutBlockResponse, key: string, main: boolean): { table: PageTable; region: boolean } {
    const region = { found: false }
    const result: PageRow[] = []
    ;(block.rows ?? []).forEach((row, index) => {
      const rowKey = `${key}/r${index}`
      if (isRepeating(row, main)) {
        region.found = true
        markRepeatedPlaced((row.cells ?? []).flatMap((cell) => cell.blocks ?? []))
        if (rows === 0) {
          result.push({ key: rowKey, cells: [], noRowsColumns: Math.max(1, row.cells?.length ?? 0) })
        } else {
          for (let item = 0; item < rows; item++) result.push(rowFor(row, `${rowKey}@${item}`, item, main, region))
        }
      } else {
        result.push(rowFor(row, rowKey, null, main, region))
      }
    })
    return { table: { kind: 'table', key, rows: result }, region: region.found }
  }

  function partFor(part: TemplateLayoutPartResponse, key: string): PagePart {
    const main = part.kind === 'MAIN_DOCUMENT'
    const blocks: PageBlock[] = []
    const addRowAfter = (blockKey: string) => {
      if (addRowPlaced) return
      addRowPlaced = true
      blocks.push({ kind: 'add-row', key: `${blockKey}/add-row` })
    }
    ;(part.blocks ?? []).forEach((block, index) => {
      const blockKey = `${key}/b${index}`
      if (block.kind === 'TABLE') {
        const { table, region } = tableFor(block, blockKey, main)
        blocks.push(table)
        if (region) addRowAfter(blockKey)
      } else if (isRepeating(block, main)) {
        blocks.push(...repeatParagraph(block, blockKey))
        addRowAfter(blockKey)
      } else {
        blocks.push(paragraphFor(block, blockKey, null))
      }
    })
    return { key, blocks }
  }

  const parts = layout.parts ?? []
  const build = (kind: TemplateLayoutPartResponse['kind'], prefix: string) =>
    parts
      .map((part, index) => ({ part, index }))
      .filter(({ part }) => part.kind === kind)
      .map(({ part, index }) => partFor(part, `${prefix}${index}`))
  const headers = build('HEADER', 'h')
  const main = build('MAIN_DOCUMENT', 'm')
  const footers = build('FOOTER', 'f')

  const unplaced = new Set(layout.unplacedFieldIds ?? [])
  const listed = fields.filter((field) =>
    unplaced.has(field.fieldId) ||
    (field.cardinality === 'SCALAR' ? !placedScalars.has(field.fieldId) : !placedRepeated.has(field.fieldId)),
  )
  const list = fieldList(
    listed.filter((field) => field.cardinality === 'SCALAR'),
    listed.filter((field) => field.cardinality === 'REPEATED'),
    rows,
    !addRowPlaced,
  )
  const model = { headers, main, list, footers }
  return { ...model, spots: assignInputIds(model) }
}
