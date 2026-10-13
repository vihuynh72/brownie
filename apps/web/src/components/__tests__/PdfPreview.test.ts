import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PdfPreview from '@/components/PdfPreview.vue'
import { axe } from '@/test/axe'

// PDF.js needs a real canvas and a worker; neither exists in jsdom, so the library is replaced with
// a fake that answers the same calls the component makes, and the worker asset import with a path.
// A page's size is 600 by 800 points unless a test says otherwise.
const { render, getPage, destroy, getDocument, pdfFile } = vi.hoisted(() => {
  const pdfFile = { numPages: 3, sizes: {} as Record<number, { width: number; height: number }> }
  const render = vi.fn(() => ({ promise: Promise.resolve(), cancel: vi.fn() }))
  const getPage = vi.fn(async (number: number) => {
    const { width, height } = pdfFile.sizes[number] ?? { width: 600, height: 800 }
    return { getViewport: ({ scale }: { scale: number }) => ({ width: width * scale, height: height * scale }), render }
  })
  const destroy = vi.fn(async () => {})
  const getDocument = vi.fn(() => ({ promise: Promise.resolve({ numPages: pdfFile.numPages, getPage }), destroy }))
  return { render, getPage, destroy, getDocument, pdfFile }
})

vi.mock('pdfjs-dist/legacy/build/pdf.mjs', () => ({ GlobalWorkerOptions: { workerSrc: '' }, getDocument }))
vi.mock('pdfjs-dist/legacy/build/pdf.worker.min.mjs?url', () => ({ default: '/fake-worker.mjs' }))

const SRC = '/api/v1/workspaces/7/uploads/9/preview'

/** How far the document has been scrolled in a test, in pixels: what has gone off the top of the view. */
interface View {
  scrolled: number
}

describe('PdfPreview', () => {
  const fetchSpy = vi.fn()
  const mounted: VueWrapper[] = []
  let getContext: ReturnType<typeof vi.spyOn>
  let scrollIntoView: ReturnType<typeof vi.fn>
  let scrollWindow: ReturnType<typeof vi.fn>
  const innerHeight = Object.getOwnPropertyDescriptor(window, 'innerHeight')

  /** Mounts into the document, so focus works, and unmounts after the test so no listener outlives it. */
  function open(props: { src: string | null; label?: string } = { src: SRC }, host: HTMLElement = document.body): VueWrapper {
    const wrapper = mount(PdfPreview, { props: { label: 'Preview of version 2', ...props }, attachTo: host })
    mounted.push(wrapper)
    return wrapper
  }

  /**
   * jsdom lays nothing out, so the sheets are put in a column by hand: `height` pixels tall, `gap` apart, the first `top`
   * pixels down, in a window 800 pixels tall. Whatever `scrolled` says has gone off the top is taken off every sheet's
   * position. An element marked data-screen is a box the sheets scroll in, and is given the `area` the test names.
   */
  function layOut({ height = 800, gap = 16, top = 50, width = 600, area = { top: 100, bottom: 900 } } = {}): View {
    const view: View = { scrolled: 0 }
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
      const sheet = this.classList.contains('pdf-preview__sheet') && this.parentElement
      const box = this.hasAttribute('data-screen')
        ? { y: area.top, width: 700, height: area.bottom - area.top }
        : sheet
          ? { y: top + Array.from(sheet.children).indexOf(this) * (height + gap) - view.scrolled, width, height }
          : { y: 0, width: 0, height: 0 }
      return { x: 91, y: box.y, left: 91, top: box.y, right: 91 + box.width, bottom: box.y + box.height, width: box.width, height: box.height, toJSON: () => ({}) }
    })
    vi.spyOn(Element.prototype, 'clientWidth', 'get').mockReturnValue(width)
    Object.defineProperty(window, 'innerHeight', { configurable: true, writable: true, value: 800 })
    return view
  }

  /** Scrolls the stand-in view and waits for the component to notice, which it does on the next frame. */
  async function scrollTo(view: View, scrolled: number, target: EventTarget = window): Promise<void> {
    view.scrolled = scrolled
    target.dispatchEvent(new Event('scroll'))
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    await flushPromises()
  }

  const sheets = (wrapper: VueWrapper) => wrapper.findAll('.pdf-preview__sheet')
  const drawnWidths = (wrapper: VueWrapper) => wrapper.findAll('canvas').map((canvas) => (canvas.element as HTMLCanvasElement).width)
  const buttons = (wrapper: VueWrapper) => wrapper.findAll('.pdf-preview__pager button')
  const previous = (wrapper: VueWrapper) => buttons(wrapper)[0]

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchSpy)
    fetchSpy.mockResolvedValue({ ok: true, status: 200, arrayBuffer: async () => new ArrayBuffer(8) })
    getContext = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({} as unknown as CanvasRenderingContext2D)
    scrollIntoView = vi.fn()
    scrollWindow = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView as unknown as typeof Element.prototype.scrollIntoView
    window.scrollTo = scrollWindow as unknown as typeof window.scrollTo
    pdfFile.numPages = 3
    pdfFile.sizes = {}
    render.mockClear()
    getPage.mockClear()
    getDocument.mockClear()
    destroy.mockClear()
  })

  afterEach(() => {
    for (const wrapper of mounted.splice(0)) wrapper.unmount()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    Reflect.deleteProperty(Element.prototype, 'scrollIntoView')
    if (innerHeight) Object.defineProperty(window, 'innerHeight', innerHeight)
    getContext.mockRestore()
  })

  it('fetches the PDF with the session and draws every page on its own labelled sheet', async () => {
    layOut({ height: 300 })
    const wrapper = open()
    await flushPromises()

    expect(fetchSpy).toHaveBeenCalledWith(SRC, { credentials: 'same-origin' })
    expect(getDocument).toHaveBeenCalledTimes(1)
    expect(render).toHaveBeenCalledTimes(3)
    expect(wrapper.get('[role="group"]').attributes('aria-label')).toBe('Preview of version 2')
    const canvases = wrapper.findAll('canvas')
    expect(canvases.map((canvas) => canvas.attributes('aria-label'))).toEqual([
      'Preview of version 2, page 1 of 3',
      'Preview of version 2, page 2 of 3',
      'Preview of version 2, page 3 of 3',
    ])
    expect(canvases.every((canvas) => canvas.attributes('role') === 'img')).toBe(true)

    expect((await axe(wrapper.element)).violations).toEqual([])
  })

  it('draws each page to fit its sheet at the screen resolution', async () => {
    layOut({ width: 300, height: 400 })
    vi.stubGlobal('devicePixelRatio', 2)
    const wrapper = open({ src: SRC })
    await flushPromises()

    // A 600 by 800 point page on a 300 pixel sheet is drawn at half size, twice over for a sharp screen.
    const canvas = wrapper.get('canvas').element as HTMLCanvasElement
    expect([canvas.width, canvas.height]).toEqual([600, 800])
    expect(render).toHaveBeenCalledWith(expect.objectContaining({ transform: [2, 0, 0, 2, 0, 0], viewport: expect.objectContaining({ width: 300 }) }))
  })

  it('names the page and offers the arrows, with the one that goes nowhere unavailable', async () => {
    layOut({ height: 300 })
    const wrapper = open()
    await flushPromises()

    expect(wrapper.get('nav').attributes('aria-label')).toBe('Preview pages')
    expect(wrapper.get('[aria-live="polite"]').text()).toBe('Page 1 of 3')
    expect(buttons(wrapper).map((button) => button.text())).toEqual(['Previous page', 'Next page'])
    expect(wrapper.find('button[disabled]').text()).toBe('Previous page')
    expect(buttons(wrapper)[1].attributes('disabled')).toBeUndefined()
  })

  it('shows the pager for a single page too, with both arrows unavailable', async () => {
    pdfFile.numPages = 1
    layOut()
    const wrapper = open()
    await flushPromises()

    expect(wrapper.text()).toContain('Page 1 of 1')
    expect(buttons(wrapper).every((button) => button.attributes('disabled') !== undefined)).toBe(true)
  })

  it('scrolls the next page into view when the arrow is pressed, and names it once it is the one on screen', async () => {
    const view = layOut()
    pdfFile.numPages = 4
    const wrapper = open()
    await flushPromises()

    await buttons(wrapper)[1].trigger('click')
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
    expect(scrollIntoView.mock.contexts[0]).toBe(sheets(wrapper)[1].element)
    // The page was not in view, so the label waits for the scroll rather than guessing.
    expect(wrapper.text()).toContain('Page 1 of 4')

    await scrollTo(view, 816 - 12)
    expect(wrapper.text()).toContain('Page 2 of 4')
    expect(buttons(wrapper).every((button) => button.attributes('disabled') === undefined)).toBe(true)
  })

  it('takes the first page back to the very top, so the line above it can be read too', async () => {
    const view = layOut()
    const wrapper = open()
    await flushPromises()
    await scrollTo(view, 816)
    expect(wrapper.text()).toContain('Page 2 of 3')

    await buttons(wrapper)[0].trigger('click')
    expect(scrollWindow).toHaveBeenCalledWith({ top: 0, behavior: 'smooth' })
    expect(scrollIntoView).not.toHaveBeenCalled()
  })

  it('jumps instead of gliding when the person prefers reduced motion', async () => {
    layOut()
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query.includes('reduce'), media: query }))
    const wrapper = open()
    await flushPromises()

    await buttons(wrapper)[1].trigger('click')
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'auto', block: 'start' })
  })

  it('names the next page at once when it is already wholly on screen and so nothing will scroll', async () => {
    layOut({ height: 200 })
    const wrapper = open()
    await flushPromises()
    expect(wrapper.text()).toContain('Page 1 of 3')

    await buttons(wrapper)[1].trigger('click')
    expect(wrapper.text()).toContain('Page 2 of 3')
    await buttons(wrapper)[1].trigger('click')
    expect(wrapper.text()).toContain('Page 3 of 3')
    expect(buttons(wrapper)[1].attributes('disabled')).toBeDefined()
  })

  it('hands the focus to the other arrow when the one pressed has nowhere left to go', async () => {
    layOut({ height: 200 })
    const wrapper = open()
    await flushPromises()

    const next = buttons(wrapper)[1]
    ;(next.element as HTMLButtonElement).focus()
    await next.trigger('click')
    expect(document.activeElement).toBe(next.element)
    await next.trigger('click')
    await flushPromises()
    expect(next.attributes('disabled')).toBeDefined()
    expect(document.activeElement).toBe(previous(wrapper).element)

    // And back the other way: pressing Previous down to the first page leaves the focus on Next.
    await previous(wrapper).trigger('click')
    await previous(wrapper).trigger('click')
    await flushPromises()
    expect(previous(wrapper).attributes('disabled')).toBeDefined()
    expect(document.activeElement).toBe(next.element)
  })

  it('names the page with the most of itself on screen as the person scrolls', async () => {
    const view = layOut()
    pdfFile.numPages = 6
    const wrapper = open()
    await flushPromises()
    expect(wrapper.text()).toContain('Page 1 of 6')

    // The first sheet's foot is 400 pixels up the screen and the second fills the rest: the second has the most.
    await scrollTo(view, 1000)
    expect(wrapper.text()).toContain('Page 2 of 6')
    await scrollTo(view, 2600)
    expect(wrapper.text()).toContain('Page 4 of 6')
    await scrollTo(view, 0)
    expect(wrapper.text()).toContain('Page 1 of 6')
  })

  it('keeps the page it named while that page is still wholly on screen, as several fit at once', async () => {
    const view = layOut({ height: 200 })
    const wrapper = open()
    await flushPromises()
    await buttons(wrapper)[1].trigger('click')
    expect(wrapper.text()).toContain('Page 2 of 3')

    // Nothing here scrolls the first sheet out of the way, so the second still counts as the page.
    await scrollTo(view, 10)
    expect(wrapper.text()).toContain('Page 2 of 3')
  })

  it('listens to the nearest element that scrolls its own contents, not the window', async () => {
    const pane = document.createElement('div')
    pane.style.overflowY = 'auto'
    pane.setAttribute('data-screen', '')
    document.body.append(pane)
    const view = layOut()
    const wrapper = open({ src: SRC }, pane)
    await flushPromises()
    expect(wrapper.text()).toContain('Page 1 of 3')

    // The window moving does nothing to sheets that scroll inside the pane; the pane moving does.
    view.scrolled = 800
    window.dispatchEvent(new Event('scroll'))
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    expect(wrapper.text()).toContain('Page 1 of 3')
    await scrollTo(view, 800, pane)
    expect(wrapper.text()).toContain('Page 2 of 3')
    pane.remove()
  })

  it('paints only the pages near the screen, and gives back the memory of those that scroll far away', async () => {
    const view = layOut()
    pdfFile.numPages = 6
    const wrapper = open()
    await flushPromises()

    // The screen is 800 pixels tall: the first two sheets are on it or within a screen of it, the third is past that.
    expect(render).toHaveBeenCalledTimes(2)
    const first = wrapper.get('canvas').element as HTMLCanvasElement
    expect(first.width).toBe(600)

    await scrollTo(view, 2600)
    expect((wrapper.findAll('canvas')[0].element as HTMLCanvasElement).width).toBe(0)
    expect(drawnWidths(wrapper).filter((width) => width === 600).length).toBeGreaterThanOrEqual(3)
    // Coming back, the page is painted again.
    const before = render.mock.calls.length
    await scrollTo(view, 0)
    expect(render.mock.calls.length).toBeGreaterThan(before)
    expect((wrapper.findAll('canvas')[0].element as HTMLCanvasElement).width).toBe(600)
  })

  it('draws nothing while its view has no size, as when it is hidden, and draws once it has one', async () => {
    const wrapper = open()
    await flushPromises()
    expect(render).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Page 1 of 3')

    layOut({ height: 300 })
    window.dispatchEvent(new Event('resize'))
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    await flushPromises()
    expect(render).toHaveBeenCalledTimes(3)
  })

  it('draws the pages again when their sheets change width', async () => {
    layOut({ width: 600, height: 300 })
    const wrapper = open()
    await flushPromises()
    expect(render).toHaveBeenCalledTimes(3)

    vi.spyOn(Element.prototype, 'clientWidth', 'get').mockReturnValue(400)
    window.dispatchEvent(new Event('resize'))
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    await flushPromises()
    expect(render).toHaveBeenCalledTimes(6)
    expect(render).toHaveBeenLastCalledWith(expect.objectContaining({ viewport: expect.objectContaining({ width: 400 }) }))
    expect(wrapper.findAll('canvas')).toHaveLength(3)
  })

  it('draws the pages again when the screen gets sharper, as when the person zooms in', async () => {
    layOut({ height: 300 })
    open()
    await flushPromises()
    expect(render).toHaveBeenCalledTimes(3)
    expect(render).toHaveBeenLastCalledWith(expect.objectContaining({ transform: undefined }))

    // The sheets keep their width; only the number of screen pixels under each of them changes.
    vi.stubGlobal('devicePixelRatio', 1.5)
    window.dispatchEvent(new Event('resize'))
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    await flushPromises()
    expect(render).toHaveBeenCalledTimes(6)
    expect(render).toHaveBeenLastCalledWith(expect.objectContaining({ transform: [1.5, 0, 0, 1.5, 0, 0] }))
  })

  it('gives each sheet the shape of its page', async () => {
    layOut({ height: 300 })
    pdfFile.sizes = { 2: { width: 800, height: 600 } }
    const wrapper = open()
    await flushPromises()

    // Every sheet starts out shaped like the first page; the sideways one takes its own shape once it is drawn.
    expect(sheets(wrapper)[0].attributes('style')).toContain('aspect-ratio: 1 / 1.333')
    expect(sheets(wrapper)[2].attributes('style')).toContain('aspect-ratio: 1 / 1.333')
    expect(sheets(wrapper)[1].attributes('style')).toContain('aspect-ratio: 1 / 0.75')
  })

  it('numbers each sheet in the margin without announcing the number twice', async () => {
    layOut({ height: 300 })
    const wrapper = open()
    await flushPromises()

    const numbers = wrapper.findAll('.pdf-preview__number')
    expect(numbers.map((number) => number.text())).toEqual(['1', '2', '3'])
    expect(numbers.every((number) => number.attributes('aria-hidden') === 'true')).toBe(true)
  })

  it('says it is loading, on a sheet where the page will be, until the file has come in', async () => {
    fetchSpy.mockReturnValue(new Promise(() => {}))
    const wrapper = open()
    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toBe('Loading preview…')
    expect(wrapper.find('.pdf-preview__sheet [role="status"]').exists()).toBe(true)
    expect(wrapper.find('nav').exists()).toBe(false)
    expect((await axe(wrapper.element)).violations).toEqual([])
  })

  it('reports a refused fetch as a readable error instead of a blank frame', async () => {
    fetchSpy.mockResolvedValueOnce({ ok: false, status: 404, arrayBuffer: async () => new ArrayBuffer(0) })
    const wrapper = open({ src: '/api/v1/workspaces/7/uploads/404/preview', label: 'Preview' })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('could not be fetched (404)')
    expect(getDocument).not.toHaveBeenCalled()
    expect(wrapper.find('nav').exists()).toBe(false)
    expect((await axe(wrapper.element)).violations).toEqual([])
  })

  it('passes on what the server said when it refused, with the id to quote', async () => {
    fetchSpy.mockResolvedValueOnce({
      ok: false,
      status: 404,
      json: async () => ({ detail: 'Artifact 12 was not found in this workspace.', correlationId: 'c0ffee-1234' }),
    })
    const wrapper = open()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('Artifact 12 was not found in this workspace. Correlation ID: c0ffee-1234.')
  })

  it('names the page that could not be drawn, and puts nothing half-drawn in its place', async () => {
    layOut({ height: 300 })
    render.mockImplementationOnce(() => ({ promise: Promise.resolve(), cancel: vi.fn() }))
    render.mockImplementationOnce(() => ({ promise: Promise.reject(new Error('bad operator list')), cancel: vi.fn() }))
    const wrapper = open()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('Page 2 could not be drawn.')
    expect(wrapper.find('canvas').exists()).toBe(false)
    expect(wrapper.find('nav').exists()).toBe(false)
  })

  it('says so when the browser has no way to draw, rather than leaving the sheets blank', async () => {
    layOut({ height: 300 })
    getContext.mockReturnValue(null)
    const wrapper = open()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('cannot draw the preview')
  })

  it('shows nothing for a null source and frees the document when the source goes away', async () => {
    layOut({ height: 300 })
    const wrapper = open({ src: SRC, label: 'Preview' })
    await flushPromises()
    expect(wrapper.text()).toContain('Page 1 of 3')

    await wrapper.setProps({ src: null })
    await flushPromises()
    expect(destroy).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('Page 1 of 3')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('canvas').exists()).toBe(false)
  })

  it('starts again from the first page when it is given another file', async () => {
    const view = layOut()
    const wrapper = open()
    await flushPromises()
    await scrollTo(view, 1000)
    expect(wrapper.text()).toContain('Page 2 of 3')

    view.scrolled = 0
    pdfFile.numPages = 5
    await wrapper.setProps({ src: '/api/v1/workspaces/7/uploads/10/preview' })
    await flushPromises()
    expect(destroy).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('Page 1 of 5')
  })

  it('stops listening to the screen when it goes away', async () => {
    const view = layOut()
    const wrapper = open()
    await flushPromises()
    const drawnBefore = render.mock.calls.length

    wrapper.unmount()
    mounted.splice(mounted.indexOf(wrapper), 1)
    await scrollTo(view, 2600)
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(render.mock.calls.length).toBe(drawnBefore)
    expect(destroy).toHaveBeenCalledTimes(1)
  })
})
