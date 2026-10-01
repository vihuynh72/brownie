import type { CropBox, ViewportTransform } from '@/workspace/pdfGeometry'

/**
 * pdf.js's own viewport transform for a page, written out from its PageViewport: the crop box as the
 * view box, turned by the rotation and scaled. It stands in for pdf.js in tests because pdf.js exports
 * no way to make a viewport without a loaded page; the page view reads the real one from pdf.js.
 */
export function pdfJsViewportTransform(crop: CropBox, rotation: number, scale: number): ViewportTransform {
  const viewBox = [crop.llx, crop.lly, crop.llx + crop.width, crop.lly + crop.height] as const
  const centerX = (viewBox[2] + viewBox[0]) / 2
  const centerY = (viewBox[3] + viewBox[1]) / 2
  const [rotateA, rotateB, rotateC, rotateD] = (
    { 0: [1, 0, 0, -1], 90: [0, 1, 1, 0], 180: [-1, 0, 0, 1], 270: [0, -1, -1, 0] } as Record<number, [number, number, number, number]>
  )[((rotation % 360) + 360) % 360]!
  const offsetCanvasX = rotateA === 0 ? Math.abs(centerY - viewBox[1]) * scale : Math.abs(centerX - viewBox[0]) * scale
  const offsetCanvasY = rotateA === 0 ? Math.abs(centerX - viewBox[0]) * scale : Math.abs(centerY - viewBox[1]) * scale
  return [
    rotateA * scale,
    rotateB * scale,
    rotateC * scale,
    rotateD * scale,
    offsetCanvasX - rotateA * scale * centerX - rotateC * scale * centerY,
    offsetCanvasY - rotateB * scale * centerX - rotateD * scale * centerY,
  ]
}
