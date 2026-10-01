import { describe, expect, it } from 'vitest'
import { placeBar, type Box } from '@/workspace/pageSelection'

/*
 * A page 400 wide from (100, 100), with lines of words 18 high every 24 pixels, and a bar 96 by 32.
 * Each line's words are given by where they start and end.
 */
const PAGE: Box = { top: 100, left: 100, right: 500, bottom: 900 }
const SIZE = { width: 96, height: 32 }

function line(index: number, left: number, right: number): Box {
  const top = 200 + index * 24
  return { top, left, right, bottom: top + 18 }
}

function overlaps(a: Box, b: Box): boolean {
  return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}

/** The bar's box, trimmed by the few pixels its rounded ends may reach into the spacing between lines. */
function barBox(place: { top: number; left: number }): Box {
  return { top: place.top + 3, left: place.left, right: place.left + SIZE.width, bottom: place.top + SIZE.height - 3 }
}

describe('placeBar: where "Fill in here" goes', () => {
  it('beside the end of the selection, on its line, when nothing follows there', () => {
    const selected = line(1, 200, 280)
    const words = [line(0, 120, 480), line(1, 120, 200), line(2, 120, 480)]

    const place = placeBar([selected], words, PAGE, SIZE)
    expect(place).toEqual({ top: 224 + (18 - 32) / 2, left: 286 })
    for (const word of words) expect(overlaps(barBox(place), word)).toBe(false)
  })

  it('below the last line when words follow on the same line, nearest the end of the selection', () => {
    const selected = line(1, 200, 280)
    // The line goes on after the selection; the next line is short, so there is room under the selection's end.
    const words = [line(0, 120, 480), line(1, 120, 480), line(2, 120, 150)]

    const place = placeBar([selected], words, PAGE, SIZE)
    expect(place.top).toBe(224 + 18 + 6)
    expect(place.left).toBe(280 - 96)
    for (const word of words) expect(overlaps(barBox(place), word)).toBe(false)
  })

  it('above the first line when neither the line nor the space below has room, never over the words', () => {
    // "…return it to the garden office." under a centred heading, with a full line after it.
    const heading = line(0, 180, 380)
    const selected = line(1, 330, 420)
    const words = [heading, line(1, 120, 430), line(2, 120, 470)]

    const place = placeBar([selected], words, PAGE, SIZE)
    expect(place.top).toBe(224 - 32 - 6)
    for (const word of [...words, selected]) expect(overlaps(barBox(place), word)).toBe(false)
    // To the right of the heading's last word, as near the selection's end as that allows.
    expect(place.left).toBeGreaterThanOrEqual(380)
    expect(place.left + 96).toBeLessThanOrEqual(PAGE.right)
  })

  it('keeps inside the page', () => {
    // At the page's right edge and top: no room beside it or above it, so below, pulled in from the edge.
    const selected: Box = { top: 104, left: 420, right: 498, bottom: 122 }
    const place = placeBar([selected], [], PAGE, SIZE)
    expect(place.left).toBeGreaterThanOrEqual(PAGE.left)
    expect(place.left + SIZE.width).toBeLessThanOrEqual(PAGE.right)
    expect(place.top).toBeGreaterThanOrEqual(PAGE.top)
    expect(place.top).toBe(122 + 6)
  })

  it('goes after the last line of a selection over several lines', () => {
    const lines = [line(1, 300, 480), line(2, 120, 200)]
    const words = [line(1, 120, 480), line(2, 120, 200), line(3, 120, 480)]
    expect(placeBar(lines, words, PAGE, SIZE)).toEqual({ top: 248 + (18 - 32) / 2, left: 206 })
  })

  it('keeps clear of what is laid over the page, such as the bar about a spot', () => {
    const selected = line(1, 200, 280)
    const spotBar: Box = { top: 240, left: 100, right: 500, bottom: 330 }
    const place = placeBar([selected], [spotBar], PAGE, SIZE)
    expect(overlaps(barBox(place), spotBar)).toBe(false)
    expect(place.top).toBe(224 - 32 - 6)
  })

  it('covers as little as it can when every place near the words has words in it', () => {
    const selected = line(1, 200, 280)
    const words = [line(-1, 100, 500), line(0, 100, 500), line(1, 100, 500), line(2, 100, 500), line(3, 100, 500)]
    const place = placeBar([selected], words, PAGE, SIZE)
    expect(place.left).toBeGreaterThanOrEqual(PAGE.left)
    expect(place.left + SIZE.width).toBeLessThanOrEqual(PAGE.right)
  })
})
