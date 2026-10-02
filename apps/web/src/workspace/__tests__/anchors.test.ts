import { afterEach, describe, expect, it } from 'vitest'
import type { TemplateLayoutBlockResponse, TemplateLayoutInlineResponse, TemplateLayoutResponse } from '@/api/client'
import {
  anchorTextOf,
  anchorableLines,
  caretFromPoint,
  codePointLength,
  codePointSlice,
  codePointsBefore,
  isOnlyABlank,
  lineWords,
  placeForCaret,
  placeForSelection,
  placeOnLine,
  placementsForLine,
  readPlace,
  snapToCharacter,
  suggestLabel,
  suggestType,
  toDocxAnchor,
  utf16Offset,
  type AnchorLine,
} from '@/workspace/anchors'

const EMOJI = String.fromCodePoint(0x1f600)
const ACUTE = String.fromCharCode(0x0301)
/** A family: woman, zero-width joiner, woman, zero-width joiner, girl; five code points, one character to a reader. */
const FAMILY = [0x1f469, 0x200d, 0x1f469, 0x200d, 0x1f467].map((code) => String.fromCodePoint(code)).join('')

function line(text: string, extra: Partial<AnchorLine> = {}): AnchorLine {
  return { nodeId: 'p3', anchorTextHash: 'hash-p3', text, controls: [], where: null, besideText: null, ...extra }
}

describe('counting in code points', () => {
  it('counts an emoji as one and an accent written as its own mark as one more', () => {
    expect(codePointLength(`Caf${'e'}${ACUTE} ${EMOJI}`)).toBe(7)
    expect(codePointLength(FAMILY)).toBe(5)
    expect(`${EMOJI}`.length).toBe(2)
  })

  it('turns a browser offset into code points, never counting half a character', () => {
    const text = `a${EMOJI}b`
    expect(codePointsBefore(text, 0)).toBe(0)
    expect(codePointsBefore(text, 1)).toBe(1)
    expect(codePointsBefore(text, 2)).toBe(1)
    expect(codePointsBefore(text, 3)).toBe(2)
    expect(codePointsBefore(text, 4)).toBe(3)
    expect(codePointsBefore(text, 99)).toBe(3)
  })

  it('goes back from code points to the browser offset and slices by code points', () => {
    const text = `${EMOJI}${EMOJI}x`
    expect(utf16Offset(text, 1)).toBe(2)
    expect(utf16Offset(text, 3)).toBe(5)
    expect(codePointSlice(text, 1, 2)).toBe(EMOJI)
    expect(codePointSlice(text, 2)).toBe('x')
  })

  it('never puts a place between a letter and its accent, or inside a family emoji', () => {
    const text = `e${ACUTE}x${FAMILY}y`
    expect(snapToCharacter(text, 1, 'back')).toBe(0)
    expect(snapToCharacter(text, 1, 'forward')).toBe(2)
    expect(snapToCharacter(text, 5, 'back')).toBe(3)
    expect(snapToCharacter(text, 5, 'forward')).toBe(8)
    expect(snapToCharacter(text, 9, 'back')).toBe(9)
  })
})

describe('the lines of a form', () => {
  function text(value: string, anchorStart: number | null, controlNodeId: string | null = null): TemplateLayoutInlineResponse {
    return { kind: 'TEXT', text: value, anchorStart, controlNodeId }
  }

  function paragraph(nodeId: string, inlines: TemplateLayoutInlineResponse[], extra: Partial<TemplateLayoutBlockResponse> = {}): TemplateLayoutBlockResponse {
    return { kind: 'PARAGRAPH', repeating: false, inlines, nodeId, anchorable: true, anchorTextHash: `hash-${nodeId}`, ...extra }
  }

  const LAYOUT: TemplateLayoutResponse = {
    templateId: 1,
    versionId: 4,
    parserVersion: 'brownie-docx-graph-v3+poi-5.5.1',
    parts: [
      {
        kind: 'MAIN_DOCUMENT',
        blocks: [
          paragraph('p0', [text('Company: ', 0), { kind: 'FILL_SPOT', fieldId: 'company' }, text('________', 9)]),
          paragraph('p1', [text('Signed on ', 0), text('[date]', null, 'p1/sdt1'), text(' in Hanoi', 10)]),
          paragraph('p2', [text('Internal', null)], { anchorable: false, anchorTextHash: null }),
          {
            kind: 'TABLE',
            repeating: false,
            rows: [
              { repeating: false, cells: [{ blocks: [paragraph('tbl3/row0/cell0/p0', [text('Phone', 0)])] }, { blocks: [paragraph('tbl3/row0/cell1/p0', [])] }] },
              { repeating: true, cells: [{ blocks: [paragraph('tbl3/row1/cell0/p0', [text('Item', 0)])] }] },
            ],
          },
          paragraph('p4', [text('Items', 0)], { repeating: true }),
        ],
      },
      { kind: 'HEADER', blocks: [paragraph('p0', [text('Header', 0)])] },
    ],
    unplacedFieldIds: [],
  }

  it('lists the body lines a fill spot can go in, in reading order, with their anchor text', () => {
    const lines = anchorableLines(LAYOUT)

    expect(lines.map((each) => each.nodeId)).toEqual(['p0', 'p1', 'tbl3/row0/cell0/p0', 'tbl3/row0/cell1/p0'])
    expect(lines[0]).toMatchObject({ text: 'Company: ________', anchorTextHash: 'hash-p0', where: null })
    expect(lines[1]).toMatchObject({ text: 'Signed on  in Hanoi', controls: [{ controlNodeId: 'p1/sdt1', text: '[date]', at: 10 }] })
    expect(lines[3]).toMatchObject({ text: '', where: 'Table 1, row 1, column 2', besideText: 'Phone' })
  })

  it('keeps every offset where the layout puts it, even past text it does not show', () => {
    expect(anchorTextOf([{ anchorStart: 0, text: 'ab' }, { anchorStart: 4, text: 'cd' }])).toBe('ab\ufffc\ufffccd')
    expect(anchorTextOf([{ anchorStart: 2, text: 'cd' }, { anchorStart: 0, text: 'abc' }])).toBe('abcd')
  })

  it('words a line for a list, and says so when it has no words', () => {
    const lines = anchorableLines(LAYOUT)
    expect(lineWords(lines[1]!)).toBe('Signed on in Hanoi')
    expect(lineWords(lines[3]!)).toBe('(empty paragraph)')
    expect(lineWords(line('', { controls: [{ controlNodeId: 'p1/sdt0', text: 'Click here', at: 0 }] }))).toBe('Click here')
  })

  it('has no lines without a layout', () => {
    expect(anchorableLines(null)).toEqual([])
  })
})

describe('the places in a line', () => {
  const words = (target: AnchorLine) => placementsForLine(target).map((place) => place.words)

  it('offers the blank after a label rather than a second place right before it', () => {
    const places = placementsForLine(line('Company: ________'))
    expect(places.map((place) => place.words)).toEqual(["Replace '________'", 'At the end of the paragraph'])
    expect(places[0]).toMatchObject({ placement: 'REPLACE', start: 9, end: 17, where: 'in place of "________"' })
    expect(places[1]).toMatchObject({ placement: 'AT', start: 17, end: 17, where: 'at the end of the paragraph "Company: ________"' })
  })

  it('offers the place after each label that no blank follows, and each blank', () => {
    expect(words(line('Name: ________   Date:   Place: [town]'))).toEqual([
      "Replace '________'",
      "After 'Date:'",
      "Replace '[town]'",
      'At the end of the paragraph',
    ])
    const after = placementsForLine(line('Date:  Time:')).find((place) => place.words === "After 'Date:'")!
    // The one space after the colon stays with the label.
    expect(after).toMatchObject({ placement: 'AT', start: 6, end: 6, where: 'after "Date:"' })
  })

  it('offers the place after a label at the end of the paragraph once', () => {
    expect(words(line('Company:'))).toEqual(["After 'Company:'"])
  })

  it('offers the whole line when it holds only a blank or a prompt', () => {
    expect(words(line('[Company name]'))).toEqual(["Replace '[Company name]'", 'Instead of the whole paragraph'])
    expect(words(line('  ____________  '))).toEqual(["Replace '____________'", 'Instead of the whole paragraph'])
    expect(placementsForLine(line('..........')).at(-1)).toMatchObject({ placement: 'WHOLE_LINE', start: 0, end: 10 })
    expect(isOnlyABlank('Company: ____')).toBe(false)
  })

  it('offers an empty line as a whole', () => {
    expect(placementsForLine(line(''))).toEqual([
      { key: 'WHOLE_LINE:0:0:', placement: 'WHOLE_LINE', start: 0, end: 0, controlNodeId: null, words: 'In this empty paragraph', where: 'in an empty paragraph' },
    ])
  })

  it('offers the text of a control no field names', () => {
    const target = line('Signed on  in Hanoi', { controls: [{ controlNodeId: 'p1/sdt1', text: '[date]', at: 10 }] })
    const places = placementsForLine(target)
    expect(places.map((place) => place.words)).toEqual(["Replace '[date]'", 'At the end of the paragraph'])
    expect(places[0]).toMatchObject({ placement: 'EXISTING_CONTROL', controlNodeId: 'p1/sdt1' })
  })

  it('shortens a long blank or label in its words', () => {
    const long = '_'.repeat(60)
    expect(words(line(`Reason: ${long}`))[0]).toBe(`Replace '${'_'.repeat(39)}\u2026'`)
  })

  it('counts offsets after an emoji in code points', () => {
    const places = placementsForLine(line(`${EMOJI} Name: ____`))
    expect(places[0]).toMatchObject({ placement: 'REPLACE', start: 8, end: 12 })
  })

  it('turns a click on a blank into that blank, and a click in a word into the end of the word', () => {
    const target = line('Company: ________ Ltd')
    expect(placeForCaret(target, 12)).toMatchObject({ placement: 'REPLACE', start: 9, end: 17 })
    expect(placeForCaret(target, 9)).toMatchObject({ placement: 'REPLACE', start: 9, end: 17 })
    expect(placeForCaret(target, 3)).toMatchObject({ placement: 'AT', start: 8, end: 8, words: "After 'Company:'" })
    expect(placeForCaret(target, 0)).toMatchObject({ placement: 'AT', start: 0, words: 'At the start of the paragraph' })
    expect(placeForCaret(target, 99)).toMatchObject({ placement: 'AT', start: 21, words: 'At the end of the paragraph' })
    expect(placeForCaret(line(''), 0)).toMatchObject({ placement: 'WHOLE_LINE' })
  })

  it('turns a selection into the words it covers, without the spaces at its edges', () => {
    const target = line('Reference: N/A  here')
    expect(placeForSelection(target, 10, 16)).toMatchObject({ placement: 'REPLACE', start: 11, end: 14, words: "Replace 'N/A'" })
    // A blank of underlined spaces is all spaces, and is kept whole.
    expect(placeForSelection(line('Sign:      .'), 5, 11)).toMatchObject({ placement: 'REPLACE', start: 5, end: 11 })
    expect(placeForSelection(target, 4, 4)).toMatchObject({ placement: 'AT' })
  })

  it('reads a place chosen on the page against its line', () => {
    const target = line('Company: ____', { controls: [{ controlNodeId: 'p3/sdt0', text: 'x', at: 0 }] })
    expect(placeOnLine(target, { nodeId: 'p3', anchorTextHash: 'h', kind: 'caret', start: 10, end: 10, controlNodeId: null })).toMatchObject({ placement: 'REPLACE' })
    expect(placeOnLine(target, { nodeId: 'p3', anchorTextHash: 'h', kind: 'selection', start: 0, end: 7, controlNodeId: null })).toMatchObject({ placement: 'REPLACE', start: 0, end: 7 })
    expect(placeOnLine(target, { nodeId: 'p3', anchorTextHash: 'h', kind: 'control', start: 0, end: 0, controlNodeId: 'p3/sdt0' })).toMatchObject({ placement: 'EXISTING_CONTROL' })
    expect(placeOnLine(target, { nodeId: 'p3', anchorTextHash: 'h', kind: 'control', start: 0, end: 0, controlNodeId: 'p9/sdt0' })).toBeNull()
  })
})

describe('the name offered for a new spot', () => {
  const at = (target: AnchorLine, start: number, end = start) => placeForSelection(target, start, end)

  it('takes the words of a bracketed prompt', () => {
    const target = line('[company name]')
    expect(suggestLabel(target, placementsForLine(target)[0]!)).toBe('Company name')
  })

  it('takes the label before the place, not the one before that', () => {
    const target = line('Name: ________   Date: ________')
    const places = placementsForLine(target)
    expect(suggestLabel(target, places[0]!)).toBe('Name')
    expect(suggestLabel(target, places[1]!)).toBe('Date')
    expect(suggestLabel(line('Company :'), placeForCaret(line('Company :'), 9))).toBe('Company')
    expect(suggestLabel(line('1. full legal name:'), placeForCaret(line('1. full legal name:'), 19))).toBe('Full legal name')
  })

  it('takes the words in the cell to the left for an empty line in a table', () => {
    const target = line('', { besideText: 'Phone number:' })
    expect(suggestLabel(target, placementsForLine(target)[0]!)).toBe('Phone number')
  })

  it('offers nothing when nothing names the place', () => {
    const target = line('Signed in Hanoi')
    expect(suggestLabel(target, at(target, 15))).toBe('')
  })

  it('takes a control prompt as the name', () => {
    const target = line('', { controls: [{ controlNodeId: 'p1/sdt0', text: '[Client]', at: 0 }] })
    expect(suggestLabel(target, placementsForLine(target)[0]!)).toBe('Client')
  })

  it('takes a name about a date as a date', () => {
    expect(suggestType('Date of birth')).toBe('DATE')
    expect(suggestType('Due')).toBe('DATE')
    expect(suggestType('Company')).toBe('TEXT')
    expect(suggestType('Update notes')).toBe('TEXT')
  })
})

describe('what is sent', () => {
  it('sends the place with the paragraph and its hash as the layout gave them', () => {
    const target = line('Company: ____')
    expect(toDocxAnchor(target, placementsForLine(target)[0]!, 'brownie-docx-graph-v3+poi-5.5.1')).toEqual({
      part: 'MAIN_DOCUMENT',
      paragraphNodeId: 'p3',
      placement: 'REPLACE',
      start: 9,
      end: 13,
      anchorTextHash: 'hash-p3',
      parserVersion: 'brownie-docx-graph-v3+poi-5.5.1',
      controlNodeId: null,
    })
  })
})

describe('reading a place off the page', () => {
  let root: HTMLElement

  afterEach(() => {
    root?.remove()
  })

  /** A sheet shaped the way the page draws one: paragraphs with their data, text pieces with their offsets. */
  function sheet(html: string): HTMLElement {
    root = document.createElement('div')
    root.className = 'document-page__sheet'
    root.innerHTML = html
    document.body.append(root)
    return root
  }

  const PAGE = `
    <p data-part="MAIN_DOCUMENT" data-node-id="p0" data-anchorable="true" data-anchor-hash="h0"><span data-anchor-start="0">Name ${EMOJI}: </span><span class="fill-spot"><textarea></textarea></span><span data-anchor-start="8">____ end</span></p>
    <p data-part="MAIN_DOCUMENT" data-node-id="p1" data-anchorable="true" data-anchor-hash="h1"><span data-anchor-start="0">Signed </span><span data-control-node-id="p1/sdt1">[date]</span></p>
    <p data-part="HEADER" data-node-id="p0" data-anchorable="false"><span>Header</span></p>
    <p data-part="MAIN_DOCUMENT" data-node-id="p2" data-anchorable="false" data-repeats="true"><span>Row</span></p>
    <p data-part="MAIN_DOCUMENT" data-node-id="p3" data-anchorable="false"><span>Other</span></p>`

  const textOf = (element: Element) => element.firstChild as Text

  it('counts a place inside the text in code points from where that text starts', () => {
    const page = sheet(PAGE)
    const [first, , last] = page.querySelectorAll('p')[0]!.children
    // After the emoji: seven UTF-16 units, six code points.
    expect(readPlace({ node: textOf(first!), offset: 7 }, { node: textOf(first!), offset: 7 }, page)).toEqual({
      point: { nodeId: 'p0', anchorTextHash: 'h0', kind: 'caret', start: 6, end: 6, controlNodeId: null },
    })
    expect(readPlace({ node: textOf(last!), offset: 0 }, { node: textOf(last!), offset: 4 }, page)).toEqual({
      point: { nodeId: 'p0', anchorTextHash: 'h0', kind: 'selection', start: 8, end: 12, controlNodeId: null },
    })
  })

  it('takes a selection made backwards the same way', () => {
    const page = sheet(PAGE)
    const last = page.querySelectorAll('p')[0]!.children[2]!
    expect(readPlace({ node: textOf(last), offset: 4 }, { node: textOf(last), offset: 0 }, page)).toMatchObject({ point: { start: 8, end: 12 } })
  })

  it('counts a place between pieces of text as the end of the piece before it', () => {
    const page = sheet(PAGE)
    const paragraph = page.querySelectorAll('p')[0]!
    // Right after the fill spot, before the blank: where the first piece ends.
    expect(readPlace({ node: paragraph, offset: 2 }, { node: paragraph, offset: 2 }, page)).toMatchObject({ point: { kind: 'caret', start: 8 } })
    expect(readPlace({ node: paragraph, offset: 0 }, { node: paragraph, offset: 0 }, page)).toMatchObject({ point: { kind: 'caret', start: 0 } })
  })

  it('makes a control no field names the spot when the place is in its text', () => {
    const page = sheet(PAGE)
    const control = page.querySelector('[data-control-node-id]')!
    expect(readPlace({ node: textOf(control), offset: 1 }, { node: textOf(control), offset: 3 }, page)).toEqual({
      point: { nodeId: 'p1', anchorTextHash: 'h1', kind: 'control', start: 0, end: 0, controlNodeId: 'p1/sdt1' },
    })
  })

  it('takes a whole line selected with three clicks, which ends at the start of the next line, as that line', () => {
    const page = sheet(PAGE)
    const [first, second] = page.querySelectorAll('p')
    expect(readPlace({ node: textOf(second!.children[0]!), offset: 0 }, { node: page.querySelectorAll('p')[2]!, offset: 0 }, page)).toEqual({
      point: { nodeId: 'p1', anchorTextHash: 'h1', kind: 'selection', start: 0, end: 7, controlNodeId: null },
    })
    expect(readPlace({ node: textOf(first!.children[2]!), offset: 0 }, { node: textOf(second!.children[0]!), offset: 0 }, page)).toEqual({
      point: { nodeId: 'p0', anchorTextHash: 'h0', kind: 'selection', start: 8, end: 16, controlNodeId: null },
    })
  })

  it('refuses a selection over two lines, across a fill spot, or inside one', () => {
    const page = sheet(PAGE)
    const [first, second] = page.querySelectorAll('p')
    expect(readPlace({ node: textOf(first!.children[0]!), offset: 0 }, { node: textOf(second!.children[0]!), offset: 2 }, page)).toEqual({ refusal: 'ACROSS_LINES' })
    expect(readPlace({ node: textOf(first!.children[0]!), offset: 1 }, { node: textOf(first!.children[2]!), offset: 2 }, page)).toEqual({ refusal: 'ACROSS_FILL_SPOT' })
    const spot = first!.querySelector('textarea')!
    expect(readPlace({ node: spot, offset: 0 }, { node: spot, offset: 0 }, page)).toEqual({ refusal: 'IN_FILL_SPOT' })
  })

  it('says why a header, a repeating row or another line cannot take a spot', () => {
    const page = sheet(PAGE)
    const [, , header, repeats, other] = page.querySelectorAll('p')
    const at = (paragraph: Element) => readPlace({ node: textOf(paragraph.firstElementChild!), offset: 1 }, { node: textOf(paragraph.firstElementChild!), offset: 1 }, page)
    expect(at(header!)).toEqual({ refusal: 'HEADER_FOOTER' })
    expect(at(repeats!)).toEqual({ refusal: 'REPEATING' })
    expect(at(other!)).toEqual({ refusal: 'NOT_ANCHORABLE' })
  })

  it('refuses a place outside the page', () => {
    const page = sheet(PAGE)
    const outside = document.createElement('p')
    outside.textContent = 'Elsewhere'
    document.body.append(outside)
    expect(readPlace({ node: outside.firstChild!, offset: 1 }, { node: outside.firstChild!, offset: 1 }, page)).toEqual({ refusal: 'NOT_ON_PAGE' })
    outside.remove()
  })

  it('never lands between a letter and its accent', () => {
    const page = sheet(`<p data-part="MAIN_DOCUMENT" data-node-id="p0" data-anchorable="true" data-anchor-hash="h"><span data-anchor-start="0">Cafe${ACUTE} x</span></p>`)
    const piece = page.querySelector('span')!
    expect(readPlace({ node: textOf(piece), offset: 4 }, { node: textOf(piece), offset: 4 }, page)).toMatchObject({ point: { start: 3 } })
    expect(readPlace({ node: textOf(piece), offset: 0 }, { node: textOf(piece), offset: 4 }, page)).toMatchObject({ point: { start: 0, end: 5 } })
  })
})

describe('the caret under a pointer', () => {
  it('asks the standard way first, then the older one, and says nothing when neither is there', () => {
    const node = document.createTextNode('x')
    const standard = { caretPositionFromPoint: () => ({ offsetNode: node, offset: 1 }) } as unknown as Document
    expect(caretFromPoint(standard, 1, 2)).toEqual({ node, offset: 1 })
    const range = { startContainer: node, startOffset: 0 } as unknown as Range
    const older = { caretRangeFromPoint: () => range } as unknown as Document
    expect(caretFromPoint(older, 1, 2)).toEqual({ node, offset: 0 })
    expect(caretFromPoint({} as Document, 1, 2)).toBeNull()
  })
})
