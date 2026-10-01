import { describe, expect, it } from 'vitest'
import vectors from '../../../../../fixtures/public/pdf-geometry-vectors.json'
import {
  boxFromDisplayed,
  boxToDisplayed,
  boxToScaledDisplay,
  boxToViewport,
  displayedHeight,
  displayedWidth,
  fromUserSpace,
  pointFromDisplayed,
  pointFromScaledDisplay,
  pointFromUserSpace,
  pointFromViewport,
  pointToDisplayed,
  pointToUserSpace,
  quarterTurns,
  toUserSpace,
  uprightFrame,
  type CropBox,
  type PdfBox,
  type PdfPoint,
} from '@/workspace/pdfGeometry'
import { pdfJsViewportTransform } from './pdfJsViewport'

/*
 * The same worked examples the server's conversions are tested against: a page measured four ways, at
 * each of the four turns, with a crop box at zero and one that starts elsewhere.
 */
interface Vector {
  name: string
  cropBox: CropBox
  rotation: number
  box: PdfBox
  userSpace: { llx: number; lly: number; urx: number; ury: number }
  displayed: PdfBox
  uprightFrame: { a: number; b: number; c: number; d: number; e: number; f: number; width: number; height: number }
  point: PdfPoint
  displayedPoint: PdfPoint
}

const cases = (vectors as { cases: Vector[] }).cases

function close(actual: Record<string, number>, expected: Record<string, number>): void {
  for (const [key, value] of Object.entries(expected)) expect(actual[key], key).toBeCloseTo(value, 9)
}

describe('pdfGeometry against the shared worked examples', () => {
  it('has the examples to check', () => {
    expect(cases.length).toBeGreaterThanOrEqual(16)
    expect(new Set(cases.map((vector) => vector.rotation))).toEqual(new Set([0, 90, 180, 270]))
  })

  for (const vector of cases) {
    describe(vector.name, () => {
      const { cropBox: crop, rotation, box } = vector

      it('goes from a box to user space and back', () => {
        close({ ...toUserSpace(box, crop) }, vector.userSpace)
        close({ ...fromUserSpace(vector.userSpace, crop) }, { ...box })
        // Corners given the other way round span the same box.
        const flipped = { llx: vector.userSpace.urx, lly: vector.userSpace.ury, urx: vector.userSpace.llx, ury: vector.userSpace.lly }
        close({ ...fromUserSpace(flipped, crop) }, { ...box })
      })

      it('turns a box and a point the way a viewer shows the page, and back', () => {
        close({ ...boxToDisplayed(box, crop, rotation) }, { ...vector.displayed })
        close({ ...boxFromDisplayed(vector.displayed, crop, rotation) }, { ...box })
        close({ ...pointToDisplayed(vector.point, crop, rotation) }, { ...vector.displayedPoint })
        close({ ...pointFromDisplayed(vector.displayedPoint, crop, rotation) }, { ...vector.point })
      })

      it('finds the frame upright text is drawn in', () => {
        close({ ...uprightFrame(box, crop, rotation) }, vector.uprightFrame)
      })

      it('moves a point between box space and user space', () => {
        const user = pointToUserSpace(vector.point, crop)
        close({ ...pointFromUserSpace(user.x, user.y, crop) }, { ...vector.point })
      })

      it('lands where pdf.js draws the page, at any scale', () => {
        for (const scale of [1, 0.75, 1.5]) {
          const transform = pdfJsViewportTransform(crop, rotation, scale)
          const expected = { x: vector.displayed.x * scale, y: vector.displayed.y * scale, width: vector.displayed.width * scale, height: vector.displayed.height * scale }
          close({ ...boxToViewport(box, crop, transform) }, expected)
          close({ ...boxToScaledDisplay(box, crop, rotation, scale) }, expected)
          const pointer = { x: vector.displayedPoint.x * scale, y: vector.displayedPoint.y * scale }
          close({ ...pointFromViewport(pointer, crop, transform) }, { ...vector.point })
          close({ ...pointFromScaledDisplay(pointer, crop, rotation, displayedWidth(crop, rotation) * scale) }, { ...vector.point })
        }
      })
    })
  }
})

describe('pdfGeometry', () => {
  it('counts quarter turns, negative and past a full turn included, and refuses anything else', () => {
    expect([0, 90, 180, 270, 360, 450, -90].map(quarterTurns)).toEqual([0, 1, 2, 3, 0, 1, 3])
    expect(() => quarterTurns(45)).toThrow(RangeError)
    expect(() => quarterTurns(Number.NaN)).toThrow(RangeError)
  })

  it('swaps the page size at a quarter turn', () => {
    const page = { width: 612, height: 792 }
    expect([0, 90, 180, 270].map((turn) => [displayedWidth(page, turn), displayedHeight(page, turn)])).toEqual([
      [612, 792],
      [792, 612],
      [612, 792],
      [792, 612],
    ])
  })
})
