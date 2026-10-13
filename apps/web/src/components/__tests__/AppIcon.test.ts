import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { ICONS, type IconName } from '@/components/icons'
import { axe } from '@/test/axe'

const NAMES = Object.keys(ICONS) as IconName[]

/** Names the app already calls icons by: each has to keep drawing something whatever the set looks like. */
const NAMES_IN_USE: IconName[] = [
  'home', 'chat', 'plus', 'trash', 'restore', 'shield', 'link', 'upload', 'document', 'sign-out', 'sign-in',
  'panel-collapse', 'panel-expand', 'menu', 'undo', 'history', 'send', 'close', 'download', 'share', 'more', 'account',
]

/** How many numbers each SVG path command takes. */
const ARITY: Record<string, number> = { m: 2, l: 2, h: 1, v: 1, c: 6, s: 4, q: 4, t: 2, a: 7, z: 0 }

/**
 * Every point a piece of path data names, in the icon's own units: where each segment ends and, for a curve, its
 * control points. An arc contributes only its end (its other numbers are radii and flags). Enough to tell whether an
 * icon sits inside its frame, which a pattern match on the text cannot.
 */
function pathPoints(d: string): Array<[number, number]> {
  const points: Array<[number, number]> = []
  let x = 0
  let y = 0
  let startX = 0
  let startY = 0
  for (const [, letter, rest] of d.matchAll(/([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)/g)) {
    const command = letter!.toLowerCase()
    const relative = letter === command
    const numbers = (rest!.match(/[-+]?(?:\d*\.\d+|\d+\.?)(?:e[-+]?\d+)?/gi) ?? []).map(Number)
    const size = ARITY[command]!
    if (size === 0) {
      x = startX
      y = startY
      continue
    }
    for (let i = 0; i + size <= numbers.length; i += size) {
      const n = numbers.slice(i, i + size)
      const baseX = relative ? x : 0
      const baseY = relative ? y : 0
      if (command === 'h') {
        x = baseX + n[0]!
      } else if (command === 'v') {
        y = baseY + n[0]!
      } else {
        // The last two numbers of every other command are its end point; a curve's earlier pairs are control points.
        for (let k = 0; k < n.length - 2 && command !== 'a'; k += 2) points.push([baseX + n[k]!, baseY + n[k + 1]!])
        x = baseX + n[size - 2]!
        y = baseY + n[size - 1]!
      }
      points.push([x, y])
      // Only the first pair of a move starts the shape; any further pairs are lines that continue from it.
      if (command === 'm' && i === 0) {
        startX = x
        startY = y
      }
    }
  }
  return points
}

const count = (text: string, pattern: RegExp): number => text.match(pattern)?.length ?? 0

describe('the icon set', () => {
  it('gives every declared name something to draw, well formed and inside its 24-unit frame', () => {
    expect(NAMES.length).toBeGreaterThan(0)
    for (const name of NAMES) {
      const { kind, paths } = ICONS[name]
      expect(['outline', 'stroke'], `${name}: how it is drawn`).toContain(kind)
      expect(paths.length, `${name}: number of paths`).toBeGreaterThan(0)
      for (const d of paths) {
        expect(d, `${name}: path data starts with a move`).toMatch(/^[Mm]/)
        expect(d, `${name}: path data holds only path text`).toMatch(/^[MmLlHhVvCcSsQqTtAaZz\d\s.,+-]+$/)
        const points = pathPoints(d)
        expect(points.length, `${name}: points in its path`).toBeGreaterThan(1)
        for (const [px, py] of points) {
          const inside = Number.isFinite(px) && Number.isFinite(py) && px >= 0 && px <= 24 && py >= 0 && py <= 24
          expect(inside, `${name}: point ${px},${py} is inside the frame`).toBe(true)
        }
        // A filled shape left open would be closed by the browser at a point nobody chose.
        if (kind === 'outline') expect(count(d, /[Mm]/g), `${name}: every outline closed`).toBe(count(d, /[Zz]/g))
      }
    }
  })

  it('still answers to every name the app has used', () => {
    for (const name of NAMES_IN_USE) expect(NAMES, name).toContain(name)
  })
})

describe('AppIcon', () => {
  it('draws a stroke icon as unfilled, round-ended lines at the size asked for', () => {
    const icon = mount(AppIcon, { props: { name: 'menu', size: 32 } })
    expect(icon.element.tagName.toLowerCase()).toBe('svg')
    expect(icon.attributes('width')).toBe('32')
    expect(icon.attributes('height')).toBe('32')
    expect(icon.attributes('viewBox')).toBe('0 0 24 24')
    expect(icon.attributes('fill')).toBe('none')
    expect(icon.attributes('stroke')).toBe('currentColor')
    expect(icon.attributes('stroke-width')).toBe('1.7')
    expect(icon.attributes('stroke-linecap')).toBe('round')
    expect(icon.attributes('stroke-linejoin')).toBe('round')
    expect(icon.findAll('path').map((path) => path.attributes('d'))).toEqual([...ICONS.menu.paths])
  })

  it('draws an outline icon as filled shapes with no stroke on top of them', () => {
    const icon = mount(AppIcon, { props: { name: 'upload', size: 40 } })
    expect(icon.element.tagName.toLowerCase()).toBe('svg')
    expect(icon.attributes('width')).toBe('40')
    expect(icon.attributes('height')).toBe('40')
    expect(icon.attributes('viewBox')).toBe('0 0 24 24')
    expect(icon.attributes('fill')).toBe('currentColor')
    expect(icon.attributes('stroke')).toBeUndefined()
    expect(icon.attributes('stroke-width')).toBeUndefined()
    expect(icon.findAll('path').map((path) => path.attributes('d'))).toEqual([...ICONS.upload.paths])
  })

  it('is 20 units wide unless told otherwise', () => {
    const icon = mount(AppIcon, { props: { name: 'plus' } })
    expect(icon.attributes('width')).toBe('20')
    expect(icon.attributes('height')).toBe('20')
  })

  it('is hidden from assistive technology and left out of the tab order', () => {
    for (const name of ['menu', 'upload'] as const) {
      const icon = mount(AppIcon, { props: { name } })
      expect(icon.attributes('aria-hidden')).toBe('true')
      expect(icon.attributes('focusable')).toBe('false')
    }
  })

  it('repaints when the name changes between the two ways of drawing', async () => {
    const icon = mount(AppIcon, { props: { name: 'menu' } })
    await icon.setProps({ name: 'panel-expand' })
    expect(icon.attributes('fill')).toBe('currentColor')
    expect(icon.attributes('stroke')).toBeUndefined()
    expect(icon.findAll('path').map((path) => path.attributes('d'))).toEqual([...ICONS['panel-expand'].paths])

    await icon.setProps({ name: 'menu' })
    expect(icon.attributes('fill')).toBe('none')
    expect(icon.attributes('stroke')).toBe('currentColor')
    expect(icon.findAll('path')).toHaveLength(ICONS.menu.paths.length)
  })

  it('passes an accessibility scan beside a visible label', async () => {
    const Labelled = defineComponent({
      render: () => h('button', { type: 'button' }, ['Upload', h(AppIcon, { name: 'upload' })]),
    })
    const wrapper = mount(Labelled, { attachTo: document.body })
    expect(await axe(wrapper.element as HTMLElement)).toHaveNoViolations()
    wrapper.unmount()
  })
})
