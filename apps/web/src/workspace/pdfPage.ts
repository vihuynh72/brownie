import { ApiRequestError, type FillSpotChangeRequest, type PdfLayoutResponse } from '@/api/client'
import { describeCommonFailure } from '@/api/failures'
import { fieldLabel, type CssStyle, type EditableField } from '@/workspace/layout'
import { boxFromDisplayed, boxToDisplayed, displayedHeight, displayedWidth, type PdfBox, type PdfPoint } from '@/workspace/pdfGeometry'

/*
 * Pure helpers behind a PDF form's page view: which fill spot sits where on which page and in what
 * order the keyboard meets them, how a box moves and grows under the arrow keys, what a change to
 * the boxes is called and what a refused one means. Nothing here touches the network or the DOM.
 */

export type PdfLayoutPage = PdfLayoutResponse['pages'][number]
export type PdfLayoutSpot = PdfLayoutResponse['spots'][number]
export type PdfTextStyle = NonNullable<PdfLayoutSpot['style']>
export type PdfOverflow = PdfLayoutSpot['overflow']

/** Brownie refuses a box smaller than this, in points: it could not hold a line of text. */
const MIN_BOX_WIDTH = 8
const MIN_BOX_HEIGHT = 6
/** The text sizes the server accepts for a box, in points. */
export const MIN_TEXT_SIZE = 4
export const MAX_TEXT_SIZE = 72
/** The smallest a long value is made to fit its box. */
export const SHRINK_FLOOR_PT = 6
/** A box's look when nothing says otherwise: the ordinary 11 pt sans-serif the server uses. */
export const DEFAULT_TEXT_STYLE: PdfTextStyle = { font: 'SANS', bold: false, sizePt: 11 }

/** One place a value goes on one page. */
export interface PdfPageSpot {
  key: string
  fieldId: string
  /** edit-<fieldId> for the first place a value appears, --2, --3... after, in the keyboard's order. */
  inputId: string
  label: string
  pageNumber: number
  /** Where the value goes: points, the page as stored (box space). */
  box: PdfBox
  /** The same box as the page is shown: points from the top-left of the turned page. */
  shown: PdfBox
  style: PdfTextStyle | null
  multiline: boolean
  overflow: PdfOverflow
  origin: PdfLayoutSpot['origin']
  /** One of the PDF's own form fields: the form sets its place and look, so it cannot be moved or restyled. */
  formField: boolean
}

export interface PdfPageModel {
  pageNumber: number
  width: number
  height: number
  rotation: 0 | 90 | 180 | 270
  shownWidth: number
  shownHeight: number
  hasText: boolean
  lines: PdfLayoutPage['lines']
  /** In the order the keyboard meets them: top to bottom, and left to right along a line. */
  spots: PdfPageSpot[]
}

export interface PdfFormModel {
  pages: PdfPageModel[]
  /** Every place in the keyboard's order: page by page. */
  spots: PdfPageSpot[]
  /** Fields that have no place on any page (a form field the reading no longer finds): listed after the pages, so each stays reachable. */
  unplaced: EditableField[]
}

function validRotation(value: unknown): 0 | 90 | 180 | 270 {
  return value === 90 || value === 180 || value === 270 ? value : 0
}

/**
 * The places on a page that sit along one line of it: the lines from top to bottom, and the places of each line
 * from left to right. Two boxes are on one line when the lower one starts above the middle of the first box
 * of that line, so boxes that sit a point or two apart still read across rather than down.
 */
export function spotLines<T extends { shown: PdfBox }>(spots: readonly T[]): T[][] {
  const byTop = [...spots].sort((left, right) => left.shown.y - right.shown.y || left.shown.x - right.shown.x)
  const lines: T[][] = []
  for (const spot of byTop) {
    const line = lines[lines.length - 1]
    const first = line?.[0]
    if (line && first && spot.shown.y < first.shown.y + first.shown.height / 2) line.push(spot)
    else lines.push([spot])
  }
  return lines.map((line) => line.sort((left, right) => left.shown.x - right.shown.x || left.shown.y - right.shown.y))
}

/** The order a reader meets the places on a page: line by line, from the top, and along each line from the left. */
export function readingOrder<T extends { shown: PdfBox }>(spots: readonly T[]): T[] {
  return spotLines(spots).flat()
}

/** The most the page rail's tick sits below the top of a box, in points: the middle of an ordinary line of text. */
const TICK_REACH = 16

/**
 * Where the page rail marks a line of places, as a percentage down the page as shown: the middle of the highest
 * box, or the middle of its first line when it is tall, so a box of several lines is marked where its text starts.
 */
export function tickTop(line: readonly { shown: PdfBox }[], page: { shownHeight: number }): string {
  const highest = line.reduce((best, spot) => (spot.shown.y < best.shown.y ? spot : best), line[0]!)
  const y = highest.shown.y + Math.min(highest.shown.height / 2, TICK_REACH)
  return `${Math.round((y / page.shownHeight) * 1_000_000) / 10_000}%`
}

/**
 * The PDF form as the page view draws it. A place is drawn only for a field the page can edit alone
 * (a scalar field of the version on screen); every other field of the version is listed after the
 * pages, so no value is ever out of reach.
 */
export function buildPdfFormModel(pdf: PdfLayoutResponse, fields: readonly EditableField[]): PdfFormModel {
  const fieldsById = new Map(fields.map((field) => [field.fieldId, field]))
  const placed = new Set<string>()
  const pages: PdfPageModel[] = [...(pdf.pages ?? [])]
    .sort((left, right) => left.pageNumber - right.pageNumber)
    .map((page) => {
      const rotation = validRotation(page.rotation)
      const size = { width: page.width, height: page.height }
      const spots: PdfPageSpot[] = []
      ;(pdf.spots ?? []).forEach((spot, index) => {
        if (spot.pageNumber !== page.pageNumber) return
        const field = fieldsById.get(spot.fieldId)
        if (!field || field.cardinality !== 'SCALAR') return
        placed.add(field.fieldId)
        spots.push({
          key: `p${page.pageNumber}/s${index}`,
          fieldId: spot.fieldId,
          inputId: '',
          label: fieldLabel({ fieldId: spot.fieldId, label: field.label ?? spot.label }),
          pageNumber: page.pageNumber,
          box: { ...spot.box },
          shown: boxToDisplayed(spot.box, size, rotation),
          style: spot.style ?? null,
          multiline: spot.multiline === true,
          overflow: spot.overflow === 'BLOCK' ? 'BLOCK' : 'SHRINK_TO_FIT',
          origin: spot.origin,
          formField: spot.bindingKind === 'ACROFORM_FIELD',
        })
      })
      return {
        pageNumber: page.pageNumber,
        width: page.width,
        height: page.height,
        rotation,
        shownWidth: displayedWidth(size, rotation),
        shownHeight: displayedHeight(size, rotation),
        hasText: page.hasText !== false,
        lines: page.lines ?? [],
        spots: readingOrder(spots),
      }
    })
  const ordered = pages.flatMap((page) => page.spots)
  const seen = new Map<string, number>()
  for (const spot of ordered) {
    const count = (seen.get(spot.fieldId) ?? 0) + 1
    seen.set(spot.fieldId, count)
    spot.inputId = count === 1 ? `edit-${spot.fieldId}` : `edit-${spot.fieldId}--${count}`
  }
  const unplaced = fields.filter((field) => !placed.has(field.fieldId))
  return { pages, spots: ordered, unplaced }
}

/**
 * The boxes that sit in a table's cells, as far as the boxes show it: a box that meets another box of its page
 * edge to edge, beside it along the same line or under it in the same column, the way a table's cells do.
 * `within` is how far apart two cells' boxes can be, in points, across the rule between them. By key.
 */
export function boxesInCells(spots: readonly { key: string; shown: PdfBox }[], within = 6): Set<string> {
  const near = (a: number, b: number) => Math.abs(a - b) <= 1.5
  const touching = (end: number, start: number) => start >= end - 1.5 && start - end <= within
  const inCells = new Set<string>()
  for (const one of spots) {
    for (const other of spots) {
      if (one === other) continue
      const a = one.shown
      const b = other.shown
      const sameLine = near(a.y, b.y) && near(a.height, b.height)
      const sameColumn = near(a.x, b.x) && near(a.width, b.width)
      const beside = sameLine && (touching(a.x + a.width, b.x) || touching(b.x + b.width, a.x))
      const under = sameColumn && (touching(a.y + a.height, b.y) || touching(b.y + b.height, a.y))
      if (beside || under) {
        inCells.add(one.key)
        break
      }
    }
  }
  return inCells
}

/** Where a box sits on its page, as percentages of the page shown, so it follows the page at any width. */
export function boxPlacement(shown: PdfBox, page: { shownWidth: number; shownHeight: number }): CssStyle {
  const percent = (value: number, of: number) => `${Math.round((value / of) * 1_000_000) / 10_000}%`
  return {
    left: percent(shown.x, page.shownWidth),
    top: percent(shown.y, page.shownHeight),
    width: percent(shown.width, page.shownWidth),
    height: percent(shown.height, page.shownHeight),
  }
}

/**
 * The point under the pointer, as the page is shown (points from its top-left), kept on the page. `rect` is
 * where the page is drawn on the screen, at whatever size it is zoomed to; null when it is not drawn.
 */
export function pagePoint(
  client: { clientX: number; clientY: number },
  rect: { left: number; top: number; width: number; height: number },
  page: { shownWidth: number; shownHeight: number },
): PdfPoint | null {
  if (!(rect.width > 0) || !(rect.height > 0)) return null
  const scale = rect.width / page.shownWidth
  const clamp = (value: number, most: number) => Math.min(Math.max(value, 0), most)
  return {
    x: clamp((client.clientX - rect.left) / scale, page.shownWidth),
    y: clamp((client.clientY - rect.top) / scale, page.shownHeight),
  }
}

/** About how wide a character of ordinary text is, as a fraction of its size: enough to tell a value that fits one line from one that wraps. */
const AVERAGE_CHARACTER = 0.52

/** What the export keeps clear inside a box Brownie drew, on every side, in points. */
const BOX_PADDING = 1

/**
 * Whether a value sits on one line in the middle of its box. The export centres a single line of text in its box
 * and starts wrapped text at the top; a box Brownie drew wraps what is too long for one line, and one of the form's
 * own fields takes a single line unless the form made it for several. The page does the same, so what is typed
 * sits where the export will put it. Whether a value fits one line is judged by an average width of text.
 */
export function centresLine(
  box: { width: number },
  sizePt: number,
  kind: { multiline: boolean; formField: boolean },
  value: string,
): boolean {
  if (kind.formField) return !kind.multiline
  return [...value].length * sizePt * AVERAGE_CHARACTER <= box.width - 2 * BOX_PADDING
}

const FONT_FAMILIES: Record<PdfTextStyle['font'], string> = {
  SANS: '"Liberation Sans", Arial, Helvetica, sans-serif',
  SERIF: '"Liberation Serif", "Times New Roman", Times, serif',
  MONO: '"Liberation Mono", "Courier New", Courier, monospace',
}

/**
 * How a value looks in its box: the box's own font and size, scaled with the page (the page sets
 * --pdf-point to the width of one point as drawn). A form's own field has no style of its own here;
 * it is drawn at the ordinary size, which is what the filler starts from. The page can set a floor,
 * --pdf-min-text, for the box being typed in, so a page drawn small on a phone is still readable there.
 */
export function spotTextCss(style: PdfTextStyle | null): CssStyle {
  const shown = style ?? DEFAULT_TEXT_STYLE
  const size = Number.isFinite(shown.sizePt) && shown.sizePt > 0 ? Math.min(shown.sizePt, MAX_TEXT_SIZE) : DEFAULT_TEXT_STYLE.sizePt
  return {
    fontFamily: FONT_FAMILIES[shown.font] ?? FONT_FAMILIES.SANS,
    fontWeight: shown.bold ? '700' : '400',
    fontSize: `max(var(--pdf-min-text, 0px), calc(${size} * var(--pdf-point, 1px)))`,
  }
}

// ---- Moving and resizing boxes ------------------------------------------------------------------

export type BoxKey = 'ArrowLeft' | 'ArrowRight' | 'ArrowUp' | 'ArrowDown'

/**
 * A box after one arrow key, as the person sees the page: an arrow moves it 1 point that way (10 with
 * Shift); with Alt, Right and Down make it wider and taller and Left and Up narrower and shorter. It
 * stays whole on its page and never shrinks below the smallest box Brownie accepts. The result is in
 * box space, the way it is stored.
 */
export function nudgeBox(
  box: PdfBox,
  page: { width: number; height: number; rotation: number },
  key: BoxKey,
  options: { large?: boolean; resize?: boolean } = {},
): PdfBox {
  const size = { width: page.width, height: page.height }
  const step = options.large ? 10 : 1
  const shown = boxToDisplayed(box, size, page.rotation)
  const dx = key === 'ArrowRight' ? step : key === 'ArrowLeft' ? -step : 0
  const dy = key === 'ArrowDown' ? step : key === 'ArrowUp' ? -step : 0
  const moved = options.resize
    ? { ...shown, width: shown.width + dx, height: shown.height + dy }
    : { ...shown, x: shown.x + dx, y: shown.y + dy }
  return clampBox(boxFromDisplayed(moved, size, page.rotation), page)
}

export type BoxCorner = 'nw' | 'ne' | 'sw' | 'se'

/** On a turned page a box's width and height swap, so each side as shown keeps the larger of the two smallest sizes. */
const SMALLEST_SIDE = Math.max(MIN_BOX_WIDTH, MIN_BOX_HEIGHT)

/**
 * A box as the page shows it after one of its corners is dragged `dx` and `dy` points: the opposite corner stays where
 * it is, the box stays on the page, and a corner dragged past the other side stops where the box is at its smallest.
 */
export function dragCorner(shown: PdfBox, corner: BoxCorner, dx: number, dy: number, page: { shownWidth: number; shownHeight: number }): PdfBox {
  const between = (value: number, least: number, most: number) => Math.min(Math.max(value, least), most)
  let left = shown.x
  let top = shown.y
  let right = shown.x + shown.width
  let bottom = shown.y + shown.height
  if (corner === 'nw' || corner === 'sw') left = between(left + dx, 0, right - SMALLEST_SIDE)
  else right = between(right + dx, left + SMALLEST_SIDE, page.shownWidth)
  if (corner === 'nw' || corner === 'ne') top = between(top + dy, 0, bottom - SMALLEST_SIDE)
  else bottom = between(bottom + dy, top + SMALLEST_SIDE, page.shownHeight)
  return { x: left, y: top, width: right - left, height: bottom - top }
}

/** A box kept whole on its page and at least the smallest size Brownie accepts; its size is kept before its place. */
export function clampBox(box: PdfBox, page: { width: number; height: number }): PdfBox {
  const width = Math.min(Math.max(box.width, MIN_BOX_WIDTH), page.width)
  const height = Math.min(Math.max(box.height, MIN_BOX_HEIGHT), page.height)
  const x = Math.min(Math.max(box.x, 0), page.width - width)
  const y = Math.min(Math.max(box.y, 0), page.height - height)
  const round = (value: number) => Math.round(value * 100) / 100
  return { x: round(x), y: round(y), width: round(width), height: round(height) }
}

/** Where a box is, in words: its size and its distance from the page's top-left corner as shown. */
export function describeBox(label: string, box: PdfBox, page: { width: number; height: number; rotation: number }): string {
  const shown = boxToDisplayed(box, { width: page.width, height: page.height }, page.rotation)
  const whole = (value: number) => Math.round(value)
  return `${label}: ${whole(shown.width)} by ${whole(shown.height)} points, ${whole(shown.x)} from the left and ${whole(shown.y)} from the top.`
}

// ---- Names --------------------------------------------------------------------------------------

/**
 * A fill spot's name as the server will keep it: spaces tidied, one line of 1 to 60 characters with at
 * least one letter or number. Null when the words cannot be a name.
 */
export function normalizeSpotLabel(text: string): string | null {
  const tidy = text.normalize('NFC').replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\s+/g, ' ').trim()
  if (tidy.length < 1 || [...tidy].length > 60 || !/[\p{L}\p{N}]/u.test(tidy)) return null
  return tidy
}

/** What the person is told when a name is not one. */
export const LABEL_RULE = 'Give the fill spot a name of 1 to 60 characters, with at least one letter or number.'

// ---- Sending a change ---------------------------------------------------------------------------

export type PdfSpotChangeKind = 'add' | 'rename' | 'restyle' | 'remove' | 'move'

/** One change to a PDF form's fill spots as the page asks for it: what it is, the spot it is about, and the changes to send. */
export interface PdfSpotRequest {
  kind: PdfSpotChangeKind
  /** The spot's name (the new name, for a rename); for several moved boxes, the first one's. */
  label: string
  changes: FillSpotChangeRequest[]
}

/** What the fill spot dialog asks about. */
export type PdfSpotDialogMode =
  /** A new box: at a place already chosen (drawn or pointed at), or, with no box, the keyboard's way: a page, then a line. */
  | { kind: 'add'; pageNumber: number | null; box: PdfBox | null; style: PdfTextStyle | null; labelGuess: string | null }
  | { kind: 'rename'; fieldId: string; label: string }
  | { kind: 'restyle'; fieldId: string; label: string; sizePt: number; overflow: PdfOverflow }
  | { kind: 'remove'; fieldId: string; label: string; fromForm: boolean }

/** How a change went: on success, the element to move focus to once any dialog has closed; otherwise why it did not happen. */
export type PdfSpotResult = { ok: true; focusId: string | null } | { ok: false; message: string }

/** What the page says while a change is under way; every change that alters a box is checked by printing the form once. */
export const WORKING_WORDS: Record<PdfSpotChangeKind, string> = {
  add: 'Adding the fill spot… Brownie is checking the form still prints correctly.',
  rename: 'Renaming the fill spot…',
  restyle: 'Changing how the text fits… Brownie is checking the form still prints correctly.',
  remove: 'Removing the fill spot…',
  move: 'Moving the boxes… Brownie is checking the form still prints correctly.',
}

// ---- When a change is refused -------------------------------------------------------------------

const PLACE_REASONS: Record<string, string> = {
  OFF_PAGE: 'That box is off the page.',
  TOO_SMALL: 'That box is too small to hold text; make it bigger.',
  OVERLAPS: 'That box covers another fill spot; move it a little.',
  NOT_FILLABLE: 'Brownie cannot write on that page of the PDF, so a box cannot go there.',
}

/**
 * Why a change to a PDF form's fill spots did not happen, in the person's words. A stale revision (412)
 * is left to the caller, which reloads the document first. `labelOf` names a field the answer points at.
 */
export function spotChangeFailure(error: unknown, labelOf: (fieldId: string) => string, action: 'add' | 'change' = 'change'): string {
  const common = describeCommonFailure(error, 'a way to change fill spots')
  if (common) return common
  if (!(error instanceof ApiRequestError)) return 'Brownie could not change the fill spots. Try again.'
  const problem = (error.problem ?? {}) as { code?: string; reason?: string; fieldId?: string; detail?: string }
  switch (problem.code) {
    case 'FILL_SPOT_PLACE_NOT_ALLOWED':
      return (problem.reason && PLACE_REASONS[problem.reason]) ?? 'A fill spot cannot go there. Choose another place.'
    case 'TEMPLATE_VERSION_MOVED_ON':
      return 'This form changed while you were working, so it was reloaded. Try again.'
    case 'DOCUMENT_TEMPLATE_VERSION_MOVED':
      return 'This document is on an older version of its form. Move it to the newest version first, then try again.'
    case 'FILL_SPOT_ANCHOR_STALE':
      return 'The page changed; choose the place again.'
    case 'FILL_SPOT_LOCKED':
      return problem.fieldId ? `Unlock ${labelOf(problem.fieldId)} first; its value would be lost.` : 'Unlock that fill spot first; its value would be lost.'
    case 'FILL_SPOT_WOULD_NOT_PRINT':
      return action === 'add'
        ? 'Brownie could not add the fill spot, because the form would not print correctly with it. Nothing was changed.'
        : 'Brownie could not make that change, because the form would not print correctly with it. Nothing was changed.'
    case 'FILL_SPOT_CHANGE_INVALID':
      return problem.detail ? `${problem.detail} Nothing was changed.` : 'Brownie could not make that change. Nothing was changed.'
    default:
      if (error.status === 404) return 'This document or its form is no longer available.'
      return error.status < 500 && problem.detail ? problem.detail : 'Brownie could not change the fill spots. Try again.'
  }
}
