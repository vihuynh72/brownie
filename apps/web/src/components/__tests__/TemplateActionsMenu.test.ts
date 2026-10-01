import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import TemplateActionsMenu from '@/components/TemplateActionsMenu.vue'
import { axe } from '@/test/axe'

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

/** jsdom lays nothing out, so every box a test depends on is given here. */
function box(left: number, top: number, width: number, height: number): DOMRect {
  return { left, top, width, height, right: left + width, bottom: top + height, x: left, y: top, toJSON: () => ({}) } as DOMRect
}

const MENU_SIZE = { width: 192, height: 48 }

let anchor: HTMLButtonElement

function setViewport(width: number, height: number): void {
  Object.defineProperty(document.documentElement, 'clientWidth', { value: width, configurable: true })
  Object.defineProperty(document.documentElement, 'clientHeight', { value: height, configurable: true })
}

/** Mounted, placed and focused: the position lands on the render after the menu has measured itself. */
async function mountMenu(props: Partial<{ point: { x: number; y: number } | null; startAt: 'first' | 'last' }> = {}) {
  const mounted = mount(TemplateActionsMenu, {
    props: { id: 'template-actions-menu', labelledBy: 'template-more-1', anchor, ...props },
    attachTo: document.body,
  })
  await flushPromises()
  return mounted
}

function menuElement(): HTMLElement {
  const menu = document.querySelector<HTMLElement>('[role="menu"]')
  if (!menu) throw new Error('No menu on the page')
  return menu
}

function item(): HTMLElement {
  return menuElement().querySelector<HTMLElement>('[role="menuitem"]')!
}

function press(key: string, target: Element = document.activeElement ?? document.body): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
  target.dispatchEvent(event)
  return event
}

describe('TemplateActionsMenu', () => {
  let wrapper: VueWrapper | null = null
  const realRect = HTMLElement.prototype.getBoundingClientRect

  beforeEach(() => {
    document.body.innerHTML = ''
    anchor = document.createElement('button')
    anchor.id = 'template-more-1'
    anchor.textContent = 'More for Club minutes'
    document.body.appendChild(anchor)
    setViewport(1000, 800)
    // The menu measures itself and the button it hangs under; everything else keeps jsdom's empty box.
    HTMLElement.prototype.getBoundingClientRect = function (this: HTMLElement) {
      if (this === anchor) return box(200, 100, 32, 32)
      if (this.getAttribute('role') === 'menu') return box(0, 0, MENU_SIZE.width, MENU_SIZE.height)
      return realRect.call(this)
    }
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    HTMLElement.prototype.getBoundingClientRect = realRect
    Reflect.deleteProperty(document.documentElement, 'clientWidth')
    Reflect.deleteProperty(document.documentElement, 'clientHeight')
  })

  it('is a menu named by the button that opened it, with its one item focused', async () => {
    wrapper = await mountMenu()

    const menu = menuElement()
    expect(menu.id).toBe('template-actions-menu')
    expect(menu.getAttribute('aria-labelledby')).toBe('template-more-1')
    expect(item().textContent?.trim()).toBe('Move to Trash Bin')
    // Items are reached with the arrow keys, not with Tab.
    expect(item().getAttribute('tabindex')).toBe('-1')
    expect(document.activeElement).toBe(item())
  })

  it('opens under the button, left edges aligned', async () => {
    wrapper = await mountMenu()

    expect(menuElement().style.top).toBe('136px')
    expect(menuElement().style.left).toBe('200px')
  })

  it('opens above the button when there is no room under it, and pulls back from the right edge', async () => {
    setViewport(300, 160)
    wrapper = await mountMenu()

    // 100 - 4 - 48 above the button; and ending at the button's right edge rather than past the window's.
    expect(menuElement().style.top).toBe('48px')
    expect(menuElement().style.left).toBe('40px')
  })

  it('opens at the pointer, and flips to the other side of it near the window\'s edges', async () => {
    wrapper = await mountMenu({ point: { x: 120, y: 300 } })
    expect(menuElement().style.top).toBe('300px')
    expect(menuElement().style.left).toBe('120px')
    wrapper.unmount()

    wrapper = await mountMenu({ point: { x: 950, y: 780 } })
    expect(menuElement().style.top).toBe(`${780 - MENU_SIZE.height}px`)
    expect(menuElement().style.left).toBe(`${950 - MENU_SIZE.width}px`)
  })

  it('stays inside the window even when neither side of the pointer has room', async () => {
    setViewport(200, 60)
    wrapper = await mountMenu({ point: { x: 100, y: 30 } })

    expect(menuElement().style.top).toBe('8px')
    expect(menuElement().style.left).toBe('8px')
  })

  it('focuses the last item when it was opened from the end', async () => {
    wrapper = await mountMenu({ startAt: 'last' })

    expect(document.activeElement).toBe(item())
  })

  it('keeps focus on its items with the arrow keys, Home and End', async () => {
    wrapper = await mountMenu()

    for (const key of ['ArrowDown', 'ArrowUp', 'Home', 'End']) {
      const event = press(key)
      expect(event.defaultPrevented).toBe(true)
      expect(document.activeElement).toBe(item())
    }
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('asks to close on Escape, with focus going back, and keeps the key from reaching anything behind it', async () => {
    const behind = vi.fn()
    document.addEventListener('keydown', behind)
    wrapper = await mountMenu()

    const event = press('Escape')

    expect(event.defaultPrevented).toBe(true)
    expect(behind).not.toHaveBeenCalled()
    expect(wrapper.emitted('close')).toEqual([[true]])
    document.removeEventListener('keydown', behind)
  })

  /** Tab is left to do its own work: focus goes back first, and the Tab moves on from there. */
  it('asks to close on Tab without holding the key back', async () => {
    wrapper = await mountMenu()

    const event = press('Tab')

    expect(event.defaultPrevented).toBe(false)
    expect(wrapper.emitted('close')).toEqual([[true]])
  })

  it('says "Move to Trash Bin" was chosen when its item is pressed', async () => {
    wrapper = await mountMenu()

    item().click()

    expect(wrapper.emitted('trash')).toHaveLength(1)
  })

  it('asks to close on a press outside it, but not on its own button or inside it', async () => {
    wrapper = await mountMenu()

    item().dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    anchor.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    expect(wrapper.emitted('close')).toBeUndefined()

    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    expect(wrapper.emitted('close')).toEqual([[false]])
  })

  it('asks to close when the page scrolls or the window is resized, since it would be left behind', async () => {
    wrapper = await mountMenu()

    document.dispatchEvent(new Event('scroll'))
    window.dispatchEvent(new Event('resize'))

    // Focus was in the menu both times, so it should go back to whoever opened it.
    expect(wrapper.emitted('close')).toEqual([[true], [true]])
  })

  it('keeps the browser\'s own context menu from opening over it', async () => {
    wrapper = await mountMenu()

    const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
    item().dispatchEvent(event)

    expect(event.defaultPrevented).toBe(true)
  })

  it('stops listening once it is gone', () => {
    const onClose = vi.fn()
    const gone = mount(TemplateActionsMenu, {
      props: { id: 'template-actions-menu', labelledBy: 'template-more-1', anchor },
      attrs: { onClose },
      attachTo: document.body,
    })
    gone.unmount()

    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    document.dispatchEvent(new Event('scroll'))
    window.dispatchEvent(new Event('resize'))

    expect(onClose).not.toHaveBeenCalled()
  })

  it('without the Popover API, moves itself to the end of the page so nothing around it can clip it', () => {
    const host = document.createElement('div')
    host.className = 'host'
    document.body.appendChild(host)
    wrapper = mount(TemplateActionsMenu, {
      props: { id: 'template-actions-menu', labelledBy: 'template-more-1', anchor },
      attachTo: host,
    })

    expect(host.querySelector('[role="menu"]')).toBeNull()
    expect(menuElement().parentElement).toBe(document.body)
    expect(menuElement().hasAttribute('popover')).toBe(false)
  })

  describe('with the Popover API', () => {
    const showPopover = vi.fn()

    beforeEach(() => {
      showPopover.mockReset()
      Object.defineProperty(HTMLElement.prototype, 'popover', { value: null, configurable: true, writable: true })
      Object.defineProperty(HTMLElement.prototype, 'showPopover', { value: showPopover, configurable: true, writable: true })
    })

    afterEach(() => {
      Reflect.deleteProperty(HTMLElement.prototype, 'popover')
      Reflect.deleteProperty(HTMLElement.prototype, 'showPopover')
    })

    it('stays where it was put and raises itself into the top layer before placing itself', async () => {
      const host = document.createElement('div')
      document.body.appendChild(host)
      wrapper = mount(TemplateActionsMenu, {
        props: { id: 'template-actions-menu', labelledBy: 'template-more-1', anchor },
        attachTo: host,
      })
      await flushPromises()

      expect(host.querySelector('[role="menu"]')).not.toBeNull()
      expect(showPopover).toHaveBeenCalledTimes(1)
      expect(menuElement().style.top).toBe('136px')
      expect(document.activeElement).toBe(item())
    })
  })

  it('has no automatically-detectable accessibility violations', async () => {
    wrapper = await mountMenu()

    expect(await axe(document.body)).toHaveNoViolations()
  })
})
