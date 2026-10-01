<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import FillSpot from '@/components/workspace/FillSpot.vue'
import FillHereBar from '@/components/workspace/FillHereBar.vue'
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
  stateNeedsAttention,
  styleToCss,
  type CssStyle,
  type EditableField,
  type PageBlock,
  type PageFieldList,
  type PageInline,
  type PageParagraph,
  type PageSpot,
  type PageText,
} from '@/workspace/layout'

/**
 * The document as a page: the template's own text in its own styles, with a fill spot wherever a
 * value goes, so the person fills the document where it will read rather than in a form beside it.
 * Without a layout (an older server, a template that could not be drawn, or while it loads) the same
 * fields are listed as "Label: value" lines and a rows table, so editing never waits on the drawing.
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
  /** Places Brownie found itself that the person has not yet said are right; each carries a "Found by Brownie" badge. */
  foundFieldIds?: ReadonlySet<string>
  /** New fill spots can be added to this page: "Add a fill spot", and "Fill in here" on its text. */
  canAddSpots?: boolean
  /** A dialog about a new fill spot is over the page, so the "Fill in here" bar stands down. */
  fillHereHidden?: boolean
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

/** Rows are the repeated fields' items; the longest column decides, so no typed item is ever hidden. */
const rowCount = computed(() => {
  let count = 0
  for (const field of props.fields) {
    const draft = field.cardinality === 'REPEATED' ? props.drafts[field.fieldId] : undefined
    if (Array.isArray(draft)) count = Math.max(count, draft.length)
  }
  return count
})

const model = computed(() =>
  props.layout ? buildPageModel(props.layout, props.fields, rowCount.value) : buildFallbackModel(props.fields, rowCount.value),
)

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

const sheetCss = computed(() => pageSheetCss(props.layout))

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

// ---- One fill spot ------------------------------------------------------------------------------

function valueOf(spot: PageSpot): string {
  const draft = props.drafts[spot.fieldId]
  if (spot.rowIndex === null) return typeof draft === 'string' ? draft : ''
  return Array.isArray(draft) ? (draft[spot.rowIndex] ?? '') : ''
}

function isEmpty(spot: PageSpot): boolean {
  return valueOf(spot).trim() === ''
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

/** A blank date in a row cannot be saved (a blank is not a date), so it is marked before the save is refused. */
function needsRowValue(spot: PageSpot): boolean {
  return spot.rowIndex !== null && fieldsById.value.get(spot.fieldId)?.type === 'DATE' && isEmpty(spot)
}

function isSelected(spot: PageSpot): boolean {
  const selected = props.selected
  return selected !== null && selected.fieldId === spot.fieldId && (selected.rowIndex ?? null) === spot.rowIndex
}

function spotProps(spot: PageSpot, muted = false) {
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
    // A repeated place is marked once, on its first row, rather than on every row of the table.
    found: (spot.rowIndex === null || spot.rowIndex === 0) && (props.foundFieldIds?.has(spot.fieldId) ?? false),
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

function focusSpot(inputId: string): void {
  const target = window.document.getElementById(inputId)
  if (!(target instanceof HTMLElement) || !sheet.value?.contains(target)) return
  target.scrollIntoView?.({ block: 'center' })
  target.focus()
}

// ---- Rows ---------------------------------------------------------------------------------------

/** The new row's first spot takes focus, so a keyboard user carries on typing where the row appeared. */
async function onAddRow(): Promise<void> {
  const before = rowCount.value
  emit('add-row')
  await nextTick()
  if (rowCount.value <= before) return
  const first = model.value.spots.find((spot) => spot.rowIndex === rowCount.value - 1)
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

const progressText = computed(() => {
  const total = targets.value.length
  const filled = targets.value.filter((spot) => !isEmpty(spot)).length
  return `${filled} of ${total} ${total === 1 ? 'fill spot' : 'fill spots'} filled`
})

const hasEmptyTarget = computed(() => targets.value.some((spot) => isEmpty(spot) && !isLocked(spot)))
const hasRequiredTarget = computed(() => targets.value.some((spot) => props.requiredFieldIds.has(spot.fieldId)))
const hasAssistValue = computed(() => targets.value.some((spot) => !isEmpty(spot) && stateFromAssist(stateOf(spot))))

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

/** Where each spot sits down the sheet, from 0 (top) to 1 (bottom), keyed by element id. */
const markerPositions = ref<Record<string, number>>({})

function measure(): void {
  const root = sheet.value
  if (!root) return
  const box = root.getBoundingClientRect()
  const positions: Record<string, number> = {}
  for (const spot of model.value.spots) {
    const element = window.document.getElementById(spot.inputId)
    if (!element || !root.contains(element)) continue
    const rect = element.getBoundingClientRect()
    positions[spot.inputId] = box.height > 0 ? Math.min(1, Math.max(0, (rect.top + rect.height / 2 - box.top) / box.height)) : 0
  }
  markerPositions.value = positions
}

const markers = computed(() =>
  model.value.spots.map((spot) => ({
    id: spot.inputId,
    top: `${Math.round((markerPositions.value[spot.inputId] ?? 0) * 1000) / 10}%`,
    tone: stateNeedsAttention(stateOf(spot)) || needsRowValue(spot) ? 'attention' : isEmpty(spot) ? 'empty' : 'filled',
  })),
)

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  measure()
  // The sheet changes height when a value wraps onto another line or the window narrows; the markers follow.
  if (typeof ResizeObserver !== 'undefined' && sheet.value) {
    resizeObserver = new ResizeObserver(() => measure())
    resizeObserver.observe(sheet.value)
  }
})

onBeforeUnmount(() => resizeObserver?.disconnect())

watch(model, () => void nextTick(measure))
</script>

<template>
  <section class="document-page" :aria-labelledby="headingId">
    <h2 :id="headingId" class="visually-hidden">Document</h2>
    <p v-if="!layout && layoutState === 'unavailable'" class="field-hint document-page__notice" role="status">
      {{ layoutProblem ? `${layoutProblem} Its fill spots are listed instead.` : "Brownie could not draw this template's layout, so its fill spots are listed instead." }}
    </p>
    <div v-if="targets.length > 0 || canAddSpots" class="document-page__progress">
      <p v-if="targets.length > 0" class="document-page__progress-text">{{ progressText }}</p>
      <!-- What the marks on the page mean; each spot also says it in words to assistive technology. -->
      <p v-if="hasRequiredTarget || hasAssistValue" class="document-page__legend">
        <span v-if="hasRequiredTarget"><span class="document-page__legend-required" aria-hidden="true">*</span> Required before export</span>
        <span v-if="hasAssistValue"><span class="document-page__legend-assist" aria-hidden="true">Aa</span> Filled by Brownie</span>
      </p>
      <span class="document-page__progress-actions">
        <button v-if="hasEmptyTarget" type="button" class="button button--secondary" @click="focusNextEmpty">Next empty spot</button>
        <button v-if="canAddSpots" type="button" class="button button--secondary" @click="emit('add-spot')">Add a fill spot</button>
      </span>
    </div>
    <!-- Why a place chosen on the page cannot take a fill spot; in the tree while spots can be added, so it is heard when it changes. -->
    <p v-if="canAddSpots" class="document-page__tool-message" :class="{ 'visually-hidden': !pageMessage }" role="status">{{ pageMessage }}</p>
    <p :id="keysHintId" class="visually-hidden">{{ actionsKey }} moves to the bar about this spot.</p>

    <div ref="pageBody" class="document-page__body">
      <div ref="sheet" class="document-page__sheet" :style="sheetCss" @contextmenu="onContextMenu" @pointerdown="onPointerDown">
        <p v-if="sections.length === 0" class="field-hint" :role="layoutState === 'loading' ? 'status' : undefined">
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
            <div v-if="section.list.columns.length > 0" class="document-page__table-wrap">
              <table class="document-page__table">
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
                    <td :colspan="section.list.columns.length" class="document-page__no-rows">No rows yet.</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-if="section.list.addRow" class="document-page__add-row">
              <button type="button" class="button button--secondary" data-add-row :disabled="rowsLocked" @click="onAddRow">Add row</button>
              <span v-if="rowsLocked" class="field-hint">A row is locked, so rows cannot be added until it is unlocked.</span>
            </div>
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
                <template v-if="block.noRows">No rows yet.</template>
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

              <div v-else-if="block.kind === 'table'" class="document-page__table-wrap">
                <table class="document-page__table">
                  <tbody>
                    <tr v-for="row in block.rows" :key="row.key">
                      <td v-if="row.noRowsColumns > 0" :colspan="row.noRowsColumns" class="document-page__no-rows">No rows yet.</td>
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
                            <template v-if="paragraph.noRows">No rows yet.</template>
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

              <div v-else class="document-page__add-row">
                <button type="button" class="button button--secondary" data-add-row :disabled="rowsLocked" @click="onAddRow">Add row</button>
                <span v-if="rowsLocked" class="field-hint">A row is locked, so rows cannot be added until it is unlocked.</span>
              </div>
            </template>
          </div>
        </template>
      </div>

      <!-- A glance at where the spots are and which still need a value; the spots themselves carry all of it for assistive technology. -->
      <div class="document-page__rail" aria-hidden="true">
        <span
          v-for="marker in markers"
          :key="marker.id"
          class="document-page__marker"
          :class="`document-page__marker--${marker.tone}`"
          :style="{ top: marker.top }"
          @click="focusSpot(marker.id)"
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
.document-page {
  container: document-page / inline-size;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-inline-size: 0;
}

/*
 * The lines above the sheet start where the sheet does: past the rail, once there is room for it,
 * and no wider than the sheet can grow.
 */
.document-page__notice,
.document-page__progress {
  inline-size: 100%;
  max-inline-size: 50rem;
  margin: 0 auto;
}

@container document-page (min-width: 30rem) {
  .document-page__notice,
  .document-page__progress {
    max-inline-size: calc(50rem + 0.625rem + var(--space-5));
    padding-inline-start: calc(0.625rem + var(--space-5));
  }
}

/* How far along the page is, what its marks mean, and a way to the next gap: one quiet line over the sheet. */
.document-page__progress {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1) var(--space-4);
  min-block-size: 1.75rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
}

.document-page__progress-text {
  margin: 0;
}

.document-page__tool-message:not(.visually-hidden) {
  inline-size: 100%;
  max-inline-size: 50rem;
  margin: 0 auto;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.document-page__legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-3);
  margin: 0;
}

/* The same marks the spots carry: the red asterisk, and the tinted, double-underlined value. */
.document-page__legend-required {
  font-weight: 700;
  color: var(--color-error);
}

.document-page__legend-assist {
  padding: 0 0.2em;
  border-block-end: 3px double var(--color-cocoa);
  background: var(--color-cocoa-wash);
  color: var(--color-text);
}

.document-page__progress-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-inline-start: auto;
}

.document-page__progress .button {
  min-block-size: 1.75rem;
  padding: 0 var(--space-3);
  border-color: var(--color-hairline);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: 500;
}

.document-page__body {
  position: relative;
  display: flex;
  justify-content: center;
  gap: var(--space-5);
  min-inline-size: 0;
}

/*
 * The paper: white on the warm page, with rounded corners and only the faintest shadow, as the design
 * draws it. The transparent edge is drawn by forced colours, where the sheet would otherwise lose its
 * outline.
 */
.document-page__sheet {
  flex: 1 1 auto;
  min-inline-size: 0;
  max-inline-size: 50rem;
  padding: 1.25rem;
  background: var(--color-surface);
  border: 1px solid transparent;
  border-radius: 0.75rem;
  box-shadow: 0 1px 3px color-mix(in srgb, var(--color-text) 6%, transparent);
  color: var(--color-text);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

@container document-page (min-width: 40rem) {
  .document-page__sheet {
    padding: 3rem 3.25rem;
  }
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
  margin: 0 0 0.5em;
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

/* A wide table scrolls inside the page instead of pushing the whole page sideways on a phone. */
.document-page__table-wrap {
  max-inline-size: 100%;
  margin: 0 0 0.75em;
  overflow-x: auto;
}

.document-page__table {
  inline-size: 100%;
  border-collapse: collapse;
}

.document-page__table th,
.document-page__table td {
  padding: 0.25em 0.5em;
  border: 1px solid var(--color-text-secondary);
  text-align: start;
  vertical-align: top;
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
  font-weight: 600;
}

.document-page__field-label {
  margin-inline-end: 0.35em;
  font-weight: 600;
}

.document-page__add-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-3);
  margin: 0 0 1em;
}

.document-page__add-row .button {
  min-block-size: 2rem;
  padding-inline: var(--space-3);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
}

/*
 * The rail: a slim capsule at the sheet's side with a tick where each fill spot sits, as the design
 * draws it. It is hidden from assistive technology and holds nothing to tab to, so drawing it first
 * changes nothing about reading order. Only where there is room.
 */
.document-page__rail {
  display: none;
  position: relative;
  order: -1;
  flex: none;
  inline-size: 0.625rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
}

.document-page__rail::before {
  content: '';
  position: absolute;
  inset: 1px;
  border-radius: var(--radius-pill);
  background: var(--color-track);
}

@container document-page (min-width: 30rem) {
  .document-page__rail {
    display: block;
  }
}

.document-page__marker {
  position: absolute;
  inset-inline-start: 50%;
  inline-size: 0.875rem;
  block-size: 0.25rem;
  border-radius: var(--radius-pill);
  transform: translate(-50%, -50%);
  cursor: pointer;
}

/* The three marks differ in shape as well as colour: a wide tick, a short one, a tall one. */
.document-page__marker--empty {
  background: var(--color-honey);
}

.document-page__marker--filled {
  inline-size: 0.375rem;
  background: var(--color-cocoa-tile);
}

.document-page__marker--attention {
  block-size: 0.75rem;
  background: var(--color-error);
}
</style>
