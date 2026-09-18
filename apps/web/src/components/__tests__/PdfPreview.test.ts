import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PdfPreview from '@/components/PdfPreview.vue'
import { axe } from '@/test/axe'

// PDF.js needs a real canvas and a worker; neither exists in jsdom, so the library is replaced with
// a fake that answers the same calls the component makes, and the worker asset import with a path.
const { render, getPage, destroy, getDocument } = vi.hoisted(() => {
  const render = vi.fn(() => ({ promise: Promise.resolve(), cancel: vi.fn() }))
  const getPage = vi.fn(async () => ({
    getViewport: ({ scale }: { scale: number }) => ({ width: 600 * scale, height: 800 * scale }),
    render,
  }))
  const destroy = vi.fn(async () => {})
  const getDocument = vi.fn(() => ({ promise: Promise.resolve({ numPages: 3, getPage }), destroy }))
  return { render, getPage, destroy, getDocument }
})

vi.mock('pdfjs-dist/legacy/build/pdf.mjs', () => ({ GlobalWorkerOptions: { workerSrc: '' }, getDocument }))
vi.mock('pdfjs-dist/legacy/build/pdf.worker.min.mjs?url', () => ({ default: '/fake-worker.mjs' }))

describe('PdfPreview', () => {
  const fetchSpy = vi.fn()
  let getContext: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchSpy)
    fetchSpy.mockResolvedValue({ ok: true, status: 200, arrayBuffer: async () => new ArrayBuffer(8) })
    getContext = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({} as unknown as CanvasRenderingContext2D)
    render.mockClear()
    getPage.mockClear()
    getDocument.mockClear()
    destroy.mockClear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    getContext.mockRestore()
  })

  it('fetches the PDF with the session, draws page one, and pages through it', async () => {
    const wrapper = mount(PdfPreview, { props: { src: '/api/v1/workspaces/7/uploads/9/preview', label: 'Preview of version 2' } })
    await flushPromises()

    expect(fetchSpy).toHaveBeenCalledWith('/api/v1/workspaces/7/uploads/9/preview', { credentials: 'same-origin' })
    expect(getDocument).toHaveBeenCalledTimes(1)
    expect(getPage).toHaveBeenLastCalledWith(1)
    expect(wrapper.text()).toContain('Page 1 of 3')
    expect(wrapper.find('canvas').attributes('aria-label')).toBe('Preview of version 2, page 1 of 3')
    expect(wrapper.find('button[disabled]').text()).toBe('Previous page')

    await wrapper.get('button:not([disabled])').trigger('click')
    await flushPromises()
    expect(getPage).toHaveBeenLastCalledWith(2)
    expect(wrapper.text()).toContain('Page 2 of 3')
    expect(render).toHaveBeenCalledTimes(2)

    expect((await axe(wrapper.element)).violations).toEqual([])
  })

  it('reports a refused fetch as a readable error instead of a blank frame', async () => {
    fetchSpy.mockResolvedValueOnce({ ok: false, status: 404, arrayBuffer: async () => new ArrayBuffer(0) })
    const wrapper = mount(PdfPreview, { props: { src: '/api/v1/workspaces/7/uploads/404/preview', label: 'Preview' } })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('could not be fetched (404)')
    expect(getDocument).not.toHaveBeenCalled()
  })

  it('shows nothing for a null source and frees the document when the source goes away', async () => {
    const wrapper = mount(PdfPreview, { props: { src: '/api/v1/workspaces/7/uploads/9/preview', label: 'Preview' } })
    await flushPromises()
    expect(wrapper.text()).toContain('Page 1 of 3')

    await wrapper.setProps({ src: null })
    await flushPromises()
    expect(destroy).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('Page 1 of 3')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
})
