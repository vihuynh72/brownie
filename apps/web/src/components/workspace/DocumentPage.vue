<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import FillSpot from '@/components/workspace/FillSpot.vue'
import FillHereBar from '@/components/workspace/FillHereBar.vue'
import RowsLine from '@/components/workspace/RowsLine.vue'
import SpotContextMenu from '@/components/workspace/SpotContextMenu.vue'
import { codePointLength, type PagePoint } from '@/workspace/anchors'
import { usePageSelection } from '@/workspace/pageSelection'
import type { DocumentRevisionResponse, FieldStateResponse, TemplateLayoutResponse, TemplateLayoutStyleResponse } from '@/api/client'
import {
  buildFallbackModel,
  buildPageModel,
  fieldLabel,
  pageBaseHalfPoints,
  pageSheetCss,
  stateFromAssist,
  styleToCss,
  type CssStyle,
  type EditableField,
  type PageBlock,
  type PageFieldList,
  type PageInline,
  type PageParagraph,
  type PageRow,
  type PageSpot,
  type PageTable,
  type PageText,
} from '@/workspace/layout'

/**
 * The document as a page: the template's own text in its own styles, with a fill spot wherever a
 * value goes, so the person fills the document where it will read rather than in a form beside it.
 * Above the sheet a status line says how far along the page is; beside it a rail shows where every
 * fill spot sits and whether it still needs a value. Without a layout (an older server, a template
 * that could not be drawn, or while it loads) the same fields are listed as "Label: value" lines and
 * a rows table, so editing never waits on the drawing.
 *
 * The page holds no values of its own: every edit is reported to the parent, which owns the drafts,
 * the saving and the row actions (remove, move), and passes the values back down.
 */
const props = defineProps<{
  /** The template's drawn layout; null draws the field list instead. */
  layout: TemplateLayoutResponse | null
  layoutState: 'loading' | 'ready' | 'unavailable'
  /** Why the layout could not be drawn, when the page knows better than "the template" (an older server, a lost connection). */
  layoutProblem?: string | null
  fields: EditableField[]
  /** Scalar fields hold a string, repeated fields an array with one entry per row (all the same length). */
  drafts: Record<string, string | string[]>
  revisionFields: DocumentRevisionResponse['fields'] | null
  /** Fields required by their definition or by an accepted rule. */
  requiredFieldIds: ReadonlySet<string>
  /** Scalar fields that are locked. */
  lockedFieldIds: ReadonlySet<string>
  /** A repeated item is locked, so no row can be edited. */
  rowsLocked: boolean
  selected: { fieldId: string; rowIndex: number | null } | null
  /** Places Brownie found itself that the person has not yet said are right; each is marked as a place Brownie found. */
  foundFieldIds?: ReadonlySet<string>
  /** New fill spots can be added to this page: "Add a fill spot", and "Fill in here" on its text. */
  canAddSpots?: boolean
  /** A dialog about a new fill spot is over the page, so the "Fill in here" bar stands down. */
  fillHereHidden?: boolean
  /**
   * Values Brownie has proposed that are not in the document yet, shaped like `drafts`. Each shows in its spot as a
   * preview and nothing here is ever written back; null shows none.
   */
  previewDrafts?: Record<string, string | string[]> | null
  /** How many questions Brownie still has open about each field, by field id. */
  questionCounts?: Readonly<Record<string, number>>
}>()

defineSlots<{
  /** Whatever the screen shows between the status line and the sheet, such as a note about the form. */
  banner?: () => unknown
}>()

const emit = defineEmits<{
  'update-scalar': [fieldId: string, value: string]
  'update-row': [fieldId: string, rowIndex: number, value: string]
  select: [target: { fieldId: string; rowIndex: number | null }]
  /** The person asked, from a spot, for its review and lock controls. */
  'open-actions': [target: { fieldId: string; rowIndex: number | null }]
  'add-row': []
  /** The person asked to add a fill spot from the list of the form's lines. */
  'add-spot': []
  /** The person chose a place on the page for a new fill spot; `returnTo` takes focus back if they change their mind. */
  'fill-here': [point: PagePoint, returnTo: HTMLElement | null]
  /** The latest place selected or clicked on the page: where "here" is. */
  place: [point: PagePoint]
  /** Words on the page were selected and the "Fill in here" bar came up beside them; the bar about a spot gives way to it. */
  'words-selected': []
}>()

const headingId = useId()
const keysHintId = useId()
/** The key that reaches a spot's review and lock controls, in the words of the keyboard in use. */
const actionsKey = /Mac|iPhone|iPad/.test(window.navigator.platform ?? '') ? 'Option+Return' : 'Alt+Enter'
const BULLET = String.fromCharCode(0x2022)
const EMPTY_CSS: CssStyle = {}

const fieldsById = computed(() => new Map(props.fields.map((field) => [field.fieldId, field])))

/** The name the page shows for a field: the form's own label when the field has one. */
function labelOf(fieldId: string): string {
  return fieldLabel(fieldsById.value.get(fieldId) ?? { fieldId })
}

const hasRepeatedFields = computed(() => props.fields.some((field) => field.cardinality === 'REPEATED'))

/**
 * Rows are the repeated fields' items; the longest column decides, so no typed item is ever hidden. A
 * proposal that carries rows the document does not have yet draws them too, so they can be seen first.
 */
const rowCount = computed(() => {
  let count = 0
  for (const field of props.fields) {
    if (field.cardinality !== 'REPEATED') continue
    for (const source of [props.drafts, props.previewDrafts]) {
      const value = source?.[field.fieldId]
      if (Array.isArray(value)) count = Math.max(count, value.length)
    }
  }
  return count
})

const model = computed(() =>
  props.layout ? buildPageModel(props.layout, props.fields, rowCount.value) : buildFallbackModel(props.fields, rowCount.value),
)

/**
 * Rows the document itself has, not counting a proposal's own: "Add row" always adds to the document, so whether
 * it worked has to be answered from this, not from `rowCount`, which a pending proposal can inflate on its own.
 */
const realRowCount = computed(() => {
  let count = 0
  for (const field of props.fields) {
    if (field.cardinality !== 'REPEATED') continue
    const value = props.drafts[field.fieldId]
    if (Array.isArray(value)) count = Math.max(count, value.length)
  }
  return count
})

type Section =
  | { key: string; place: 'header' | 'main' | 'footer'; blocks: PageBlock[] }
  | { key: string; place: 'list'; list: PageFieldList }

/** Everything on the sheet in reading order; fields the drawing has no place for come right after the main text. */
const sections = computed<Section[]>(() => {
  const { headers, main, list, footers } = model.value
  return [
    ...headers.map((part) => ({ key: part.key, place: 'header' as const, blocks: part.blocks })),
    ...main.map((part) => ({ key: part.key, place: 'main' as const, blocks: part.blocks })),
    ...(list ? [{ key: 'list', place: 'list' as const, list }] : []),
    ...footers.map((part) => ({ key: part.key, place: 'footer' as const, blocks: part.blocks })),
  ]
})

/**
 * The template's text is drawn at 14px for its 11 point body, so a line across the sheet holds about as many
 * characters as it does on paper; every other size keeps its proportion to that.
 */
const TEXT_SCALE = 0.875

const sheetCss = computed(() => {
  const css = pageSheetCss(props.layout)
  // A page without a layout has no size of its own to scale: its list of lines is drawn at the same size.
  const size = /^(\d+(?:\.\d+)?)rem$/.exec(css.fontSize ?? '')
  css.fontSize = `${Math.round((size ? Number(size[1]) : 1) * TEXT_SCALE * 10000) / 10000}rem`
  return css
})

/*
 * Run styles are worked out once per template style, not on every keystroke: a fill spot whose props
 * are unchanged is not redrawn while the person types elsewhere on the page.
 */
const styleCache = computed(() => ({
  base: pageBaseHalfPoints(props.layout),
  plain: new WeakMap<TemplateLayoutStyleResponse, CssStyle>(),
  muted: new WeakMap<TemplateLayoutStyleResponse, CssStyle>(),
}))

function cssFor(style: TemplateLayoutStyleResponse | null, muted = false): CssStyle {
  if (!style) return EMPTY_CSS
  const cache = styleCache.value
  const store = muted ? cache.muted : cache.plain
  let css = store.get(style)
  if (!css) {
    css = styleToCss(style, cache.base)
    // Header and footer text takes the page's quieter colour, so it reads as the page's frame rather than its content.
    if (muted) delete css.color
    store.set(style, css)
  }
  return css
}

const ALIGNMENTS = { START: 'start', CENTER: 'center', END: 'end', JUSTIFY: 'justify' } as const

// ---- A spot and the word before it ----------------------------------------------------------------

/** A fill spot drawn on one line with the label before it. */
interface KeptTogether {
  kind: 'kept'
  key: string
  label: PageText
  spot: PageSpot
}

type PageRun = PageInline | KeptTogether

/**
 * A short label at the end of a piece of text, such as "Date:" or "Date of birth:": words from a word's start
 * up to a colon, about a few words long, with nothing between them that ends a sentence or a blank.
 */
const LABEL_AT_END = /(?<=^|\s)[\p{L}\p{N}][^\t_.\u2026,;!?:\uff1a]{0,30}[:\uff1a]\s*$/u

/** Splits text before the short label it ends with; null when it does not end with one. Spacing alone before the label stays with it. */
function labelAtEnd(text: string): { head: string; tail: string } | null {
  const match = LABEL_AT_END.exec(text)
  if (!match) return null
  const head = text.slice(0, match.index)
  return head.trim() === '' ? { head: '', tail: text } : { head, tail: match[0] }
}

const runsCache = new WeakMap<readonly PageInline[], PageRun[]>()

/**
 * A paragraph's pieces as the page draws them. Where a fill spot follows a short label ("Date:", "Date of
 * birth:"), the label is drawn with the spot as one piece, so a line with no room left for the spot sends
 * the label down with it rather than leaving it at the end of the line, where it would read as part of
 * what comes before it (a signature line, say). Other words before a spot, such as a question, flow as
 * they are. The label keeps where it starts in the paragraph's text, so a place chosen in it is read as
 * before.
 */
function runsOf(inlines: readonly PageInline[]): PageRun[] {
  const cached = runsCache.get(inlines)
  if (cached) return cached
  const runs: PageRun[] = []
  for (const inline of inlines) {
    const before = runs.at(-1)
    const split = inline.kind === 'spot' && before?.kind === 'text' && before.controlNodeId === null ? labelAtEnd(before.text) : null
    if (inline.kind !== 'spot' || before?.kind !== 'text' || split === null) {
      runs.push(inline)
      continue
    }
    runs.pop()
    if (split.head !== '') runs.push({ ...before, text: split.head })
    const anchorStart = before.anchorStart === null ? null : before.anchorStart + codePointLength(split.head)
    runs.push({ kind: 'kept', key: `${inline.key}/kept`, label: { ...before, key: `${before.key}/last`, text: split.tail, anchorStart }, spot: inline })
  }
  runsCache.set(inlines, runs)
  return runs
}

function paragraphCss(paragraph: PageParagraph): CssStyle {
  const css: CssStyle = {}
  if (paragraph.alignment) css.textAlign = ALIGNMENTS[paragraph.alignment]
  if (paragraph.listLevel !== null) {
    // A hanging indent: the bullet sits in the margin and wrapped lines align with the text.
    css.paddingInlineStart = `${(paragraph.listLevel + 1) * 1.5}em`
    css.textIndent = '-1em'
  }
  return css
}

// ---- Tables ---------------------------------------------------------------------------------------

/** A row the filler repeats, or the line that stands in for it while there are no rows. */
function isRepeatingRow(row: PageRow): boolean {
  return row.noRowsColumns > 0 || row.cells.some((cell) => cell.paragraphs.some((paragraph) => paragraph.repeats))
}

/** A table whose first row names the columns of the rows below it. */
function hasHeaderRow(table: PageTable): boolean {
  const [first] = table.rows
  return first !== undefined && !isRepeatingRow(first) && table.rows.some(isRepeatingRow)
}

/** The table stands in for rows it does not have yet. */
function hasNoRowsLine(table: PageTable): boolean {
  return table.rows.some((row) => row.noRowsColumns > 0)
}

/** Brownie found the table's places itself and the person has not yet said they are right. */
const repeatsFound = computed(() =>
  props.fields.some((field) => field.cardinality === 'REPEATED' && props.foundFieldIds?.has(field.fieldId) === true),
)

/**
 * Where the one "Add row" button goes while there are no rows: the first line on the page that stands in for them,
 * so the way to the first row sits where the rows will be. Null once there are rows (the button follows them), or
 * where no line stands in for them.
 */
const addRowHome = computed<string | null>(() => {
  if (rowCount.value > 0) return null
  for (const section of sections.value) {
    if (section.place === 'list') {
      if (section.list.columns.length > 0) return section.list.addRow ? 'list' : null
      continue
    }
    for (const block of section.blocks) {
      if (block.kind === 'paragraph' && block.noRows) return block.key
      if (block.kind !== 'table') continue
      for (const row of block.rows) {
        if (row.noRowsColumns > 0) return row.key
        const inCell = row.cells.flatMap((cell) => cell.paragraphs).find((paragraph) => paragraph.noRows)
        if (inCell) return inCell.key
      }
    }
  }
  return null
})

// ---- One fill spot ------------------------------------------------------------------------------

function valueOf(spot: PageSpot): string {
  const draft = props.drafts[spot.fieldId]
  if (spot.rowIndex === null) return typeof draft === 'string' ? draft : ''
  return Array.isArray(draft) ? (draft[spot.rowIndex] ?? '') : ''
}

function isEmpty(spot: PageSpot): boolean {
  return valueOf(spot).trim() === ''
}

/** What Brownie has proposed for this spot, shown without being written; null when there is nothing to show. */
function previewOf(spot: PageSpot): string | null {
  const proposed = props.previewDrafts?.[spot.fieldId]
  const value = spot.rowIndex === null ? (typeof proposed === 'string' ? proposed : undefined) : Array.isArray(proposed) ? proposed[spot.rowIndex] : undefined
  return value !== undefined && value.trim() !== '' ? value : null
}

function openQuestionsAbout(fieldId: string): number {
  const count = props.questionCounts?.[fieldId]
  return typeof count === 'number' && count > 0 ? count : 0
}

function stateOf(spot: PageSpot): FieldStateResponse | null {
  const field = props.revisionFields?.[spot.fieldId]
  if (!field) return null
  return spot.rowIndex === null ? (field.fieldState ?? null) : (field.itemFieldStates?.[spot.rowIndex] ?? null)
}

function isLocked(spot: PageSpot): boolean {
  return spot.rowIndex === null ? props.lockedFieldIds.has(spot.fieldId) : props.rowsLocked
}

/** Every row stops being editable while any one is locked, so an unlocked row says which lock holds it. */
function lockedHintOf(spot: PageSpot): string | undefined {
  if (spot.rowIndex === null || !props.rowsLocked || stateOf(spot)?.lock === 'EXPLICITLY_LOCKED') return undefined
  return 'A row is locked, so rows cannot be edited until it is unlocked.'
}

/**
 * A blank date in a row cannot be saved (a blank is not a date), so it is marked before the save is refused. A
 * row only Brownie has proposed so far is not in the document, so its blank is not one to mark.
 */
function needsRowValue(spot: PageSpot): boolean {
  return spot.rowIndex !== null && fieldsById.value.get(spot.fieldId)?.type === 'DATE' && isEmpty(spot) && previewOf(spot) === null
}

function isSelected(spot: PageSpot): boolean {
  const selected = props.selected
  return selected !== null && selected.fieldId === spot.fieldId && (selected.rowIndex ?? null) === spot.rowIndex
}

function spotProps(spot: PageSpot, muted = false) {
  // A repeated place is marked once, on its first row, rather than on every row of the table.
  const firstRow = spot.rowIndex === null || spot.rowIndex === 0
  const questions = firstRow ? openQuestionsAbout(spot.fieldId) : 0
  return {
    fieldId: spot.fieldId,
    rowIndex: spot.rowIndex,
    type: fieldsById.value.get(spot.fieldId)?.type ?? 'TEXT',
    value: valueOf(spot),
    label: labelOf(spot.fieldId),
    placeholder: spot.placeholder,
    styleCss: cssFor(spot.style, muted),
    required: props.requiredFieldIds.has(spot.fieldId),
    locked: isLocked(spot),
    lockedHint: lockedHintOf(spot),
    state: stateOf(spot),
    selected: isSelected(spot),
    attention: needsRowValue(spot),
    inputId: spot.inputId,
    keysHintId,
    found: firstRow && (props.foundFieldIds?.has(spot.fieldId) ?? false),
    preview: previewOf(spot),
    questionCount: questions > 0 ? questions : undefined,
  }
}

function targetKey(spot: PageSpot): string {
  return `${spot.fieldId}\n${spot.rowIndex ?? ''}`
}

const lastFocusedTarget = ref<string | null>(null)

function onSpotInput(spot: PageSpot, value: string): void {
  if (spot.rowIndex === null) emit('update-scalar', spot.fieldId, value)
  else emit('update-row', spot.fieldId, spot.rowIndex, value)
}

function onSpotFocus(spot: PageSpot): void {
  lastFocusedTarget.value = targetKey(spot)
  emit('select', { fieldId: spot.fieldId, rowIndex: spot.rowIndex })
}

function onSpotActions(spot: PageSpot): void {
  emit('open-actions', { fieldId: spot.fieldId, rowIndex: spot.rowIndex })
}

const sheet = ref<HTMLElement | null>(null)
const pageBody = ref<HTMLElement | null>(null)

// ---- Choosing a place on the page -------------------------------------------------------------------

const DATA_PART = { header: 'HEADER', main: 'MAIN_DOCUMENT', footer: 'FOOTER' } as const

const {
  bar: fillHereBar,
  menu: pageMenu,
  message: pageMessage,
  onContextMenu,
  onPointerDown,
  fillHereFromBar,
  dismissBar,
  resume: resumeFillHere,
  closeMenu,
  menuFillHere,
  menuCopy,
} = usePageSelection({
  sheet,
  body: pageBody,
  enabled: () => props.canAddSpots === true,
  fillHere: (point, returnTo) => emit('fill-here', point, returnTo),
  placeSeen: (point) => emit('place', point),
  paused: () => props.fillHereHidden === true,
  // Anything the workspace lays over the page, such as the bar about the selected spot.
  covers: () => Array.from(window.document.querySelectorAll('[data-covers-page]')),
  barShown: () => emit('words-selected'),
})

// The dialog over the page has closed: the bar comes back beside the words, or goes if none are selected.
watch(
  () => props.fillHereHidden === true,
  (hidden, wasHidden) => {
    if (wasHidden && !hidden) resumeFillHere()
  },
)

function focusElement(target: HTMLElement | null | undefined): void {
  if (!target) return
  target.scrollIntoView?.({ block: 'center' })
  target.focus()
}

function focusSpot(inputId: string): void {
  const target = window.document.getElementById(inputId)
  if (target instanceof HTMLElement && sheet.value?.contains(target)) focusElement(target)
}

// ---- Rows ---------------------------------------------------------------------------------------

/** The new row's first spot takes focus, so a keyboard user carries on typing where the row appeared. */
async function onAddRow(): Promise<void> {
  const before = realRowCount.value
  emit('add-row')
  await nextTick()
  if (realRowCount.value <= before) return
  const first = model.value.spots.find((spot) => spot.rowIndex === realRowCount.value - 1)
  if (first) focusSpot(first.inputId)
}

// ---- Progress and the page rail -----------------------------------------------------------------

/** Each value the page can fill, once, however many places it appears, in reading order. */
const targets = computed(() => {
  const seen = new Set<string>()
  const result: PageSpot[] = []
  for (const spot of model.value.spots) {
    const key = targetKey(spot)
    if (seen.has(key)) continue
    seen.add(key)
    result.push(spot)
  }
  return result
})

/**
 * A fill spot as the person counts it: a field with one value, or the table of rows, which is one place to fill
 * however many columns it has.
 */
interface Place {
  key: string
  /** Each value it holds, in reading order; none for a table that has no rows yet. */
  spots: PageSpot[]
  table: boolean
}

/** The table's key starts with a line break, which no field id has, so it never clashes with a field's. */
const TABLE_KEY = '\nrows'

const places = computed(() => {
  const result: Place[] = []
  let table: Place | null = null
  for (const spot of targets.value) {
    if (spot.rowIndex === null) {
      result.push({ key: spot.fieldId, spots: [spot], table: false })
    } else {
      if (!table) {
        table = { key: TABLE_KEY, spots: [], table: true }
        result.push(table)
      }
      table.spots.push(spot)
    }
  }
  if (!table && hasRepeatedFields.value) result.push({ key: TABLE_KEY, spots: [], table: true })
  return result
})

/** A table is filled once it has rows and none of them has a blank; any other place, once it holds a value. */
function isFilled(place: Place): boolean {
  return place.spots.length > 0 && place.spots.every((spot) => !isEmpty(spot))
}

const progressText = computed(() => {
  const total = places.value.length
  const filled = places.value.filter(isFilled).length
  return `${filled} of ${total} ${total === 1 ? 'fill spot' : 'fill spots'} filled`
})

/**
 * What is left for the person to decide: values Brownie wrote that nobody has looked at yet, and values someone
 * has accepted. A value the person typed needs no review of its own, so it is in neither unless it was accepted.
 */
const reviewCounts = computed(() => {
  let toReview = 0
  let accepted = 0
  for (const place of places.value) {
    const states = place.spots.filter((spot) => !isEmpty(spot)).map((spot) => stateOf(spot))
    if (states.length === 0) continue
    if (states.some((state) => stateFromAssist(state) && state?.review === 'UNREVIEWED')) toReview++
    else if (states.every((state) => state?.review === 'ACCEPTED')) accepted++
  }
  return { toReview, accepted }
})

const hasEmptyTarget = computed(() => targets.value.some((spot) => isEmpty(spot) && !isLocked(spot)))
const hasRequiredTarget = computed(() => targets.value.some((spot) => props.requiredFieldIds.has(spot.fieldId)))

/** Moves to the next empty spot after the one last focused, wrapping to the top; a locked spot cannot be filled, so it is passed over. */
function focusNextEmpty(): void {
  const list = targets.value
  const start = list.findIndex((spot) => targetKey(spot) === lastFocusedTarget.value)
  for (let step = 1; step <= list.length; step++) {
    const candidate = list[(start + step) % list.length]!
    if (isEmpty(candidate) && !isLocked(candidate)) {
      focusSpot(candidate.inputId)
      return
    }
  }
}

/** How a place shows on the rail: the strongest thing to say about it wins, and each tone differs in shape as well as colour. */
type Tone = 'open' | 'filled' | 'question' | 'invalid' | 'selected'

/** The split a spot makes: red for what cannot be saved or exported, or was turned down; honey with a question mark for what is being asked about. */
function isInvalid(spot: PageSpot): boolean {
  const state = stateOf(spot)
  return state?.validation === 'BLOCKING' || state?.review === 'REJECTED' || needsRowValue(spot)
}

function isQuestioned(place: Place): boolean {
  if (place.spots.some((spot) => stateOf(spot)?.review === 'NEEDS_CLARIFICATION')) return true
  if (!place.table) return openQuestionsAbout(place.spots[0]!.fieldId) > 0
  return props.fields.some((field) => field.cardinality === 'REPEATED' && openQuestionsAbout(field.fieldId) > 0)
}

function toneOf(place: Place): Tone {
  if (place.spots.some(isSelected)) return 'selected'
  if (place.spots.some(isInvalid)) return 'invalid'
  if (isQuestioned(place)) return 'question'
  // A value Brownie only proposes is still a gap in the document, whatever the spot holds now.
  if (place.spots.some((spot) => previewOf(spot) !== null)) return 'open'
  return isFilled(place) ? 'filled' : 'open'
}

/** Where each place sits down the sheet, in pixels from its top, keyed by place. */
const tickTops = ref<Record<string, number>>({})

/** The control the rail's tick for a place stands level with and goes to: its first spot, or "Add row" for a table with no rows. */
function railTarget(place: Place): HTMLElement | null {
  const root = sheet.value
  if (!root) return null
  const first = place.spots[0]
  const element = first ? window.document.getElementById(first.inputId) : root.querySelector<HTMLElement>('[data-add-row]')
  return element instanceof HTMLElement && root.contains(element) ? element : null
}

/** The middle of a text control's first line, in pixels from `origin`, so a tick stays with the first of the lines a long value wraps onto; for anything else, the middle of its box. */
function firstLineMiddle(element: HTMLElement, origin: number): number {
  const box = element.getBoundingClientRect()
  if (!(element instanceof HTMLTextAreaElement || element instanceof HTMLInputElement)) return box.top + box.height / 2 - origin
  const style = window.getComputedStyle(element)
  const lineHeight = Number.parseFloat(style.lineHeight)
  const inset = (Number.parseFloat(style.borderTopWidth) || 0) + (Number.parseFloat(style.paddingTop) || 0)
  const line = Number.isFinite(lineHeight) ? Math.min(lineHeight, box.height - inset) : box.height - inset
  return box.top + inset + line / 2 - origin
}

function measure(): void {
  const root = sheet.value
  if (!root) return
  const origin = root.getBoundingClientRect().top
  const tops: Record<string, number> = {}
  for (const place of places.value) {
    const element = railTarget(place)
    if (element) tops[place.key] = Math.round(firstLineMiddle(element, origin) * 2) / 2
  }
  const known = tickTops.value
  const keys = Object.keys(tops)
  if (keys.length !== Object.keys(known).length || keys.some((key) => known[key] !== tops[key])) tickTops.value = tops
}

const ticks = computed(() =>
  places.value.flatMap((place) => {
    const top = tickTops.value[place.key]
    return top === undefined ? [] : [{ place, top, tone: toneOf(place) }]
  }),
)

/** A tick takes the pointer to the same control the keyboard reaches by Tab. */
function goToPlace(place: Place): void {
  const target = railTarget(place)
  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) focusSpot(target.id)
  else focusElement(target)
}

let resizeObserver: ResizeObserver | null = null
let measureQueued = false
let measureFrame = 0
let measureDebounce = 0

/** At most one measurement per frame, however many things moved at once. */
function scheduleMeasure(): void {
  if (measureQueued) return
  measureQueued = true
  const run = () => {
    measureQueued = false
    measureFrame = 0
    measure()
  }
  if (typeof window.requestAnimationFrame === 'function') measureFrame = window.requestAnimationFrame(run)
  else run()
}

/**
 * The sheet's own height can stay put (it is never shorter than a page) while a line wraps and pushes the spots
 * below it down, so the parts of the page are watched as well as the sheet.
 */
function watchSheet(): void {
  const root = sheet.value
  if (!resizeObserver || !root) return
  resizeObserver.disconnect()
  resizeObserver.observe(root)
  for (const part of Array.from(root.children)) resizeObserver.observe(part)
}

onMounted(() => {
  measure()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(scheduleMeasure)
    watchSheet()
  }
  // The text is set again when the page's typeface arrives, which can move every line after the first.
  const fonts = window.document.fonts
  fonts?.addEventListener?.('loadingdone', scheduleMeasure)
  void fonts?.ready?.then(scheduleMeasure)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.document.fonts?.removeEventListener?.('loadingdone', scheduleMeasure)
  if (measureFrame !== 0) window.cancelAnimationFrame?.(measureFrame)
  window.clearTimeout(measureDebounce)
})

watch(model, () =>
  void nextTick(() => {
    watchSheet()
    scheduleMeasure()
  }),
)

/*
 * A value that changes (typed, undone, proposed) can move a spot to the next line without changing the height of
 * any part. Typing is many such changes in a row, so this waits for a short pause in them rather than reading
 * every place on the page back for each keystroke.
 */
watch(
  () => [props.drafts, props.previewDrafts],
  () => {
    window.clearTimeout(measureDebounce)
    measureDebounce = window.setTimeout(scheduleMeasure, 200)
  },
  { deep: true },
)
</script>

<template>
  <section class="document-page" :aria-labelledby="headingId">
    <h2 :id="headingId" class="visually-hidden">Document</h2>
    <p v-if="!layout && layoutState === 'unavailable'" class="document-page__notice" role="status">
      <AppIcon name="info" :size="16" />
      <span>{{ layoutProblem ? `${layoutProblem} Its fill spots are listed instead.` : "Brownie could not draw this template's layout, so its fill spots are listed instead." }}</span>
    </p>

    <div v-if="places.length > 0 || canAddSpots" class="document-page__status">
      <div class="document-page__status-text">
        <!-- The dots are for the eye; assistive technology hears the counts as sentences. -->
        <p v-if="places.length > 0" class="document-page__summary">
          {{ progressText
          }}<template v-if="reviewCounts.toReview > 0"
            ><span class="visually-hidden">. </span><span aria-hidden="true"> · </span>{{ reviewCounts.toReview }} to review</template
          ><template v-if="reviewCounts.accepted > 0"
            ><span class="visually-hidden">. </span><span aria-hidden="true"> · </span>{{ reviewCounts.accepted }} accepted</template
          >
        </p>
        <!-- What the red mark beside a spot means; each required spot also says it in words to assistive technology. -->
        <p v-if="hasRequiredTarget" class="document-page__legend">
          <span class="document-page__legend-required" aria-hidden="true">*</span> Required before export
        </p>
      </div>
      <span class="document-page__status-actions">
        <button v-if="canAddSpots" type="button" class="button button--secondary button--small" @click="emit('add-spot')">
          <AppIcon name="plus" :size="16" />Add a fill spot
        </button>
        <button v-if="hasEmptyTarget" type="button" class="button button--secondary button--small" @click="focusNextEmpty">
          <AppIcon name="chevron-down" :size="16" />Next empty spot
        </button>
      </span>
    </div>

    <slot name="banner" />

    <!-- Why a place chosen on the page cannot take a fill spot; in the tree while spots can be added, so it is heard when it changes. -->
    <p v-if="canAddSpots" class="document-page__tool-message" :class="{ 'visually-hidden': !pageMessage }" role="status">{{ pageMessage }}</p>
    <p :id="keysHintId" class="visually-hidden">{{ actionsKey }} moves to the bar about this spot.</p>

    <div ref="pageBody" class="document-page__body">
      <div ref="sheet" class="document-page__sheet" :style="sheetCss" @contextmenu="onContextMenu" @pointerdown="onPointerDown">
        <p v-if="sections.length === 0" class="document-page__quiet" :role="layoutState === 'loading' ? 'status' : undefined">
          <span v-if="layoutState === 'loading'" class="activity-indicator" aria-hidden="true"></span>
          {{ layoutState === 'loading' ? 'Loading the page…' : 'This document has no fill spots.' }}
        </p>
        <template v-for="section in sections" :key="section.key">
          <div
            v-if="section.place === 'list'"
            class="document-page__list"
            :class="{ 'document-page__list--others': layout !== null }"
          >
            <template v-if="layout">
              <h3 class="document-page__list-heading">Other fill spots</h3>
              <p class="field-hint">The template has no marked place for these.</p>
            </template>
            <p v-for="spot in section.list.scalars" :key="spot.key" class="document-page__paragraph">
              <span class="document-page__field-label">{{ labelOf(spot.fieldId) }}:</span>
              <FillSpot v-bind="spotProps(spot)" @update:value="onSpotInput(spot, $event)" @focus="onSpotFocus(spot)" @actions="onSpotActions(spot)" />
            </p>
            <div v-if="section.list.columns.length > 0" class="document-page__table-wrap" :class="{ 'document-page__table-wrap--found': repeatsFound && section.list.rows.length === 0 }">
              <table class="document-page__table document-page__table--headed">
                <thead>
                  <tr>
                    <th v-for="fieldId in section.list.columns" :key="fieldId" scope="col">{{ labelOf(fieldId) }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, index) in section.list.rows" :key="index">
                    <td v-for="spot in row" :key="spot.key">
                      <FillSpot v-bind="spotProps(spot)" @update:value="onSpotInput(spot, $event)" @focus="onSpotFocus(spot)" @actions="onSpotActions(spot)" />
                    </td>
                  </tr>
                  <tr v-if="section.list.rows.length === 0">
                    <td :colspan="section.list.columns.length" class="document-page__no-rows">
                      <RowsLine empty :found="repeatsFound" :locked="rowsLocked" :button="addRowHome === 'list'" @add="onAddRow" />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <RowsLine v-if="section.list.addRow && section.list.rows.length > 0" :empty="false" :locked="rowsLocked" button @add="onAddRow" />
          </div>

          <div
            v-else
            :class="{
              'document-page__header-part': section.place === 'header',
              'document-page__main-part': section.place === 'main',
              'document-page__footer-part': section.place === 'footer',
            }"
          >
            <template v-for="block in section.blocks" :key="block.key">
              <p
                v-if="block.kind === 'paragraph'"
                class="document-page__paragraph"
                :class="{ 'document-page__no-rows': block.noRows }"
                :style="paragraphCss(block)"
                :data-part="DATA_PART[section.place]"
                :data-node-id="block.nodeId ?? undefined"
                :data-anchorable="block.anchorable ? 'true' : 'false'"
                :data-anchor-hash="block.anchorTextHash ?? undefined"
                :data-repeats="block.repeats ? 'true' : undefined"
              >
                <RowsLine v-if="block.noRows" empty :found="repeatsFound" :locked="rowsLocked" :button="addRowHome === block.key" @add="onAddRow" />
                <template v-else>
                  <span v-if="block.listLevel !== null" class="document-page__bullet" aria-hidden="true">{{ BULLET }}</span>
                  <template v-for="inline in runsOf(block.inlines)" :key="inline.key">
                    <span
                      v-if="inline.kind === 'text'"
                      class="document-page__text"
                      :style="cssFor(inline.style, section.place !== 'main')"
                      :data-anchor-start="inline.anchorStart ?? undefined"
                      :data-control-node-id="inline.controlNodeId ?? undefined"
                      >{{ inline.text }}</span
                    >
                    <FillSpot
                      v-else-if="inline.kind === 'spot'"
                      v-bind="spotProps(inline, section.place !== 'main')"
                      @update:value="onSpotInput(inline, $event)"
                      @focus="onSpotFocus(inline)"
                      @actions="onSpotActions(inline)"
                    />
                    <span v-else-if="inline.kind === 'kept'" class="document-page__kept"
                      ><span
                        class="document-page__text"
                        :style="cssFor(inline.label.style, section.place !== 'main')"
                        :data-anchor-start="inline.label.anchorStart ?? undefined"
                        >{{ inline.label.text }}</span
                      ><FillSpot
                        v-bind="spotProps(inline.spot, section.place !== 'main')"
                        @update:value="onSpotInput(inline.spot, $event)"
                        @focus="onSpotFocus(inline.spot)"
                        @actions="onSpotActions(inline.spot)"
                    /></span>
                    <span v-else class="document-page__image" role="img" aria-label="Image from the template">Image</span>
                  </template>
                </template>
              </p>

              <div
                v-else-if="block.kind === 'table'"
                class="document-page__table-wrap"
                :class="{ 'document-page__table-wrap--found': repeatsFound && hasNoRowsLine(block) }"
              >
                <table class="document-page__table" :class="{ 'document-page__table--headed': hasHeaderRow(block) }">
                  <tbody>
                    <tr v-for="row in block.rows" :key="row.key">
                      <td v-if="row.noRowsColumns > 0" :colspan="row.noRowsColumns" class="document-page__no-rows">
                        <RowsLine empty :found="repeatsFound" :locked="rowsLocked" :button="addRowHome === row.key" @add="onAddRow" />
                      </td>
                      <template v-else>
                        <td v-for="cell in row.cells" :key="cell.key">
                          <p
                            v-for="paragraph in cell.paragraphs"
                            :key="paragraph.key"
                            class="document-page__paragraph"
                            :class="{ 'document-page__no-rows': paragraph.noRows }"
                            :style="paragraphCss(paragraph)"
                            :data-part="DATA_PART[section.place]"
                            :data-node-id="paragraph.nodeId ?? undefined"
                            :data-anchorable="paragraph.anchorable ? 'true' : 'false'"
                            :data-anchor-hash="paragraph.anchorTextHash ?? undefined"
                            :data-repeats="paragraph.repeats ? 'true' : undefined"
                          >
                            <RowsLine v-if="paragraph.noRows" empty :found="repeatsFound" :locked="rowsLocked" :button="addRowHome === paragraph.key" @add="onAddRow" />
                            <template v-else>
                              <span v-if="paragraph.listLevel !== null" class="document-page__bullet" aria-hidden="true">{{
                                BULLET
                              }}</span>
                              <template v-for="inline in runsOf(paragraph.inlines)" :key="inline.key">
                                <span
                                  v-if="inline.kind === 'text'"
                                  class="document-page__text"
                                  :style="cssFor(inline.style, section.place !== 'main')"
                                  :data-anchor-start="inline.anchorStart ?? undefined"
                                  :data-control-node-id="inline.controlNodeId ?? undefined"
                                  >{{ inline.text }}</span
                                >
                                <FillSpot
                                  v-else-if="inline.kind === 'spot'"
                                  v-bind="spotProps(inline, section.place !== 'main')"
                                  @update:value="onSpotInput(inline, $event)"
                                  @focus="onSpotFocus(inline)"
                                  @actions="onSpotActions(inline)"
                                />
                                <span v-else-if="inline.kind === 'kept'" class="document-page__kept"
                                  ><span
                                    class="document-page__text"
                                    :style="cssFor(inline.label.style, section.place !== 'main')"
                                    :data-anchor-start="inline.label.anchorStart ?? undefined"
                                    >{{ inline.label.text }}</span
                                  ><FillSpot
                                    v-bind="spotProps(inline.spot, section.place !== 'main')"
                                    @update:value="onSpotInput(inline.spot, $event)"
                                    @focus="onSpotFocus(inline.spot)"
                                    @actions="onSpotActions(inline.spot)"
                                /></span>
                                <span v-else class="document-page__image" role="img" aria-label="Image from the template">Image</span>
                              </template>
                            </template>
                          </p>
                        </td>
                      </template>
                    </tr>
                  </tbody>
                </table>
              </div>

              <RowsLine v-else-if="addRowHome === null" :empty="false" :locked="rowsLocked" button @add="onAddRow" />
            </template>
          </div>
        </template>
      </div>

      <!-- A glance at where the spots are and which still need a value; the spots themselves carry all of it for assistive technology. -->
      <div class="document-page__rail" aria-hidden="true">
        <span class="document-page__page-number">1</span>
        <span class="document-page__track"></span>
        <span
          v-for="tick in ticks"
          :key="tick.place.key"
          class="document-page__tick"
          :class="`document-page__tick--${tick.tone}`"
          :style="{ top: `${tick.top}px` }"
          @click="goToPlace(tick.place)"
        ></span>
      </div>

      <!--
        Kept, not removed, while a dialog is over the page: focus goes back to its button when the dialog closes,
        and the words it was about are selected again. Otherwise it is there only while words on the page are.
      -->
      <FillHereBar
        v-if="fillHereBar && canAddSpots"
        v-show="!fillHereHidden"
        :top="fillHereBar.top"
        :left="fillHereBar.left"
        @fill-here="fillHereFromBar"
        @dismiss="dismissBar"
      />
    </div>
    <SpotContextMenu
      v-if="pageMenu"
      :x="pageMenu.x"
      :y="pageMenu.y"
      :can-copy="pageMenu.copyText !== ''"
      @fill-here="menuFillHere"
      @copy="menuCopy"
      @close="closeMenu"
    />
  </section>
</template>

<style scoped>
/*
 * The column the page is drawn in: the status line, whatever the screen puts above the sheet, and the sheet,
 * 600px wide at most and as wide as the screen allows below that.
 */
.document-page {
  container: document-page / inline-size;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  inline-size: 100%;
  max-inline-size: 37.5rem;
  min-inline-size: 0;
  margin-inline: auto;
}

/* A calm note over the page that its fill spots are listed because the page could not be drawn. */
.document-page__notice {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  margin: 0;
  padding: 0.75rem 0.875rem;
  border-radius: var(--radius-md);
  background: var(--color-paper-soft);
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

.document-page__notice > svg {
  margin-block-start: 0.0625rem;
}

/*
 * How far along the page is, what its red mark means, and the ways to the next gap or a new spot: one quiet line over
 * the sheet. The words give way before the buttons do: when they do not both fit beside them, the legend drops under
 * the count, two small lines being no taller than a button, so the sheet below never moves. Only where there is no
 * room for even the count do the buttons move to a line of their own.
 */
.document-page__status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1) 0.625rem;
  min-block-size: var(--control-height-small);
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.35;
}

.document-page__status-text {
  display: flex;
  flex: 1 1 14rem;
  flex-wrap: wrap;
  column-gap: 0.625rem;
  min-inline-size: 0;
}

.document-page__summary,
.document-page__legend {
  margin: 0;
}

.document-page__summary {
  font-weight: var(--font-weight-medium);
}

/* Raised a pixel, as a star is when it is set against the line of words beside it. */
.document-page__legend-required {
  position: relative;
  inset-block-start: -0.0625rem;
  margin-inline-end: 0.125rem;
  color: var(--color-error);
  font-weight: var(--font-weight-medium);
}

.document-page__status .button {
  --button-gap: 0.4375rem;
}

.document-page__status-actions {
  display: flex;
  flex: 0 1 auto;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-inline-start: auto;
}

.document-page__tool-message:not(.visually-hidden) {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.document-page__body {
  position: relative;
}

/*
 * The paper: white on the page colour with soft corners and no shadow, as tall as a letter page is for its width
 * and taller where the document needs it. The transparent outline is drawn by forced colours, where the sheet
 * would otherwise lose its edge.
 */
.document-page__sheet {
  --sheet-inset: clamp(1.25rem, 8cqi, 3rem);
  inline-size: 100%;
  aspect-ratio: 8.5 / 11;
  padding: 2.5rem var(--sheet-inset) 3rem;
  background: var(--color-surface);
  border-radius: var(--radius-md);
  outline: 1px solid transparent;
  outline-offset: -1px;
  color: var(--color-text);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.document-page__quiet {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.document-page__header-part,
.document-page__footer-part {
  color: var(--color-text-muted);
  font-size: 0.875em;
}

.document-page__header-part {
  margin-block-end: var(--space-5);
  padding-block-end: var(--space-3);
  border-block-end: 1px dashed var(--color-hairline);
}

.document-page__footer-part {
  margin-block-start: var(--space-5);
  padding-block-start: var(--space-3);
  border-block-start: 1px dashed var(--color-hairline);
}

.document-page__paragraph {
  /* About the 8 points Word leaves after a paragraph unless a template says otherwise. */
  margin: 0 0 0.72em;
  /* An empty paragraph is one of the template's blank lines, so it keeps a line's height. */
  min-block-size: 1.5em;
}

/* The template's own spacing (several spaces between labels, say) is part of how it reads. */
.document-page__text {
  white-space: pre-wrap;
}

/*
 * A fill spot and the label before it, as one piece of the line: where the line has no room left for the
 * spot, the label moves down with it. No wider than the line, so a long one still wraps inside itself.
 */
.document-page__kept {
  display: inline-block;
  max-inline-size: 100%;
}

.document-page__bullet {
  display: inline-block;
  inline-size: 1em;
  text-indent: 0;
}

.document-page__no-rows {
  color: var(--color-text-muted);
  font-style: italic;
}

.document-page__image {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-inline-size: 4em;
  min-block-size: 2.5em;
  padding: 0 0.5em;
  vertical-align: middle;
  border: 1px solid var(--color-hairline);
  border-radius: 0.25rem;
  background: var(--color-paper);
  color: var(--color-text-muted);
  font-size: 0.75em;
}

/*
 * A table is a hairline frame with soft corners; a wide one scrolls inside the page instead of pushing the whole
 * page sideways on a phone. A table that has not got rows yet, from a place Brownie found, is dashed like the
 * spots it will hold.
 */
.document-page__table-wrap {
  max-inline-size: 100%;
  margin: 0 0 0.5em;
  border: 1px solid var(--color-hairline);
  border-radius: 0.25rem;
  overflow-x: auto;
}

.document-page__table-wrap--found {
  border: 1px dashed var(--color-honey-ink);
}

.document-page__table {
  inline-size: 100%;
  border-collapse: collapse;
}

/* A row of one-line values comes to 34px: a 21px line, a spot's own height and the air above and below. */
.document-page__table th,
.document-page__table td {
  padding: 0.4em 0.5714em;
  text-align: start;
  vertical-align: top;
}

.document-page__table tr + tr > td,
.document-page__table tr + tr > th {
  border-block-start: 1px solid var(--color-hairline);
}

/* Where the first row names the columns, it is a quiet band above the rows. */
.document-page__table--headed tr:first-child > th,
.document-page__table--headed tr:first-child > td {
  background: var(--color-paper-soft);
  font-weight: var(--font-weight-medium);
}

.document-page__table .document-page__paragraph:last-child {
  margin-block-end: 0;
}

.document-page__list--others {
  margin-block: var(--space-5);
  padding-block-start: var(--space-4);
  border-block-start: 1px solid var(--color-hairline);
}

.document-page__list-heading {
  margin: 0 0 var(--space-1);
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
}

.document-page__field-label {
  margin-inline-end: 0.35em;
  font-weight: var(--font-weight-medium);
}

/*
 * The rail: a slim track beside the sheet with a tick level with every fill spot, so the whole page can be seen
 * at a glance. It is hidden from assistive technology and holds nothing to tab to; a tick is a mouse way to the
 * same spot Tab reaches. It hangs in the margin to the sheet's left, which the screen leaves only where it shows the
 * page beside Brownie's panel.
 */
.document-page__rail {
  display: none;
  position: absolute;
  inset-block: 0;
  inset-inline-end: 100%;
  inline-size: 3.125rem;
  /* Only the ticks answer a pointer, and a selection that runs over the page never picks up the page number. */
  pointer-events: none;
  user-select: none;
}

@container main (min-width: 50rem) {
  .document-page__rail {
    display: block;
  }
}

.document-page__track {
  position: absolute;
  inset-block: 0;
  inset-inline-start: 1.0625rem;
  inline-size: 0.25rem;
  border-radius: var(--radius-pill);
  background: var(--color-track);
}

.document-page__page-number {
  position: absolute;
  inset-block-start: 0.1875rem;
  inset-inline-start: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1;
}

/* Each tick is a small mark on the track inside a taller box, so there is something to aim a pointer at. */
.document-page__tick {
  --tick-ink: var(--color-honey);
  --tick-width: 0.875rem;
  --tick-height: 0.25rem;
  position: absolute;
  inset-inline-start: 0;
  inline-size: 1.625rem;
  block-size: 1.5rem;
  transform: translateY(-50%);
  cursor: pointer;
  pointer-events: auto;
}

.document-page__tick::before {
  content: '';
  position: absolute;
  inset-block-start: 50%;
  inset-inline-end: 0;
  inline-size: var(--tick-width);
  block-size: var(--tick-height);
  border-radius: var(--radius-pill);
  background: var(--tick-ink);
  transform: translateY(-50%);
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.document-page__tick:hover::before {
  filter: brightness(0.9);
}

/* A tick says what is left in its shape as well as its colour: the ones that need a look are thicker and longer. */
.document-page__tick--filled {
  --tick-ink: var(--color-cocoa-tile);
}

.document-page__tick--question,
.document-page__tick--invalid,
.document-page__tick--selected {
  --tick-width: 1rem;
  --tick-height: 0.375rem;
}

.document-page__tick--invalid {
  --tick-ink: var(--color-error);
}

.document-page__tick--selected {
  --tick-ink: var(--color-focus);
}
</style>
