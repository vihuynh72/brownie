import { describe, expect, it } from 'vitest'
import { ApiRequestError, type PdfLayoutResponse } from '@/api/client'
import { describeStyle, dominantFillSpotStyle, fillSpotStyle, type EditableField } from '@/workspace/layout'
import {
  boxPlacement,
  boxesInCells,
  buildPdfFormModel,
  clampBox,
  describeBox,
  normalizeSpotLabel,
  nudgeBox,
  readingOrder,
  spotChangeFailure,
  spotTextCss,
} from '@/workspace/pdfPage'

type Spot = PdfLayoutResponse['spots'][number]

function spot(fieldId: string, pageNumber: number, box: Spot['box'], extra: Partial<Spot> = {}): Spot {
  return {
    fieldId,
    label: fieldId,
    origin: 'FORM',
    pageNumber,
    box,
    style: { font: 'SANS', bold: false, sizePt: 11 },
    multiline: false,
    overflow: 'SHRINK_TO_FIT',
    bindingKind: 'PAGE_BOX',
    ...extra,
  }
}

function field(fieldId: string, extra: Partial<EditableField> = {}): EditableField {
  return { fieldId, type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', ...extra }
}

const letter = { width: 612, height: 792, rotation: 0 as const, hasText: true, lines: [] }

function problem(status: number, code: string, extra: Record<string, unknown> = {}): ApiRequestError {
  return new ApiRequestError(status, { status, title: 'x', code, correlationId: 'c', fields: [], recoveryActions: [], ...extra })
}

describe('buildPdfFormModel', () => {
  it('orders the places page by page, top to bottom, and left to right along a line', () => {
    const pdf: PdfLayoutResponse = {
      sourceArtifactId: 9,
      pages: [
        { ...letter, pageNumber: 2 },
        { ...letter, pageNumber: 1 },
      ],
      spots: [
        spot('signature', 2, { x: 72, y: 700, width: 200, height: 18 }),
        spot('city', 1, { x: 320, y: 201, width: 120, height: 18 }),
        spot('street', 1, { x: 72, y: 200, width: 200, height: 18 }),
        spot('name', 1, { x: 72, y: 100, width: 300, height: 18 }),
        spot('notes', 2, { x: 72, y: 100, width: 400, height: 60 }),
      ],
    }
    const model = buildPdfFormModel(pdf, ['name', 'street', 'city', 'notes', 'signature'].map((id) => field(id)))

    expect(model.pages.map((page) => page.pageNumber)).toEqual([1, 2])
    expect(model.spots.map((place) => place.fieldId)).toEqual(['name', 'street', 'city', 'notes', 'signature'])
    expect(model.spots.map((place) => place.inputId)).toEqual(['edit-name', 'edit-street', 'edit-city', 'edit-notes', 'edit-signature'])
    expect(model.unplaced).toEqual([])
  })

  it('gives a field shown in two places one control id each, and lists fields with no place', () => {
    const pdf: PdfLayoutResponse = {
      sourceArtifactId: 9,
      pages: [{ ...letter, pageNumber: 1 }],
      spots: [
        spot('name', 1, { x: 72, y: 500, width: 200, height: 18 }, { bindingKind: 'ACROFORM_FIELD', style: null }),
        spot('name', 1, { x: 72, y: 100, width: 200, height: 18 }, { bindingKind: 'ACROFORM_FIELD', style: null }),
        spot('gone', 1, { x: 72, y: 300, width: 200, height: 18 }),
      ],
    }
    const model = buildPdfFormModel(pdf, [field('name', { label: 'Full name' }), field('email')])

    expect(model.spots.map((place) => [place.inputId, place.box.y, place.formField, place.label])).toEqual([
      ['edit-name', 100, true, 'Full name'],
      ['edit-name--2', 500, true, 'Full name'],
    ])
    // A place for a field the version does not have is not drawn; a field with no place is listed.
    expect(model.unplaced.map((item) => item.fieldId)).toEqual(['email'])
  })

  it('lays places out on a turned page as the page is shown', () => {
    const pdf: PdfLayoutResponse = {
      sourceArtifactId: 9,
      pages: [{ ...letter, pageNumber: 1, rotation: 90 }],
      spots: [spot('name', 1, { x: 72, y: 100, width: 200, height: 18 })],
    }
    const [page] = buildPdfFormModel(pdf, [field('name')]).pages

    expect(page!.shownWidth).toBe(792)
    expect(page!.shownHeight).toBe(612)
    expect(page!.spots[0]!.shown).toEqual({ x: 674, y: 72, width: 18, height: 200 })
    expect(boxPlacement(page!.spots[0]!.shown, page!)).toEqual({ left: '85.101%', top: '11.7647%', width: '2.2727%', height: '32.6797%' })
  })
})

describe('boxesInCells', () => {
  const at = (key: string, x: number, y: number, width = 146, height = 22) => ({ key, shown: { x, y, width, height } })

  it("finds the boxes of a table's cells: beside another along a line, or under another in a column, across a rule", () => {
    // "Year" in the first row; "Course" and "Year" in the next two, each cell 4 points from the next.
    const cells = [at('year.1', 613, 437), at('course.2', 463, 461), at('year.2', 613, 461), at('course.3', 463, 486), at('year.3', 613, 486)]
    const alone = [at('full.name', 513, 235, 184, 16), at('email', 496, 299, 435, 16)]
    expect([...boxesInCells([...alone, ...cells])].sort()).toEqual(['course.2', 'course.3', 'year.1', 'year.2', 'year.3'])
  })

  it('leaves boxes on lines of their own, and boxes too far apart or of another size to be cells of one table', () => {
    expect(boxesInCells([at('a', 100, 100), at('b', 100, 140)]).size).toBe(0)
    expect(boxesInCells([at('a', 100, 100), at('b', 260, 100)]).size).toBe(0)
    expect(boxesInCells([at('a', 100, 100), at('b', 100, 122, 80)]).size).toBe(0)
  })
})

describe('readingOrder', () => {
  it('keeps boxes a little apart in height on one line, read from the left', () => {
    const shown = (x: number, y: number) => ({ shown: { x, y, width: 50, height: 20 } })
    const order = readingOrder([shown(300, 104), shown(50, 100), shown(50, 140), shown(200, 96)])
    expect(order.map((item) => [item.shown.x, item.shown.y])).toEqual([
      [50, 100],
      [200, 96],
      [300, 104],
      [50, 140],
    ])
  })
})

describe('nudgeBox and clampBox', () => {
  const page = { width: 612, height: 792, rotation: 0 }

  it('moves a box a point, or ten with Shift, and resizes it with Alt', () => {
    const box = { x: 100, y: 100, width: 100, height: 20 }
    expect(nudgeBox(box, page, 'ArrowRight')).toEqual({ x: 101, y: 100, width: 100, height: 20 })
    expect(nudgeBox(box, page, 'ArrowUp', { large: true })).toEqual({ x: 100, y: 90, width: 100, height: 20 })
    expect(nudgeBox(box, page, 'ArrowDown', { resize: true })).toEqual({ x: 100, y: 100, width: 100, height: 21 })
    expect(nudgeBox(box, page, 'ArrowLeft', { resize: true, large: true })).toEqual({ x: 100, y: 100, width: 90, height: 20 })
  })

  it('moves the way the person sees a turned page', () => {
    // At 90 degrees the shown right is the stored page's down, so Right grows y; Alt+Right widens what is shown, the stored height.
    const turned = { ...page, rotation: 90 }
    const box = { x: 72, y: 100, width: 200, height: 18 }
    expect(nudgeBox(box, turned, 'ArrowRight')).toEqual({ x: 72, y: 99, width: 200, height: 18 })
    expect(nudgeBox(box, turned, 'ArrowDown')).toEqual({ x: 73, y: 100, width: 200, height: 18 })
    expect(nudgeBox(box, turned, 'ArrowRight', { resize: true })).toEqual({ x: 72, y: 99, width: 200, height: 19 })
  })

  it('keeps a box whole on its page and no smaller than Brownie accepts', () => {
    expect(nudgeBox({ x: 0, y: 0, width: 50, height: 20 }, page, 'ArrowLeft', { large: true })).toEqual({ x: 0, y: 0, width: 50, height: 20 })
    expect(nudgeBox({ x: 10, y: 10, width: 8, height: 6 }, page, 'ArrowUp', { resize: true })).toEqual({ x: 10, y: 10, width: 8, height: 6 })
    expect(clampBox({ x: 600, y: 790, width: 50, height: 20 }, page)).toEqual({ x: 562, y: 772, width: 50, height: 20 })
    expect(clampBox({ x: -5, y: 3.14159, width: 2, height: 2 }, page)).toEqual({ x: 0, y: 3.14, width: 8, height: 6 })
  })

  it('says where a box is in words', () => {
    expect(describeBox('Company', { x: 72, y: 100, width: 200, height: 18 }, page)).toBe(
      'Company: 200 by 18 points, 72 from the left and 100 from the top.',
    )
  })
})

describe('spotTextCss', () => {
  it('draws the box font at its size, scaled with the page', () => {
    expect(spotTextCss({ font: 'SERIF', bold: true, sizePt: 9 })).toEqual({
      fontFamily: '"Liberation Serif", "Times New Roman", Times, serif',
      fontWeight: '700',
      fontSize: 'max(var(--pdf-min-text, 0px), calc(9 * var(--pdf-point, 1px)))',
    })
    expect(spotTextCss(null).fontSize).toBe('max(var(--pdf-min-text, 0px), calc(11 * var(--pdf-point, 1px)))')
  })
})

describe('normalizeSpotLabel', () => {
  it('tidies a name and refuses one that is not a name', () => {
    expect(normalizeSpotLabel('  Tên   công ty \n')).toBe('Tên công ty')
    expect(normalizeSpotLabel('Date:')).toBe('Date:')
    expect(normalizeSpotLabel('   ')).toBeNull()
    expect(normalizeSpotLabel('***')).toBeNull()
    expect(normalizeSpotLabel('x'.repeat(61))).toBeNull()
    expect(normalizeSpotLabel('x'.repeat(60))).toBe('x'.repeat(60))
  })
})

describe('spotChangeFailure', () => {
  const labelOf = (fieldId: string) => (fieldId === 'company' ? 'Company' : fieldId)

  it('says why a box was refused', () => {
    expect(spotChangeFailure(problem(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'OFF_PAGE' }), labelOf)).toBe('That box is off the page.')
    expect(spotChangeFailure(problem(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'TOO_SMALL' }), labelOf)).toBe(
      'That box is too small to hold text; make it bigger.',
    )
    expect(spotChangeFailure(problem(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'OVERLAPS' }), labelOf)).toBe(
      'That box covers another fill spot; move it a little.',
    )
    expect(spotChangeFailure(problem(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'NOT_FILLABLE' }), labelOf)).toContain('cannot write on that page')
  })

  it('says what else stopped a change', () => {
    expect(spotChangeFailure(problem(409, 'TEMPLATE_VERSION_MOVED_ON'), labelOf)).toBe(
      'This form changed while you were working, so it was reloaded. Try again.',
    )
    expect(spotChangeFailure(problem(409, 'FILL_SPOT_LOCKED', { fieldId: 'company' }), labelOf)).toBe('Unlock Company first; its value would be lost.')
    expect(spotChangeFailure(problem(422, 'FILL_SPOT_WOULD_NOT_PRINT'), labelOf, 'add')).toBe(
      'Brownie could not add the fill spot, because the form would not print correctly with it. Nothing was changed.',
    )
    expect(spotChangeFailure(problem(422, 'FILL_SPOT_CHANGE_INVALID', { detail: 'Only a box Brownie draws can be moved.' }), labelOf)).toBe(
      'Only a box Brownie draws can be moved. Nothing was changed.',
    )
    expect(spotChangeFailure(new TypeError('fetch failed'), labelOf)).toBe('Brownie could not be reached. Check your connection, then try again.')
  })
})

describe('the Rules card on a PDF form', () => {
  const pdf: PdfLayoutResponse = {
    sourceArtifactId: 9,
    pages: [{ ...letter, pageNumber: 1 }],
    spots: [
      spot('full.name', 1, { x: 72, y: 100, width: 300, height: 18 }, { bindingKind: 'ACROFORM_FIELD', style: null }),
      spot('company', 1, { x: 72, y: 140, width: 300, height: 18 }, { style: { font: 'SERIF', bold: true, sizePt: 8.5 } }),
      spot('city', 1, { x: 72, y: 180, width: 300, height: 18 }),
      spot('street', 1, { x: 72, y: 220, width: 300, height: 18 }),
    ],
  }
  const layout = { templateId: 1, versionId: 1, kind: 'PDF' as const, parserVersion: 'p', parts: [], unplacedFieldIds: [], pdf }

  it('describes a box by the font, size and weight Brownie writes it in', () => {
    expect(describeStyle(fillSpotStyle(layout, 'company'))).toEqual({ font: 'Liberation Serif', size: '8.5 pt', weight: 'Bold', italic: false, underline: false })
    expect(describeStyle(dominantFillSpotStyle(layout))).toMatchObject({ font: 'Liberation Sans', size: '11 pt', weight: 'Regular' })
    // One of the PDF's own fields is written the way the form says.
    expect(fillSpotStyle(layout, 'full.name')).toBeNull()
  })
})
