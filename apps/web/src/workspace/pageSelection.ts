import { nextTick, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import { caretFromPoint, PLACE_REFUSAL_WORDS, readPlace, type PagePoint, type PageReading } from '@/workspace/anchors'

/*
 * The pointer's ways of choosing a place for a new fill spot on the page: words selected there (with a
 * "Fill in here" button beside them), a right-click on the page's text (with a menu in place of the
 * browser's), and the ContextMenu key or Shift+F10 with a selection, which open the same menu at it.
 * Whatever is selected or clicked last on the page is also passed on, so that "here" in a request to
 * Brownie means that place.
 */

export interface PageMenu {
  x: number
  y: number
  reading: PageReading
  copyText: string
  returnTo: HTMLElement | null
}

/** About how wide the "Fill in here" bar is before it is drawn, and how far it keeps from the words around it. */
const BAR_WIDTH = 104
const BAR_GAP = 6

/** The bar's height: its button's, which is taller for a finger. */
function barHeight(): number {
  return typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches ? 44 : 32
}

/** A box in the window's pixels. */
export interface Box {
  top: number
  left: number
  right: number
  bottom: number
}

function overlaps(a: Box, b: Box): boolean {
  return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}

function overlapArea(a: Box, b: Box): number {
  const width = Math.min(a.right, b.right) - Math.max(a.left, b.left)
  const height = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top)
  return width > 0 && height > 0 ? width * height : 0
}

/**
 * Where the bar goes, in the window's pixels, for words selected over `lines` (the boxes of their words, line
 * by line): beside the end of the selection on its last line where that line has room; else below that line;
 * else above the first line. Never over the words selected or any of `words` (the page's other words and
 * spots, and whatever is laid over the page) when a place clear of them exists, and always inside `page`.
 * Along a line above or below, the place nearest the end of the selection is taken. Where nothing is clear,
 * the place over the least of the words is.
 */
export function placeBar(lines: readonly Box[], words: readonly Box[], page: Box, size: { width: number; height: number }): { top: number; left: number } {
  const { width, height } = size
  const first = lines.reduce((top, line) => (line.top < top.top ? line : top), lines[0]!)
  const last = lines.reduce((latest, line) => (line.bottom > latest.bottom || (line.bottom === latest.bottom && line.right > latest.right) ? line : latest), lines[0]!)
  const end = last.right
  const obstacles = [...lines, ...words]
  const minLeft = page.left
  const maxLeft = page.right - width
  const minTop = page.top
  const maxTop = page.bottom - height
  const fits = (top: number, left: number) => left >= minLeft - 0.5 && left <= maxLeft + 0.5 && top >= minTop - 0.5 && top <= maxTop + 0.5
  const box = (top: number, left: number): Box => ({ top, left, right: left + width, bottom: top + height })
  // A little room is kept to either side; the pill's rounded ends may reach into the spacing between lines.
  const footprint = (top: number, left: number): Box => ({ top: top + 3, left: left - 3, right: left + width + 3, bottom: top + height - 3 })
  const clear = (top: number, left: number) => fits(top, left) && !obstacles.some((word) => overlaps(footprint(top, left), word))
  // The places to the side of the selection's end, nearest first, along a row of the page.
  const along = (from: number): number[] => {
    const lefts: number[] = []
    const start = Math.max(minLeft, Math.min(from, maxLeft))
    for (let step = 0; start + step <= maxLeft || start - step >= minLeft; step += 8) {
      if (start + step <= maxLeft) lefts.push(start + step)
      if (step > 0 && start - step >= minLeft) lefts.push(start - step)
    }
    if (lefts.at(-1) !== maxLeft && maxLeft >= minLeft) lefts.push(maxLeft)
    return lefts
  }
  const level = last.top + (last.bottom - last.top - height) / 2
  const below = last.bottom + BAR_GAP
  const above = first.top - height - BAR_GAP
  // Beside the end, on the same line: only to the right of the selection, so the bar reads as about it.
  const besideLeft = end + BAR_GAP
  if (clear(level, besideLeft)) return { top: level, left: besideLeft }
  for (const top of [below, above]) {
    if (!fits(top, Math.max(minLeft, Math.min(end - width, maxLeft)))) continue
    const found = along(end - width).find((left) => clear(top, left))
    if (found !== undefined) return { top, left: found }
  }
  // Nothing is clear: the place over the fewest words, keeping to the order above.
  const candidates = [
    { top: level, left: Math.min(besideLeft, maxLeft) },
    { top: below, left: Math.max(minLeft, Math.min(end - width, maxLeft)) },
    { top: above, left: Math.max(minLeft, Math.min(end - width, maxLeft)) },
  ]
    .map(({ top, left }) => ({ top: Math.max(minTop, Math.min(top, maxTop)), left: Math.max(minLeft, Math.min(left, Math.max(minLeft, maxLeft))) }))
    .map((place) => ({ place, covered: obstacles.reduce((sum, word) => sum + overlapArea(box(place.top, place.left), word), 0) }))
  candidates.sort((a, b) => a.covered - b.covered)
  return candidates[0]!.place
}

/** The boxes of the words on each line of a range, leaving out the empty ones a line break leaves. */
function lineBoxes(range: Range): Box[] {
  if (typeof range.getClientRects === 'function') {
    const boxes = Array.from(range.getClientRects()).filter((rect) => rect.width > 0 && rect.height > 0)
    if (boxes.length > 0) return boxes
  }
  if (typeof range.getBoundingClientRect !== 'function') return []
  const box = range.getBoundingClientRect()
  return [box]
}

/** A control whose own text selection and menu are the person's, never replaced. */
function isOwnControl(element: Element | null): boolean {
  return element instanceof HTMLElement && (element.matches('input, textarea, select, [contenteditable=""], [contenteditable="true"]') || element.closest('.fill-spot') !== null)
}

export function usePageSelection(options: {
  sheet: Ref<HTMLElement | null>
  /** The element the bar is placed against. */
  body: Ref<HTMLElement | null>
  enabled: () => boolean
  /** A place was chosen for a new spot. */
  fillHere: (point: PagePoint, returnTo: HTMLElement | null) => void
  /** The latest place selected or clicked on the page. */
  placeSeen: (point: PagePoint) => void
  /** A dialog about a new spot is over the page: the bar is kept as it is, for focus to go back to. */
  paused?: () => boolean
  /** Whatever is drawn over the page, which the bar keeps clear of. */
  covers?: () => Element[]
  /** The bar has just appeared beside words selected on the page. */
  barShown?: () => void
}) {
  const bar = ref<{ top: number; left: number } | null>(null)
  const menu = ref<PageMenu | null>(null)
  /** Why the place chosen cannot take a fill spot, or what was done ("Copied."). */
  const message = ref('')
  /** The latest selection on the page, kept because pressing a button elsewhere may clear the live one. */
  let lastRange: Range | null = null
  /** The contextmenu event the ContextMenu key sends after its keydown, which the menu already answered. */
  let menuOpenedByKeyAt = 0
  /**
   * The selection as it was when a right-click began. Some browsers select the word under the pointer
   * as the button goes down, which is not what the person chose; undefined when no right-click began.
   */
  let rangeBeforeRightPress: Range | null | undefined

  function selectionRange(): Range | null {
    const selection = window.getSelection()
    if (!selection || selection.rangeCount === 0) return null
    const range = selection.getRangeAt(0)
    const sheet = options.sheet.value
    if (!sheet || !sheet.contains(range.startContainer) || !sheet.contains(range.endContainer)) return null
    return range
  }

  function reading(range: Range): PageReading {
    const sheet = options.sheet.value!
    return readPlace({ node: range.startContainer, offset: range.startOffset }, { node: range.endContainer, offset: range.endOffset }, sheet)
  }

  /** The bar's element, once it is drawn in the page's body. */
  function barElement(): HTMLElement | null {
    return options.body.value?.querySelector<HTMLElement>('.fill-here-bar') ?? null
  }

  /** The bar's size as drawn; null before it is drawn (or while it stands down), when an estimate is used. */
  function drawnSize(): { width: number; height: number } | null {
    const element = barElement()
    return element && element.offsetWidth > 0 && element.offsetHeight > 0 ? { width: element.offsetWidth, height: element.offsetHeight } : null
  }

  /** The words and spots on the page near the lines selected, line by line, and whatever is laid over the page. */
  function wordsNear(lines: readonly Box[], reach: number): Box[] {
    const sheet = options.sheet.value
    const words: Box[] = []
    if (sheet) {
      const top = Math.min(...lines.map((line) => line.top)) - reach
      const bottom = Math.max(...lines.map((line) => line.bottom)) + reach
      for (const element of sheet.querySelectorAll<HTMLElement>('p, h3, th, button')) {
        const box = element.getBoundingClientRect()
        if (box.bottom < top || box.top > bottom) continue
        const contents = window.document.createRange()
        contents.selectNodeContents(element)
        if (typeof contents.getClientRects !== 'function') continue
        for (const rect of Array.from(contents.getClientRects())) {
          if (rect.width > 0 && rect.height > 0 && rect.bottom >= top && rect.top <= bottom) words.push(rect)
        }
      }
    }
    for (const cover of options.covers?.() ?? []) {
      const box = cover.getBoundingClientRect()
      if (box.width > 0 && box.height > 0) words.push(box)
    }
    return words
  }

  /** Where the bar goes, in pixels from the top left of the page's body: see `placeBar`. */
  function place(range: Range): { top: number; left: number } {
    const body = options.body.value
    const sheet = options.sheet.value
    const lines = lineBoxes(range)
    if (!body || !sheet || lines.length === 0) return { top: 0, left: 0 }
    const size = drawnSize() ?? { width: BAR_WIDTH, height: barHeight() }
    const frame = body.getBoundingClientRect()
    const spot = placeBar(lines, wordsNear(lines, 2 * (size.height + BAR_GAP)), sheet.getBoundingClientRect(), size)
    return { top: spot.top - frame.top, left: spot.left - frame.left }
  }

  /** The scrolling area that holds the page, where focus goes when the bar it was on goes away. */
  function pageArea(): HTMLElement | null {
    return options.sheet.value?.closest<HTMLElement>('[tabindex="0"]') ?? null
  }

  function hideBar(): void {
    if (bar.value === null) return
    const active = window.document.activeElement
    const hadFocus = active instanceof HTMLElement && barElement()?.contains(active) === true
    bar.value = null
    if (hadFocus) void nextTick(() => pageArea()?.focus({ preventScroll: true }))
  }

  /**
   * The bar follows the page's selection: beside words selected in the page's text, and gone the moment no
   * words there are selected, whatever has focus, so it is never left over the page about nothing.
   */
  function refresh(): void {
    // Typing in a fill spot moves the page's selection too; that is never a place for a new one.
    if (isOwnControl(window.document.activeElement)) {
      hideBar()
      return
    }
    const range = selectionRange()
    if (!range) {
      hideBar()
      return
    }
    lastRange = range.cloneRange()
    message.value = ''
    const read = reading(range)
    if ('point' in read) options.placeSeen(read.point)
    if (!options.enabled() || range.collapsed) {
      hideBar()
      return
    }
    const appearing = bar.value === null
    bar.value = place(range)
    if (!appearing) return
    options.barShown?.()
    // Placed with an estimate of its size; once drawn, placed again by the size it really is.
    void nextTick(() => {
      const live = selectionRange()
      if (bar.value !== null && live && !live.collapsed && drawnSize() !== null) bar.value = place(live)
    })
  }

  function onSelectionChange(): void {
    if (options.paused?.()) return
    refresh()
  }

  /** Moving into a fill spot by keyboard need not change the selection; the bar goes all the same. */
  function onFocusIn(event: FocusEvent): void {
    if (bar.value !== null && !options.paused?.() && event.target instanceof Element && isOwnControl(event.target)) hideBar()
  }

  /**
   * The dialog over the page has closed. Focus back on the bar means it closed without a change, so the words
   * chosen are selected again and the bar stays beside them; otherwise the bar follows the selection as it is.
   */
  function resume(): void {
    void nextTick(() => {
      const active = window.document.activeElement
      const chosen = lastRange
      const sheet = options.sheet.value
      const backOnBar = active instanceof HTMLElement && barElement()?.contains(active) === true
      if (backOnBar && chosen && !chosen.collapsed && sheet?.contains(chosen.startContainer) && sheet.contains(chosen.endContainer) && !selectionRange()) {
        const selection = window.getSelection()
        selection?.removeAllRanges()
        selection?.addRange(chosen)
      }
      refresh()
    })
  }

  /** Escape on the bar: the words are no longer selected, and focus goes back to the page. */
  function dismissBar(): void {
    window.getSelection()?.removeAllRanges()
    hideBar()
  }

  function choose(read: PageReading, returnTo: HTMLElement | null): void {
    if ('refusal' in read) {
      message.value = PLACE_REFUSAL_WORDS[read.refusal]
      return
    }
    message.value = ''
    options.fillHere(read.point, returnTo)
  }

  /** The bar's button: the selection it was shown for. */
  function fillHereFromBar(button: HTMLElement): void {
    const range = selectionRange() ?? lastRange
    if (!range) return
    choose(reading(range), button)
  }

  function openMenu(x: number, y: number, read: PageReading, range: Range | null): void {
    const active = window.document.activeElement
    // Focus goes back where it was when the menu closes, or else to the scrolling area that holds the page.
    const returnTo =
      active instanceof HTMLElement && active !== window.document.body ? active : (options.sheet.value?.closest<HTMLElement>('[tabindex="0"]') ?? null)
    menu.value = { x, y, reading: read, copyText: range && !range.collapsed ? range.toString() : '', returnTo }
  }

  function onPointerDown(event: PointerEvent): void {
    if (event.button === 2) rangeBeforeRightPress = selectionRange()?.cloneRange() ?? null
  }

  function onContextMenu(event: MouseEvent): void {
    if (Date.now() - menuOpenedByKeyAt < 500) {
      event.preventDefault()
      return
    }
    // Shift+right-click is the browser's own menu, and a control keeps its own.
    if (!options.enabled() || event.shiftKey || !(event.target instanceof Element)) return
    if (isOwnControl(event.target) || event.target.closest('button, a') || !event.target.closest('[data-part]')) return
    event.preventDefault()
    const before = rangeBeforeRightPress !== undefined ? rangeBeforeRightPress : selectionRange()
    rangeBeforeRightPress = undefined
    // A right-click on words already selected is about those words; anywhere else, about the point clicked.
    const range = before && !before.collapsed && before.intersectsNode(event.target) ? before : null
    let read: PageReading
    if (range) {
      read = reading(range)
    } else {
      const caret = caretFromPoint(window.document, event.clientX, event.clientY)
      read = caret ? readPlace(caret, caret, options.sheet.value!) : { refusal: 'NOT_ON_PAGE' }
    }
    openMenu(event.clientX, event.clientY, read, range)
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'ContextMenu' && !(event.key === 'F10' && event.shiftKey)) return
    if (!options.enabled() || isOwnControl(window.document.activeElement)) return
    const range = selectionRange()
    if (!range) return
    event.preventDefault()
    menuOpenedByKeyAt = Date.now()
    const box = typeof range.getBoundingClientRect === 'function' ? range.getBoundingClientRect() : null
    openMenu(box?.left ?? 0, box?.bottom ?? 0, reading(range), range)
  }

  function closeMenu(): void {
    const returnTo = menu.value?.returnTo ?? null
    menu.value = null
    if (returnTo?.isConnected) returnTo.focus()
  }

  function menuFillHere(): void {
    const current = menu.value
    if (!current) return
    menu.value = null
    if ('refusal' in current.reading && current.returnTo?.isConnected) current.returnTo.focus()
    choose(current.reading, current.returnTo)
  }

  async function menuCopy(): Promise<void> {
    const text = menu.value?.copyText ?? ''
    closeMenu()
    if (!text) return
    try {
      if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') await navigator.clipboard.writeText(text)
      else window.document.execCommand('copy')
      message.value = 'Copied.'
    } catch {
      message.value = 'Your browser did not let Brownie copy that. Use your keyboard to copy it instead.'
    }
  }

  onMounted(() => {
    window.document.addEventListener('selectionchange', onSelectionChange)
    window.document.addEventListener('keydown', onKeydown)
    window.document.addEventListener('focusin', onFocusIn)
  })
  onBeforeUnmount(() => {
    window.document.removeEventListener('selectionchange', onSelectionChange)
    window.document.removeEventListener('keydown', onKeydown)
    window.document.removeEventListener('focusin', onFocusIn)
  })

  return { bar, menu, message, onContextMenu, onPointerDown, fillHereFromBar, dismissBar, resume, closeMenu, menuFillHere, menuCopy }
}
