import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import FillHereBar from '@/components/workspace/FillHereBar.vue'
import SpotContextMenu from '@/components/workspace/SpotContextMenu.vue'
import { axe } from '@/test/axe'

let wrapper: VueWrapper | null = null

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('FillHereBar', () => {
  it('is a small primary button with an icon and the words "Fill in here", placed where the page says', () => {
    wrapper = mount(FillHereBar, { props: { top: 40, left: 120 } })
    const bar = wrapper.get('.fill-here-bar')
    expect((bar.element as HTMLElement).style.top).toBe('40px')
    expect((bar.element as HTMLElement).style.left).toBe('120px')

    const button = wrapper.get('button')
    expect(button.classes()).toEqual(expect.arrayContaining(['button', 'button--primary', 'button--small']))
    expect(button.text()).toBe('Fill in here')
    // The icon is a picture beside the words, so the button's name is the words alone.
    expect(button.get('svg').attributes('aria-hidden')).toBe('true')
    expect(button.element.firstElementChild?.tagName.toLowerCase()).toBe('svg')
  })

  it('hands the page its own button when pressed, and does not take the selection away by being pressed', async () => {
    wrapper = mount(FillHereBar, { props: { top: 0, left: 0 }, attachTo: document.body })
    const button = wrapper.get('button')

    const down = new MouseEvent('mousedown', { bubbles: true, cancelable: true })
    button.element.dispatchEvent(down)
    expect(down.defaultPrevented).toBe(true)

    await button.trigger('click')
    expect(wrapper.emitted('fill-here')).toEqual([[button.element]])
  })

  it('lets the words go when Escape is pressed on it', async () => {
    wrapper = mount(FillHereBar, { props: { top: 0, left: 0 } })
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    wrapper.get('button').element.dispatchEvent(escape)
    expect(escape.defaultPrevented).toBe(true)
    expect(wrapper.emitted('dismiss')).toHaveLength(1)
  })

  it('passes an accessibility scan', async () => {
    wrapper = mount(FillHereBar, { props: { top: 0, left: 0 }, attachTo: document.body })
    expect(await axe(wrapper.element as HTMLElement)).toHaveNoViolations()
  })
})

describe('SpotContextMenu', () => {
  const menu = () => document.body.querySelector<HTMLElement>('.spot-menu')!
  const items = () => [...menu().querySelectorAll<HTMLElement>('[role="menuitem"]')]

  async function open(canCopy = true): Promise<VueWrapper> {
    wrapper = mount(SpotContextMenu, { props: { x: 30, y: 40, canCopy }, attachTo: document.body })
    await flushPromises()
    return wrapper
  }

  it('offers "Fill in here" and, with words selected, "Copy", each with an icon beside its words', async () => {
    await open()
    expect(items().map((item) => item.textContent)).toEqual(['Fill in here', 'Copy'])
    for (const item of items()) {
      expect(item.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
      expect(item.firstElementChild?.tagName.toLowerCase()).toBe('svg')
    }
    expect(menu().querySelector('[role="menu"]')?.getAttribute('aria-label')).toBe('Page text')
    expect(menu().querySelector('.spot-menu__hint')?.textContent).toBe("Shift+right-click shows your browser's menu")
    wrapper!.unmount()
    wrapper = null

    await open(false)
    expect(items().map((item) => item.textContent)).toEqual(['Fill in here'])
  })

  it('takes focus on its first item and moves between items with the arrow keys, Home and End, wrapping round', async () => {
    await open()
    const [first, second] = items()
    expect(document.activeElement).toBe(first)

    const press = (key: string) => document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
    press('ArrowDown')
    expect(document.activeElement).toBe(second)
    press('ArrowDown')
    expect(document.activeElement).toBe(first)
    press('ArrowUp')
    expect(document.activeElement).toBe(second)
    press('Home')
    expect(document.activeElement).toBe(first)
    press('End')
    expect(document.activeElement).toBe(second)
  })

  it('closes on Escape and on Tab, and when a press lands outside it', async () => {
    const page = await open()
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    items()[0]!.dispatchEvent(escape)
    expect(escape.defaultPrevented).toBe(true)
    items()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }))
    expect(page.emitted('close')).toHaveLength(2)

    items()[0]!.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    expect(page.emitted('close')).toHaveLength(2)
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    expect(page.emitted('close')).toHaveLength(3)
  })

  it('reports the item chosen', async () => {
    const page = await open()
    items()[0]!.click()
    items()[1]!.click()
    expect(page.emitted('fill-here')).toHaveLength(1)
    expect(page.emitted('copy')).toHaveLength(1)
  })

  it('passes an accessibility scan', async () => {
    await open()
    expect(await axe(menu())).toHaveNoViolations()
  })
})
