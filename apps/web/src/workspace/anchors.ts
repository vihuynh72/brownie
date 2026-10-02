import type { DocxAnchor, TemplateLayoutBlockResponse, TemplateLayoutResponse } from '@/api/client'

/*
 * Choosing a place on a Word form's page for a new fill spot, in the terms the server checks it in.
 *
 * The page draws every paragraph with the node id, the anchor text hash and whether a spot can go in
 * it, and every piece of the form's own text with where it starts in that paragraph's anchor text
 * (the text of the paragraph's own runs), counted in Unicode code points as the server counts them.
 * A place is a paragraph and one or two such offsets, so what the person pointed at, selected or
 * chose from a list becomes exactly the place the server edits, or a refusal it would make anyway,
 * said before anything is sent.
 *
 * Nothing here sends anything; the functions that read the page take the elements they read.
 */

// ---- Code points --------------------------------------------------------------------------------

function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff
}

function isLowSurrogate(code: number): boolean {
  return code >= 0xdc00 && code <= 0xdfff
}

/** How many code points a text holds: an emoji is one, an accent written as its own mark is one more. */
export function codePointLength(text: string): number {
  let count = 0
  for (let index = 0; index < text.length; index++) {
    if (isHighSurrogate(text.charCodeAt(index)) && isLowSurrogate(text.charCodeAt(index + 1))) index++
    count++
  }
  return count
}

/**
 * How many code points come before a UTF-16 offset, which is how the browser counts a place in a text.
 * An offset between the two halves of one character (which a browser does not normally give) counts
 * the character as not reached.
 */
export function codePointsBefore(text: string, utf16Offset: number): number {
  const end = Math.max(0, Math.min(utf16Offset, text.length))
  let count = 0
  for (let index = 0; index < end; index++) {
    if (isHighSurrogate(text.charCodeAt(index)) && isLowSurrogate(text.charCodeAt(index + 1))) {
      if (index + 1 >= end) break
      index++
    }
    count++
  }
  return count
}

/** The UTF-16 offset of a code point offset; past the end is the end. */
export function utf16Offset(text: string, codePoints: number): number {
  let index = 0
  for (let count = 0; count < codePoints && index < text.length; count++) {
    index += isHighSurrogate(text.charCodeAt(index)) && isLowSurrogate(text.charCodeAt(index + 1)) ? 2 : 1
  }
  return index
}

/** The text between two code point offsets. */
export function codePointSlice(text: string, start: number, end?: number): string {
  const from = utf16Offset(text, start)
  return end === undefined ? text.slice(from) : text.slice(from, utf16Offset(text, end))
}

/**
 * The code point offsets where one character as a reader sees it ends and the next begins: an accent
 * written as its own mark stays with its letter, and a family emoji made of several code points is one.
 * A place is never put inside one of these.
 */
function graphemeBoundaries(text: string): number[] {
  const boundaries = [0]
  const Segmenter = (Intl as { Segmenter?: typeof Intl.Segmenter }).Segmenter
  if (typeof Segmenter === 'function') {
    let codePoints = 0
    for (const { segment } of new Segmenter(undefined, { granularity: 'grapheme' }).segment(text)) {
      codePoints += codePointLength(segment)
      boundaries.push(codePoints)
    }
    return boundaries
  }
  // Without a segmenter, a combining mark (and a joiner or a variation selector) at least stays with what it follows.
  let codePoints = 0
  for (const character of text) {
    codePoints += 1
    if (!/^[\p{M}\u200d\ufe0e\ufe0f]$/u.test(character)) boundaries.push(codePoints)
    else boundaries[boundaries.length - 1] = codePoints
  }
  return boundaries.filter((value, index, all) => index === 0 || value !== all[index - 1])
}

/** The nearest whole-character offset at or before (or, going forward, at or after) a code point offset. */
export function snapToCharacter(text: string, offset: number, direction: 'back' | 'forward'): number {
  const boundaries = graphemeBoundaries(text)
  if (direction === 'back') {
    let result = 0
    for (const boundary of boundaries) if (boundary <= offset) result = boundary
    return result
  }
  for (const boundary of boundaries) if (boundary >= offset) return boundary
  return boundaries[boundaries.length - 1] ?? 0
}

// ---- The lines of a form --------------------------------------------------------------------------

/** A piece of the line that comes from a control no field names: making it a fill spot names that control. */
export interface LineControl {
  controlNodeId: string
  text: string
  /** Where it sits in the anchor text. */
  at: number
}

/** One paragraph of the body that can take a fill spot, as the page and the list of lines show it. */
export interface AnchorLine {
  nodeId: string
  anchorTextHash: string | null
  /** The paragraph's anchor text; every offset in a place counts into it. */
  text: string
  controls: LineControl[]
  /** Where a line in a table sits ("Table 1, row 2, column 1"); null in the flow of the text. */
  where: string | null
  /** The words in the cell to its left, for a line in a table; they often name what goes in it. */
  besideText: string | null
}

/**
 * A character that stands for text the layout does not show, so that a gap never shifts the offsets
 * after it; it is never part of a choice's words.
 */
const UNSEEN = '\ufffc'

/** A paragraph's anchor text from its pieces, each put where the layout says it starts. */
export function anchorTextOf(pieces: readonly { anchorStart: number; text: string }[]): string {
  let text = ''
  let length = 0
  for (const piece of [...pieces].sort((left, right) => left.anchorStart - right.anchorStart)) {
    if (piece.anchorStart > length) {
      text += UNSEEN.repeat(piece.anchorStart - length)
      length = piece.anchorStart
    }
    // A piece that overlaps the one before it adds only what goes past it.
    const skip = length - piece.anchorStart
    const added = skip > 0 ? codePointSlice(piece.text, skip) : piece.text
    text += added
    length += codePointLength(added)
  }
  return text
}

function lineFromBlock(block: TemplateLayoutBlockResponse, where: string | null, besideText: string | null): AnchorLine | null {
  if (block.kind !== 'PARAGRAPH' || block.anchorable !== true || typeof block.nodeId !== 'string' || block.repeating) return null
  const pieces: { anchorStart: number; text: string }[] = []
  const controls: LineControl[] = []
  let running = 0
  for (const inline of block.inlines ?? []) {
    if (inline.kind !== 'TEXT' || !inline.text) continue
    if (typeof inline.anchorStart === 'number' && inline.anchorStart >= 0) {
      pieces.push({ anchorStart: inline.anchorStart, text: inline.text })
      running = inline.anchorStart + codePointLength(inline.text)
    } else if (inline.controlNodeId) {
      const known = controls.find((control) => control.controlNodeId === inline.controlNodeId)
      if (known) known.text += inline.text
      else controls.push({ controlNodeId: inline.controlNodeId, text: inline.text, at: running })
    }
  }
  return { nodeId: block.nodeId, anchorTextHash: block.anchorTextHash ?? null, text: anchorTextOf(pieces), controls, where, besideText }
}

/** Every piece of text a cell shows, for the words beside a line in the next cell. */
function cellWords(blocks: readonly TemplateLayoutBlockResponse[] | null | undefined): string {
  const words: string[] = []
  for (const block of blocks ?? []) {
    if (block.kind === 'PARAGRAPH') {
      words.push((block.inlines ?? []).map((inline) => (inline.kind === 'TEXT' ? (inline.text ?? '') : '')).join(''))
    }
  }
  return words.join(' ').replace(/\s+/g, ' ').trim()
}

/**
 * Every line of the form's body that can take a fill spot, in reading order: what the keyboard route
 * lists, and where a place chosen on the page is looked up. Headers, footers and the part that repeats
 * for each row are left out, as the server leaves them out.
 */
export function anchorableLines(layout: TemplateLayoutResponse | null | undefined): AnchorLine[] {
  const lines: AnchorLine[] = []
  const main = (layout?.parts ?? []).find((part) => part.kind === 'MAIN_DOCUMENT')
  let tables = 0
  const collect = (blocks: readonly TemplateLayoutBlockResponse[] | null | undefined, where: string | null, besideText: string | null) => {
    for (const block of blocks ?? []) {
      if (block.kind === 'TABLE') {
        tables += 1
        const table = tables
        ;(block.rows ?? []).forEach((row, rowIndex) => {
          if (row.repeating) return
          const cells = row.cells ?? []
          cells.forEach((cell, column) => {
            const beside = column > 0 ? cellWords(cells[column - 1]?.blocks) || null : null
            collect(cell.blocks, `Table ${table}, row ${rowIndex + 1}, column ${column + 1}`, beside)
          })
        })
      } else {
        const line = lineFromBlock(block, where, besideText)
        if (line) lines.push(line)
      }
    }
  }
  collect(main?.blocks, null, null)
  return lines
}

/** How a line reads in a list or a sentence: its words on one line, and something for a line with none. */
export function lineWords(line: AnchorLine): string {
  const words = line.text.split(UNSEEN).join('').replace(/\s+/g, ' ').trim()
  const controls = line.controls.map((control) => control.text.replace(/\s+/g, ' ').trim()).filter(Boolean)
  if (words === '' && controls.length > 0) return controls.join(' ')
  return words === '' ? '(empty paragraph)' : words
}

// ---- Places in a line -----------------------------------------------------------------------------

export type Placement = DocxAnchor['placement']

/** One place in a line a fill spot can go. */
export interface PlaceOption {
  key: string
  placement: Placement
  start: number
  end: number
  controlNodeId: string | null
  /** The choice as a list of places words it: "Replace '________'", "After 'Company:'". */
  words: string
  /** Where it goes, after "a fill spot for Company": after "Company:", in place of "________". */
  where: string
}

/** A run of underscores, dots or an ellipsis: the form's own blank to write on. */
const BLANK = /_{3,}|\uff3f{2,}|\.{4,}|\u2026+/gu
/** A bracketed prompt such as [Company name]. */
const BRACKETED = /\[[^[\]\r\n]{1,80}\]/gu
/** Words that end with a colon: "Company:", "Date of birth :". */
const COLON_LABEL = /[^\t:\uff1a_.\u2026[\]\n]*[\p{L})]\s?[:\uff1a]/gu

/** A line that holds nothing but a blank or one bracketed prompt, which a spot can take the place of whole. */
export function isOnlyABlank(text: string): boolean {
  const trimmed = text.split(UNSEEN).join('').trim()
  if (trimmed === '') return false
  if (/^\[[^[\]\r\n]{1,80}\]$/u.test(trimmed)) return true
  return /^[\s_.\u2026\uff3f-]+$/u.test(trimmed) && /[_.\u2026\uff3f]/u.test(trimmed)
}

/** Quoted words for a choice or a sentence, shortened when long. */
function shortWords(text: string, max = 40): string {
  const words = text.split(UNSEEN).join('').replace(/\s+/g, ' ').trim()
  return codePointLength(words) > max ? `${codePointSlice(words, 0, max - 1).trimEnd()}\u2026` : words
}

function option(placement: Placement, start: number, end: number, controlNodeId: string | null, words: string, where: string): PlaceOption {
  return { key: `${placement}:${start}:${end}:${controlNodeId ?? ''}`, placement, start, end, controlNodeId, words, where }
}

function matchesOf(pattern: RegExp, text: string): { start: number; end: number; text: string }[] {
  const result: { start: number; end: number; text: string }[] = []
  for (const match of text.matchAll(new RegExp(pattern.source, pattern.flags))) {
    const start = codePointsBefore(text, match.index)
    result.push({ start, end: start + codePointLength(match[0]), text: match[0] })
  }
  return result
}

function isSpace(character: string | undefined): boolean {
  return character !== undefined && /^[\s\u00a0]$/u.test(character)
}

function replaceOption(text: string, start: number, end: number): PlaceOption {
  const words = shortWords(codePointSlice(text, start, end))
  return option('REPLACE', start, end, null, `Replace '${words}'`, `in place of "${words}"`)
}

function endOfLineOption(text: string): PlaceOption {
  const length = codePointLength(text)
  const line = shortWords(text, 60)
  return option('AT', length, length, null, 'At the end of the paragraph', line ? `at the end of the paragraph "${line}"` : 'at the end of the paragraph')
}

function wholeLineOption(text: string): PlaceOption {
  const empty = text.split(UNSEEN).join('').trim() === ''
  return empty
    ? option('WHOLE_LINE', 0, 0, null, 'In this empty paragraph', 'in an empty paragraph')
    : option('WHOLE_LINE', 0, codePointLength(text), null, 'Instead of the whole paragraph', `in place of "${shortWords(text)}"`)
}

function controlOption(control: LineControl): PlaceOption {
  const words = shortWords(control.text)
  return option('EXISTING_CONTROL', control.at, control.at, control.controlNodeId, `Replace '${words}'`, `in place of "${words}"`)
}

/** A point in the line, worded by what comes before it. */
function pointOption(text: string, offset: number): PlaceOption {
  const length = codePointLength(text)
  if (offset >= length) return endOfLineOption(text)
  const before = codePointSlice(text, 0, offset).trimEnd()
  if (before.split(UNSEEN).join('').trim() === '') return option('AT', offset, offset, null, 'At the start of the paragraph', 'at the start of the paragraph')
  const words = shortWords(before.split(/\s+/u).slice(-3).join(' '))
  return option('AT', offset, offset, null, `After '${words}'`, `after "${words}"`)
}

/**
 * The places a fill spot can go in a line, in the order they sit in it: each blank ("Replace
 * '________'"), each bracketed prompt, right after each label that ends in a colon (unless a blank
 * follows it, which is the better place), each piece of text from a control the form left unnamed,
 * the end of the line, and the whole line when the line holds only a blank.
 */
export function placementsForLine(line: AnchorLine): PlaceOption[] {
  const text = line.text
  const length = codePointLength(text)
  if (text.split(UNSEEN).join('').trim() === '') {
    return [...line.controls.map(controlOption), wholeLineOption(text)]
  }
  const tokens = [...matchesOf(BLANK, text), ...matchesOf(BRACKETED, text)]
  const placed: PlaceOption[] = tokens.map((token) => replaceOption(text, token.start, token.end))
  for (const label of matchesOf(COLON_LABEL, text)) {
    let at = label.end
    // One space after the colon stays with the label, so the value reads "Company: Acme".
    if (isSpace(codePointSlice(text, at, at + 1))) at += 1
    let next = at
    while (next < length && isSpace(codePointSlice(text, next, next + 1))) next += 1
    if (tokens.some((token) => token.start === next)) continue
    const words = shortWords(label.text)
    placed.push(option('AT', at, at, null, `After '${words}'`, `after "${words}"`))
  }
  placed.push(...line.controls.map(controlOption))
  placed.sort((left, right) => left.start - right.start || left.end - right.end)
  const only = isOnlyABlank(text)
  if (!only) placed.push(endOfLineOption(text))
  if (only) placed.push(wholeLineOption(text))
  const seen = new Set<string>()
  return placed.filter((place) => (seen.has(place.key) ? false : (seen.add(place.key), true)))
}

/**
 * The place a click or a caret in a line stands for: the blank or bracketed prompt it is on, if any;
 * otherwise the point itself, moved to the end of a word it falls inside, since a value is never
 * written into the middle of a word.
 */
export function placeForCaret(line: AnchorLine, offset: number): PlaceOption {
  const text = line.text
  const length = codePointLength(text)
  if (text.split(UNSEEN).join('').trim() === '') return wholeLineOption(text)
  const point = Math.max(0, Math.min(offset, length))
  const token = [...matchesOf(BLANK, text), ...matchesOf(BRACKETED, text)].find((match) => match.start <= point && point <= match.end)
  if (token) return replaceOption(text, token.start, token.end)
  let at = snapToCharacter(text, point, 'back')
  const characterAt = (index: number) => codePointSlice(text, index, index + 1)
  if (at > 0 && !isSpace(characterAt(at - 1))) {
    while (at < length && !isSpace(characterAt(at))) at += 1
  }
  return pointOption(text, at)
}

/**
 * The place a selection in a line stands for: the selected words, which the spot takes the place of.
 * Spaces at either edge are left out, unless the selection is nothing but spaces (some forms' blank is
 * a row of underlined spaces).
 */
export function placeForSelection(line: AnchorLine, start: number, end: number): PlaceOption {
  const text = line.text
  let from = Math.max(0, Math.min(start, end))
  let to = Math.min(codePointLength(text), Math.max(start, end))
  if (from === to) return placeForCaret(line, from)
  const characterAt = (index: number) => codePointSlice(text, index, index + 1)
  let trimmedFrom = from
  let trimmedTo = to
  while (trimmedFrom < trimmedTo && isSpace(characterAt(trimmedFrom))) trimmedFrom += 1
  while (trimmedTo > trimmedFrom && isSpace(characterAt(trimmedTo - 1))) trimmedTo -= 1
  if (trimmedFrom < trimmedTo) {
    from = trimmedFrom
    to = trimmedTo
  }
  from = snapToCharacter(text, from, 'back')
  to = snapToCharacter(text, to, 'forward')
  return replaceOption(text, from, to)
}

/** The place for a control's text, chosen on the page. */
export function placeForControl(line: AnchorLine, controlNodeId: string): PlaceOption | null {
  const control = line.controls.find((candidate) => candidate.controlNodeId === controlNodeId)
  return control ? controlOption(control) : null
}

// ---- Names ------------------------------------------------------------------------------------------

const MAX_LABEL = 60

function tidyLabel(words: string): string {
  const label = words
    .replace(/[_.\u2026\uff3f]{2,}/gu, ' ')
    .replace(/[:\uff1a]+\s*$/u, '')
    .replace(/^[^\p{L}\p{N}]+/u, '')
    // A list number before the words ("1.", "b)") is not part of the name.
    .replace(/^(?:\d{1,3}|[a-z])[.)]\s+/iu, '')
    .replace(/[^\p{L}\p{N})]+$/u, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (label === '') return ''
  const short = codePointLength(label) > MAX_LABEL ? codePointSlice(label, 0, MAX_LABEL).trimEnd() : label
  return short.charAt(0).toLocaleUpperCase() + short.slice(1)
}

/** The words right before a place that end in a colon: the name the form itself gives what goes there. */
function labelBefore(text: string, offset: number): string {
  const before = codePointSlice(text, 0, offset).split(UNSEEN).join('')
  const trimmed = before.replace(/[\s\u00a0]+$/u, '')
  if (!/[:\uff1a]$/u.test(trimmed)) return ''
  // The label starts after the last blank, tab, run of spaces or earlier colon before it.
  const parts = trimmed.slice(0, -1).split(/_{2,}|\uff3f+|\.{3,}|\u2026+|\t|\s{2,}|[:\uff1a|;]/u)
  const last = parts[parts.length - 1] ?? ''
  return last.trim().split(/\s+/u).slice(-6).join(' ')
}

/**
 * A name to offer for a new fill spot: the words of a bracketed prompt it takes the place of ("[Company
 * name]" gives "Company name"), else the label before it ("Company:" gives "Company"), else, for an
 * otherwise empty line in a table, the words in the cell to its left. Empty when there is nothing to go on.
 */
export function suggestLabel(line: AnchorLine, place: PlaceOption): string {
  const text = line.text
  if (place.placement === 'REPLACE' || place.placement === 'WHOLE_LINE') {
    const covered = codePointSlice(text, place.start, place.end).trim()
    const bracket = /^\[([^[\]\r\n]{1,80})\]$/u.exec(covered)
    if (bracket) return tidyLabel(bracket[1]!)
  }
  if (place.placement === 'EXISTING_CONTROL') {
    const control = line.controls.find((candidate) => candidate.controlNodeId === place.controlNodeId)
    const bracket = control ? /^\s*\[([^[\]\r\n]{1,80})\]\s*$/u.exec(control.text) : null
    if (bracket) return tidyLabel(bracket[1]!)
  }
  const fromLabel = labelBefore(text, place.start)
  if (fromLabel) return tidyLabel(fromLabel)
  const lineIsBlank = text.split(UNSEEN).join('').trim() === '' || isOnlyABlank(text)
  if (lineIsBlank && line.besideText) return tidyLabel(line.besideText)
  return ''
}

/** Text or Date for a name: a name about a date or a day is a date. */
export function suggestType(label: string): 'TEXT' | 'DATE' {
  return /\b(date|dated|day|birthday|deadline|due)\b|\bdate of\b|\bDOB\b/iu.test(label) ? 'DATE' : 'TEXT'
}

// ---- What is sent ---------------------------------------------------------------------------------

/** The place as the server takes it. */
export function toDocxAnchor(line: AnchorLine, place: PlaceOption, parserVersion: string): DocxAnchor {
  return {
    part: 'MAIN_DOCUMENT',
    paragraphNodeId: line.nodeId,
    placement: place.placement,
    start: place.start,
    end: place.end,
    anchorTextHash: line.anchorTextHash,
    parserVersion,
    controlNodeId: place.controlNodeId,
  }
}

// ---- Reading a place off the page ------------------------------------------------------------------

/** What a place on the page is, before it is looked up in the lines. */
export interface PagePoint {
  nodeId: string
  anchorTextHash: string | null
  kind: 'caret' | 'selection' | 'control'
  start: number
  end: number
  controlNodeId: string | null
}

/** Why a place on the page cannot take a fill spot. */
export type PlaceRefusal =
  | 'NOT_ON_PAGE'
  | 'ACROSS_LINES'
  | 'HEADER_FOOTER'
  | 'REPEATING'
  | 'NOT_ANCHORABLE'
  | 'IN_FILL_SPOT'
  | 'ACROSS_FILL_SPOT'

export const PLACE_REFUSAL_WORDS: Record<PlaceRefusal, string> = {
  NOT_ON_PAGE: "Select a place in the document's text first.",
  ACROSS_LINES: 'Select within one paragraph to add a fill spot there.',
  HEADER_FOOTER: 'Brownie fills only the body of the form, not its header or footer.',
  REPEATING: 'This part of the form repeats for each row, so a single fill spot cannot go here.',
  NOT_ANCHORABLE: 'Brownie cannot add a fill spot in this paragraph. Choose another paragraph.',
  IN_FILL_SPOT: "That is a fill spot already. Choose a place in the form's own words.",
  ACROSS_FILL_SPOT: 'Select words on one side of a fill spot, not across it.',
}

export type PageReading = { point: PagePoint } | { refusal: PlaceRefusal }

/** A boundary of a selection or a caret: a node and an offset in it, as a Range gives them. */
export interface Boundary {
  node: Node
  offset: number
}

function elementOf(node: Node): Element | null {
  return node.nodeType === Node.ELEMENT_NODE ? (node as Element) : node.parentElement
}

/** The paragraph the page drew for a boundary, if it is one of the form's lines. */
function paragraphOf(node: Node, root: Element): HTMLElement | null {
  const paragraph = elementOf(node)?.closest<HTMLElement>('[data-part]') ?? null
  return paragraph && root.contains(paragraph) ? paragraph : null
}

function anchoredSpans(paragraph: Element): HTMLElement[] {
  return [...paragraph.querySelectorAll<HTMLElement>('[data-anchor-start]')]
}

function anchorStartOf(span: HTMLElement): number {
  const value = Number(span.dataset.anchorStart)
  return Number.isInteger(value) && value >= 0 ? value : 0
}

/** The paragraph's anchor text as the page drew it. */
export function anchorTextOfParagraph(paragraph: Element): string {
  return anchorTextOf(anchoredSpans(paragraph).map((span) => ({ anchorStart: anchorStartOf(span), text: span.textContent ?? '' })))
}

/** Whether a boundary comes after the end of an element. */
function boundaryIsAfter(boundary: Boundary, element: Element): boolean {
  const range = element.ownerDocument.createRange()
  range.selectNodeContents(element)
  // The end of the element's contents is at or before the boundary.
  return range.comparePoint(boundary.node, boundary.offset) >= 0
}

/** Whether a boundary sits before any of a paragraph's text. */
function nothingBefore(boundary: Boundary, paragraph: Element): boolean {
  const range = paragraph.ownerDocument.createRange()
  range.setStart(paragraph, 0)
  range.setEnd(boundary.node, boundary.offset)
  return range.toString() === ''
}

/** Whether the stretch between two boundaries takes in one of the page's fill spots, which a new spot cannot replace. */
function coversAFillSpot(start: Boundary, end: Boundary, paragraph: Element): boolean {
  const range = paragraph.ownerDocument.createRange()
  range.setStart(start.node, start.offset)
  range.setEnd(end.node, end.offset)
  if (range.collapsed) {
    // Given the other way round, the range collapsed onto its end; the stretch runs from end to start.
    range.setStart(end.node, end.offset)
    range.setEnd(start.node, start.offset)
  }
  if (range.collapsed) return false
  return [...paragraph.querySelectorAll('.fill-spot')].some((spot) => range.intersectsNode(spot))
}

/**
 * A boundary as an offset into the paragraph's anchor text, or the control it is in. Inside a piece of
 * the form's text it counts the code points before it; anywhere else (between pieces, next to a fill
 * spot, on the paragraph itself) it is where the last piece before it ends.
 */
function offsetOf(boundary: Boundary, paragraph: Element): { offset: number } | { control: string } {
  const element = elementOf(boundary.node)
  const control = element?.closest<HTMLElement>('[data-control-node-id]')
  if (control && paragraph.contains(control) && !control.hasAttribute('data-anchor-start')) {
    return { control: control.dataset.controlNodeId! }
  }
  const span = element?.closest<HTMLElement>('[data-anchor-start]')
  if (span && paragraph.contains(span)) {
    if (boundary.node.nodeType === Node.TEXT_NODE) {
      // Text before this text node inside the span (the span normally holds one) counts too.
      let before = 0
      for (const child of span.childNodes) {
        if (child === boundary.node) break
        before += codePointLength(child.textContent ?? '')
      }
      return { offset: anchorStartOf(span) + before + codePointsBefore(boundary.node.textContent ?? '', boundary.offset) }
    }
    const wholeText = span.textContent ?? ''
    return { offset: anchorStartOf(span) + (boundary.offset === 0 ? 0 : codePointLength(wholeText)) }
  }
  let offset: number | null = null
  let firstStart: number | null = null
  for (const piece of anchoredSpans(paragraph)) {
    if (firstStart === null) firstStart = anchorStartOf(piece)
    if (boundaryIsAfter(boundary, piece)) offset = anchorStartOf(piece) + codePointLength(piece.textContent ?? '')
    else break
  }
  return { offset: offset ?? firstStart ?? 0 }
}

/**
 * What a selection or a caret on the page stands for: a point, a stretch of one line, or a control's
 * text; or why no fill spot can go there. Both ends must be in the same line, and that line must be one
 * a spot can go in.
 */
export function readPlace(start: Boundary, end: Boundary, root: Element): PageReading {
  const first = paragraphOf(start.node, root)
  let last = paragraphOf(end.node, root)
  if (first && last && first !== last && nothingBefore(end, last)) {
    // A whole line selected (three clicks) ends at the very start of the next one: it is still that one line.
    end = { node: first, offset: first.childNodes.length }
    last = first
  }
  if (!first || !last) {
    const inSpot = [start.node, end.node].some((node) => elementOf(node)?.closest('.fill-spot') && root.contains(elementOf(node)))
    return { refusal: inSpot ? 'IN_FILL_SPOT' : 'NOT_ON_PAGE' }
  }
  if (first !== last) return { refusal: 'ACROSS_LINES' }
  if ([start.node, end.node].some((node) => elementOf(node)?.closest('.fill-spot'))) return { refusal: 'IN_FILL_SPOT' }
  const paragraph = first
  if (paragraph.dataset.anchorable !== 'true' || !paragraph.dataset.nodeId) {
    if (paragraph.dataset.part === 'HEADER' || paragraph.dataset.part === 'FOOTER') return { refusal: 'HEADER_FOOTER' }
    if (paragraph.dataset.repeats === 'true') return { refusal: 'REPEATING' }
    return { refusal: 'NOT_ANCHORABLE' }
  }
  const nodeId = paragraph.dataset.nodeId
  const anchorTextHash = paragraph.dataset.anchorHash ?? null
  if (coversAFillSpot(start, end, paragraph)) return { refusal: 'ACROSS_FILL_SPOT' }
  const from = offsetOf(start, paragraph)
  const to = offsetOf(end, paragraph)
  // A selection that reaches into the text of a control no field names makes that control the spot.
  const control = 'control' in from ? from.control : 'control' in to ? to.control : null
  if (control) return { point: { nodeId, anchorTextHash, kind: 'control', start: 0, end: 0, controlNodeId: control } }
  const text = anchorTextOfParagraph(paragraph)
  const a = (from as { offset: number }).offset
  const b = (to as { offset: number }).offset
  const low = Math.min(a, b)
  const high = Math.max(a, b)
  if (low === high) {
    const at = snapToCharacter(text, low, 'back')
    return { point: { nodeId, anchorTextHash, kind: 'caret', start: at, end: at, controlNodeId: null } }
  }
  return {
    point: {
      nodeId,
      anchorTextHash,
      kind: 'selection',
      start: snapToCharacter(text, low, 'back'),
      end: snapToCharacter(text, high, 'forward'),
      controlNodeId: null,
    },
  }
}

/** The place a page point stands for in its line; null when the line is not among the lines, which means the page changed. */
export function placeOnLine(line: AnchorLine, point: PagePoint): PlaceOption | null {
  if (point.kind === 'control') return point.controlNodeId ? placeForControl(line, point.controlNodeId) : null
  if (point.kind === 'selection') return placeForSelection(line, point.start, point.end)
  return placeForCaret(line, point.start)
}

/** Where a caret falls under a pointer, through whichever way the browser has of saying it. */
export function caretFromPoint(doc: Document, x: number, y: number): Boundary | null {
  const withCaret = doc as Document & {
    caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null
    caretRangeFromPoint?: (x: number, y: number) => Range | null
  }
  if (typeof withCaret.caretPositionFromPoint === 'function') {
    const position = withCaret.caretPositionFromPoint(x, y)
    if (position) return { node: position.offsetNode, offset: position.offset }
  }
  if (typeof withCaret.caretRangeFromPoint === 'function') {
    const range = withCaret.caretRangeFromPoint(x, y)
    if (range) return { node: range.startContainer, offset: range.startOffset }
  }
  return null
}
