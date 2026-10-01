import { describe, expect, it } from 'vitest'
import vectors from '../../../../../fixtures/public/pdf-geometry-vectors.json'
import { boxPlacement, pagePoint } from '@/workspace/pdfPage'
import { boxToDisplayed, boxToViewport, displayedHeight, displayedWidth, pointFromDisplayed, type CropBox, type PdfBox, type PdfPoint } from '@/workspace/pdfGeometry'
import {
  FIT_WIDTH_MAX_SCALE,
  MAX_CANVAS_PIXELS,
  PRINTED_SCALE,
  canvasPixels,
  canvasResolution,
  fitWidthScale,
  keepMiddle,
  pageWidthCss,
  zoomPercent,
  zoomStep,
} from '@/workspace/pdfZoom'
import { pdfJsViewportTransform } from './pdfJsViewport'

interface Vector {
  name: string
  cropBox: CropBox
  rotation: number
  box: PdfBox
  displayed: PdfBox
  point: PdfPoint
  displayedPoint: PdfPoint
}

const cases = (vectors as { cases: Vector[] }).cases

/** A CSS length the page view writes, in pixels: a plain width, or Fit width's in a pane `available` pixels wide. */
function resolveWidth(css: string, available: number): number {
  const fit = /^min\(100%, (\d+)px\)$/.exec(css)
  if (fit) return Math.min(available, Number(fit[1]))
  const plain = /^(\d+)px$/.exec(css)
  if (!plain) throw new Error(`Not a width the page view writes: ${css}`)
  return Number(plain[1])
}

/** Where a box lands on a page drawn `width` by `height` pixels, from the percentages the page view gives it. */
function resolvePlacement(style: Record<string, string>, width: number, height: number): PdfBox {
  const percent = (value: string | undefined) => Number.parseFloat(value ?? 'NaN') / 100
  return { x: percent(style.left) * width, y: percent(style.top) * height, width: percent(style.width) * width, height: percent(style.height) * height }
}

describe('pdfZoom', () => {
  it('fits a page to a narrow pane, and no larger than an easy reading size in a wide one', () => {
    expect(fitWidthScale(464, 612)).toBeCloseTo(464 / 612, 9)
    expect(fitWidthScale(1600, 612)).toBe(FIT_WIDTH_MAX_SCALE)
    expect(fitWidthScale(0, 612)).toBe(FIT_WIDTH_MAX_SCALE)
    expect(pageWidthCss(612, { kind: 'fit' })).toBe('min(100%, 918px)')
    // A page turned a quarter is shown wider, and fits by that width.
    expect(pageWidthCss(792, { kind: 'fit' })).toBe('min(100%, 1188px)')
    expect(pageWidthCss(612, { kind: 'scale', scale: 2 })).toBe('1224px')
    expect(pageWidthCss(595.28, { kind: 'scale', scale: 0.75 })).toBe('446px')
  })

  it('says a size as a percentage of the printed page', () => {
    expect([PRINTED_SCALE, 1, FIT_WIDTH_MAX_SCALE, 2, 464 / 612].map(zoomPercent)).toEqual([100, 75, 113, 150, 57])
  })

  it('steps through the zoom sizes, never to one that looks the same, and stops at either end', () => {
    const percentOf = (scale: number | null) => (scale === null ? null : zoomPercent(scale))
    expect(percentOf(zoomStep(464 / 612, 'in'))).toBe(75)
    expect(percentOf(zoomStep(464 / 612, 'out'))).toBe(50)
    // A fitted page at 74% is a step's width from 75%: Zoom in goes on to 100%.
    expect(percentOf(zoomStep(0.99, 'in'))).toBe(100)
    expect(percentOf(zoomStep(PRINTED_SCALE, 'in'))).toBe(125)
    expect(percentOf(zoomStep(PRINTED_SCALE, 'out'))).toBe(75)
    expect(zoomStep(2 * PRINTED_SCALE, 'in')).toBeNull()
    expect(zoomStep(0.5 * PRINTED_SCALE, 'out')).toBeNull()
    // A phone fits a page smaller than the smallest step: there is nothing smaller to go to, but every step is larger.
    expect(zoomStep(343 / 612, 'out')).toBeNull()
    expect(percentOf(zoomStep(343 / 612, 'in'))).toBe(50)
  })

  it('draws a page at the screen’s own density, unless its picture would be larger than a browser can hold', () => {
    expect(canvasResolution(918, 1188, 2)).toBe(2)
    expect(canvasResolution(464, 600, 1)).toBe(1)
    expect(canvasResolution(464, 600, Number.NaN)).toBe(1)
    const capped = canvasResolution(1632, 2112, 3)
    expect(capped).toBeLessThan(3)
    expect(Math.floor(1632 * capped) * Math.floor(2112 * capped)).toBeLessThanOrEqual(MAX_CANVAS_PIXELS)
  })

  it('gives a page whole canvas pixels, never one short for a rounding error, never more than the limit allows', () => {
    expect(canvasPixels(612 * (448 / 612), 2)).toBe(896)
    expect(canvasPixels(447.99999999999994, 2)).toBe(896)
    expect(canvasPixels(600.47, 2)).toBe(1200)
    expect(canvasPixels(463.5, 1)).toBe(463)
  })

  it('keeps what was in the middle of the view there when the pages change size', () => {
    // A 400 px view scrolled 1000 px into pages that start 200 px down and double from 2000 to 4000 px.
    const scroll = keepMiddle(1000, 400, 200, 2000, 4000)
    expect((1200 - 200) / 2000).toBeCloseTo((scroll + 200 - 200) / 4000, 9)
    // The middle of the view is on the notes above the pages: the view stays where it is.
    expect(keepMiddle(0, 300, 200, 2000, 4000)).toBe(0)
    // Nothing measured yet: nothing moves.
    expect(keepMiddle(120, 400, 0, 0, 4000)).toBe(120)
  })
})

describe('boxes at every zoom, against the shared worked examples', () => {
  for (const vector of cases) {
    for (const scale of [0.75, 1, 1.5, 2]) {
      it(`${vector.name}, at ${scale}x`, () => {
        const { cropBox: crop, rotation } = vector
        const shownWidth = displayedWidth(crop, rotation)
        const shownHeight = displayedHeight(crop, rotation)
        // The page as the browser lays it out at that zoom, and the scale pdf.js draws its picture at to fill it.
        const width = resolveWidth(pageWidthCss(shownWidth, { kind: 'scale', scale }), 10_000)
        const drawn = width / shownWidth
        const height = shownHeight * drawn

        // The box the page view lays over the page is where pdf.js draws that place on the picture, to a hundredth
        // of a pixel (the percentages it writes are rounded to a millionth of the page).
        const placed = resolvePlacement(boxPlacement(boxToDisplayed(vector.box, crop, rotation), { shownWidth, shownHeight }), width, height)
        const onPicture = boxToViewport(vector.box, crop, pdfJsViewportTransform(crop, rotation, drawn))
        for (const key of ['x', 'y', 'width', 'height'] as const) expect(placed[key], key).toBeCloseTo(onPicture[key], 2)

        // A pointer over that place on the screen, with the page scrolled to anywhere, means the same point of the page.
        const rect = { left: -37.5, top: 412, width, height }
        const shown = pagePoint(
          { clientX: rect.left + vector.displayedPoint.x * drawn, clientY: rect.top + vector.displayedPoint.y * drawn },
          rect,
          { shownWidth, shownHeight },
        )!
        const stored = pointFromDisplayed(shown, crop, rotation)
        expect(stored.x).toBeCloseTo(vector.point.x, 6)
        expect(stored.y).toBeCloseTo(vector.point.y, 6)
      })
    }
  }

  it('fits the same way: a fitted page is drawn at the width it is laid out at', () => {
    for (const available of [343, 464, 2000]) {
      const width = resolveWidth(pageWidthCss(612, { kind: 'fit' }), available)
      expect(width / 612).toBeCloseTo(fitWidthScale(available, 612), 9)
    }
  })

  it('keeps a pointer off the page on its edge, and knows no point on a page not drawn yet', () => {
    const page = { shownWidth: 612, shownHeight: 792 }
    expect(pagePoint({ clientX: -40, clientY: 2000 }, { left: 0, top: 0, width: 918, height: 1188 }, page)).toEqual({ x: 0, y: 792 })
    expect(pagePoint({ clientX: 10, clientY: 10 }, { left: 0, top: 0, width: 0, height: 0 }, page)).toBeNull()
  })
})
