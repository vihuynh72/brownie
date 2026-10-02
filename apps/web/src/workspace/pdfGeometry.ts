/*
 * The ways a PDF page can be measured, and how a box moves between them, so that the page the person
 * sees, the boxes drawn over it and the file the server writes into agree to the point:
 *
 * - Box space (how Brownie stores boxes and lines, and what the API sends): points, the page as
 *   stored, before it is turned, origin at the top-left of its visible area (the crop box), Y down.
 * - User space: the PDF's own coordinates, origin wherever the file puts it (the crop box need not
 *   start at zero), Y up.
 * - Displayed space: the page as a viewer shows it, turned clockwise by its rotation, origin at the
 *   top-left of what is shown, Y down. Width and height swap at 90 and 270 degrees. What the person
 *   points at arrives here, and it is where the page view lays its fill spots out.
 * - The pdf.js viewport: displayed space in CSS pixels, at the scale the page is drawn.
 *
 * The server has the same conversions, and both are tested against one file of worked examples
 * (fixtures/public/pdf-geometry-vectors.json), so neither can drift from the other unnoticed.
 */

/** A box in box space. */
export interface PdfBox {
  x: number
  y: number
  width: number
  height: number
}

export interface PdfPoint {
  x: number
  y: number
}

/** The page's visible area in user space: its lower-left corner and its size. */
export interface CropBox {
  llx: number
  lly: number
  width: number
  height: number
}

/** A rectangle in user space: lower-left and upper-right corners, Y up. */
export interface UserSpaceRect {
  llx: number
  lly: number
  urx: number
  ury: number
}

/**
 * Where upright text for a box is drawn: the transform [a b c d e f] from a frame whose origin is the
 * box's bottom-left corner as displayed (X to the displayed right, Y displayed up) into user space, and
 * that frame's width and height, which are the box's displayed size.
 */
export interface UprightFrame {
  a: number
  b: number
  c: number
  d: number
  e: number
  f: number
  width: number
  height: number
}

/** A pdf.js viewport's transform [a b c d e f], from user space to CSS pixels. */
export type ViewportTransform = readonly [number, number, number, number, number, number]

/** A page's rotation as a number of clockwise quarter turns (0 to 3). A PDF only allows multiples of 90 degrees. */
export function quarterTurns(rotation: number): number {
  if (!Number.isInteger(rotation) || rotation % 90 !== 0) {
    throw new RangeError(`A page rotation must be a multiple of 90 degrees, was ${rotation}.`)
  }
  return (((rotation / 90) % 4) + 4) % 4
}

export function toUserSpace(box: PdfBox, crop: CropBox): UserSpaceRect {
  const llx = crop.llx + box.x
  const ury = crop.lly + crop.height - box.y
  return { llx, lly: ury - box.height, urx: llx + box.width, ury }
}

/** The corners may come in either order; the result is the box they span. */
export function fromUserSpace(rect: UserSpaceRect, crop: CropBox): PdfBox {
  const left = Math.min(rect.llx, rect.urx)
  const right = Math.max(rect.llx, rect.urx)
  const bottom = Math.min(rect.lly, rect.ury)
  const top = Math.max(rect.lly, rect.ury)
  return { x: left - crop.llx, y: crop.lly + crop.height - top, width: right - left, height: top - bottom }
}

export function pointFromUserSpace(userX: number, userY: number, crop: CropBox): PdfPoint {
  return { x: userX - crop.llx, y: crop.lly + crop.height - userY }
}

export function pointToUserSpace(point: PdfPoint, crop: CropBox): PdfPoint {
  return { x: crop.llx + point.x, y: crop.lly + crop.height - point.y }
}

/** The page's size as a viewer shows it. Only the crop box's size matters here, not where it starts. */
export interface PageSize {
  width: number
  height: number
}

export function displayedWidth(page: PageSize, rotation: number): number {
  return quarterTurns(rotation) % 2 === 0 ? page.width : page.height
}

export function displayedHeight(page: PageSize, rotation: number): number {
  return quarterTurns(rotation) % 2 === 0 ? page.height : page.width
}

export function pointToDisplayed(point: PdfPoint, page: PageSize, rotation: number): PdfPoint {
  const { width, height } = page
  switch (quarterTurns(rotation)) {
    case 0:
      return { x: point.x, y: point.y }
    case 1:
      return { x: height - point.y, y: point.x }
    case 2:
      return { x: width - point.x, y: height - point.y }
    default:
      return { x: point.y, y: width - point.x }
  }
}

export function pointFromDisplayed(point: PdfPoint, page: PageSize, rotation: number): PdfPoint {
  const { width, height } = page
  switch (quarterTurns(rotation)) {
    case 0:
      return { x: point.x, y: point.y }
    case 1:
      return { x: point.y, y: height - point.x }
    case 2:
      return { x: width - point.x, y: height - point.y }
    default:
      return { x: width - point.y, y: point.x }
  }
}

function spanning(first: PdfPoint, second: PdfPoint): PdfBox {
  return {
    x: Math.min(first.x, second.x),
    y: Math.min(first.y, second.y),
    width: Math.abs(second.x - first.x),
    height: Math.abs(second.y - first.y),
  }
}

export function boxToDisplayed(box: PdfBox, page: PageSize, rotation: number): PdfBox {
  return spanning(
    pointToDisplayed({ x: box.x, y: box.y }, page, rotation),
    pointToDisplayed({ x: box.x + box.width, y: box.y + box.height }, page, rotation),
  )
}

export function boxFromDisplayed(box: PdfBox, page: PageSize, rotation: number): PdfBox {
  return spanning(
    pointFromDisplayed({ x: box.x, y: box.y }, page, rotation),
    pointFromDisplayed({ x: box.x + box.width, y: box.y + box.height }, page, rotation),
  )
}

/**
 * The frame to draw a box's text in so that it reads upright once the viewer turns the page. A viewer
 * turns a page clockwise by its rotation, so at 90 degrees what is displayed as "right" is the page's
 * own "up" (user-space +Y), and what is displayed as "up" is the page's own "left" (user-space -X).
 */
export function uprightFrame(box: PdfBox, crop: CropBox, rotation: number): UprightFrame {
  const user = toUserSpace(box, crop)
  switch (quarterTurns(rotation)) {
    case 0:
      return { a: 1, b: 0, c: 0, d: 1, e: user.llx, f: user.lly, width: box.width, height: box.height }
    case 1:
      return { a: 0, b: 1, c: -1, d: 0, e: user.urx, f: user.lly, width: box.height, height: box.width }
    case 2:
      return { a: -1, b: 0, c: 0, d: -1, e: user.urx, f: user.ury, width: box.width, height: box.height }
    default:
      return { a: 0, b: -1, c: 1, d: 0, e: user.llx, f: user.ury, width: box.height, height: box.width }
  }
}

// ---- The pdf.js viewport -----------------------------------------------------------------------

function applyTransform(point: PdfPoint, transform: ViewportTransform): PdfPoint {
  const [a, b, c, d, e, f] = transform
  return { x: a * point.x + c * point.y + e, y: b * point.x + d * point.y + f }
}

function applyInverseTransform(point: PdfPoint, transform: ViewportTransform): PdfPoint {
  const [a, b, c, d, e, f] = transform
  const determinant = a * d - b * c
  const x = point.x - e
  const y = point.y - f
  return { x: (d * x - c * y) / determinant, y: (a * y - b * x) / determinant }
}

/**
 * Where a box lands on a page pdf.js drew: its user-space corners through the viewport's own
 * transform (what pdf.js's convertToViewportRectangle does), in CSS pixels from the canvas's top-left.
 */
export function boxToViewport(box: PdfBox, crop: CropBox, transform: ViewportTransform): PdfBox {
  const user = toUserSpace(box, crop)
  return spanning(applyTransform({ x: user.llx, y: user.lly }, transform), applyTransform({ x: user.urx, y: user.ury }, transform))
}

/** The page point, in box space, under a spot on a page pdf.js drew (CSS pixels from the canvas's top-left). */
export function pointFromViewport(point: PdfPoint, crop: CropBox, transform: ViewportTransform): PdfPoint {
  const user = applyInverseTransform(point, transform)
  return pointFromUserSpace(user.x, user.y, crop)
}

/**
 * The same place without pdf.js: displayed space scaled to the width the page is drawn at. For every
 * page this equals boxToViewport through the viewport pdf.js makes for the page at that scale, which
 * is what lets the page view lay its spots out in plain percentages of the page.
 */
export function boxToScaledDisplay(box: PdfBox, page: PageSize, rotation: number, scale: number): PdfBox {
  const shown = boxToDisplayed(box, page, rotation)
  return { x: shown.x * scale, y: shown.y * scale, width: shown.width * scale, height: shown.height * scale }
}

/** The page point, in box space, under a pointer at (x, y) CSS pixels on a page drawn `drawnWidth` pixels wide. */
export function pointFromScaledDisplay(point: PdfPoint, page: PageSize, rotation: number, drawnWidth: number): PdfPoint {
  const scale = drawnWidth / displayedWidth(page, rotation)
  return pointFromDisplayed({ x: point.x / scale, y: point.y / scale }, page, rotation)
}
