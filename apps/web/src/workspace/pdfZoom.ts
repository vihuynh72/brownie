/*
 * How large a PDF form's pages are drawn. A point is 1/72 of an inch and a CSS pixel 1/96, so a page
 * at the size it prints is drawn 4/3 of a pixel to the point. Every size here is a scale in CSS pixels
 * per point; the person sees it as a percentage of the printed size, the way other PDF viewers say it.
 * The boxes over a page are placed in fractions of the page (boxPlacement), so they follow it at any
 * size without being moved. Nothing here touches the DOM.
 */

/** CSS pixels per point for a page at the size it prints. */
export const PRINTED_SCALE = 96 / 72

/**
 * The largest Fit width draws a page: 1.5 pixels to the point, where 11 pt text is about 16 pixels, the
 * size of ordinary text on screen. A wide window leaves the rest of its width empty rather than draw a
 * letter page a foot and a half across; the zoom buttons go further.
 */
export const FIT_WIDTH_MAX_SCALE = 1.5

/** The sizes the zoom buttons step through, as fractions of the printed size. */
export const ZOOM_STEPS: readonly number[] = [0.5, 0.75, 1, 1.25, 1.5, 2]

/**
 * The most pixels one page's picture holds: 4096 by 4096, the limit of the browsers with the smallest one
 * (Safari on iPhone and iPad), past which a canvas stays blank. A page that would need more is drawn at a
 * lower resolution and shown at the same size, a little softer, rather than not at all.
 */
export const MAX_CANVAS_PIXELS = 4096 * 4096

/** How the pages are sized: to the width they are shown in, or at a scale the person chose. */
export type PdfZoom = { kind: 'fit' } | { kind: 'scale'; scale: number }

/** The scale Fit width draws a page at, in a space `available` CSS pixels wide. */
export function fitWidthScale(available: number, shownWidth: number): number {
  if (!(available > 0) || !(shownWidth > 0)) return FIT_WIDTH_MAX_SCALE
  return Math.min(available / shownWidth, FIT_WIDTH_MAX_SCALE)
}

/** A scale as the person reads it: a whole percentage of the printed size. */
export function zoomPercent(scale: number): number {
  return Math.round((scale / PRINTED_SCALE) * 100)
}

/**
 * The next size the zoom buttons go to from `scale`, or null when there is none that way. A step must
 * change the size by at least a twentieth, so from a fitted page just under a step, Zoom in goes to the
 * step after it rather than one that looks the same.
 */
export function zoomStep(scale: number, direction: 'in' | 'out'): number | null {
  const steps = ZOOM_STEPS.map((step) => step * PRINTED_SCALE)
  if (direction === 'in') return steps.find((step) => step > scale * 1.05) ?? null
  return [...steps].reverse().find((step) => step < scale / 1.05) ?? null
}

/**
 * The width of a page as CSS, with `shownWidth` its width in points as shown: for Fit width, the space
 * it is in up to the largest Fit width draws; at a chosen scale, whole pixels, so the page's picture
 * lands on the screen's pixels.
 */
export function pageWidthCss(shownWidth: number, zoom: PdfZoom): string {
  if (zoom.kind === 'fit') return `min(100%, ${Math.round(shownWidth * FIT_WIDTH_MAX_SCALE)}px)`
  return `${Math.round(shownWidth * zoom.scale)}px`
}

/**
 * Canvas pixels per CSS pixel for a page drawn `cssWidth` by `cssHeight`: the screen's own density, so
 * text is as sharp as the screen can show it, unless the picture would hold more than MAX_CANVAS_PIXELS.
 */
export function canvasResolution(cssWidth: number, cssHeight: number, devicePixelRatio: number): number {
  const density = Number.isFinite(devicePixelRatio) && devicePixelRatio > 0 ? devicePixelRatio : 1
  const area = cssWidth * cssHeight
  if (!(area > 0)) return density
  return Math.min(density, Math.sqrt(MAX_CANVAS_PIXELS / area))
}

/**
 * Canvas pixels for a length of `cssLength` CSS pixels at `resolution`: rounded down, so a picture never holds
 * more than canvasResolution allows, but not cut a pixel short by a rounding error in the last digit of a
 * whole length (pdf.js can give a page 448 pixels wide as a hair under 448), which would blur the page.
 */
export function canvasPixels(cssLength: number, resolution: number): number {
  return Math.floor(cssLength * resolution + 1e-6)
}

/**
 * Where to scroll so that what was in the middle of the view is still there after the pages change size,
 * along one direction: `scroll` and `view` are the scroller's position and size, `start` where the pages
 * begin in what it scrolls, `before` and `after` their length. When the middle of the view is above the
 * pages (the notes over them), the view is left where it is.
 */
export function keepMiddle(scroll: number, view: number, start: number, before: number, after: number): number {
  const middle = scroll + view / 2
  if (!(before > 0) || !(after > 0) || middle < start) return scroll
  const fraction = Math.min((middle - start) / before, 1)
  return Math.max(0, start + fraction * after - view / 2)
}
