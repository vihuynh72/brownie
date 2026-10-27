import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import FormUploadView from '../FormUploadView.vue'

function router() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/upload/:token', component: FormUploadView }],
  })
}

function ok(body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }))
}

describe('FormUploadView', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('names the client that asked for the form', async () => {
    vi.stubGlobal('fetch', vi.fn(() => ok({ state: 'PENDING', client_name: 'Claude', expires_at: 'x' })))
    const r = router()
    await r.push('/upload/brownie_up_test')
    const wrapper = mount(FormUploadView, { global: { plugins: [r] } })
    await flushPromises()
    expect(wrapper.text()).toContain('Claude asked you to share a form with Brownie')
    expect(wrapper.text()).toContain('.docx')
    expect(fetch).toHaveBeenCalledWith('/api/v1/mcp-uploads/brownie_up_test')
  })

  it('a link the server does not know says so plainly', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('', { status: 404 }))))
    const r = router()
    await r.push('/upload/brownie_up_gone')
    const wrapper = mount(FormUploadView, { global: { plugins: [r] } })
    await flushPromises()
    expect(wrapper.text()).toContain('no longer available')
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  })

  it('a sent file that lands shows where to go back', async () => {
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => ok({ state: 'PENDING', client_name: 'ChatGPT', expires_at: 'x' }))
      .mockImplementationOnce(() => ok({ state: 'READY', template_id: 7, name: 'Form', spot_count: 5 }))
    vi.stubGlobal('fetch', fetchMock)
    const r = router()
    await r.push('/upload/brownie_up_t')
    const wrapper = mount(FormUploadView, { global: { plugins: [r] } })
    await flushPromises()
    const file = new File(['x'], 'volunteer.docx')
    await wrapper.find('input[type="file"]').trigger('change')
    // Drive the change handler directly: happy-dom does not populate files on trigger.
    ;(wrapper.vm as unknown as { send: (f: File) => void })
    const input = wrapper.find('input[type="file"]').element as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [file] })
    await wrapper.find('input[type="file"]').trigger('change')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('You can go back to ChatGPT')
    expect(wrapper.text()).toContain('5 places')
  })

  it('a failed intake shows the reason and the way out', async () => {
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => ok({ state: 'PENDING', client_name: 'Claude', expires_at: 'x' }))
      .mockImplementationOnce(() => ok({ state: 'FAILED', reason: 'Only Word documents and PDF forms.' }))
    vi.stubGlobal('fetch', fetchMock)
    const r = router()
    await r.push('/upload/brownie_up_t2')
    const wrapper = mount(FormUploadView, { global: { plugins: [r] } })
    await flushPromises()
    const input = wrapper.find('input[type="file"]').element as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [new File(['x'], 'bad.exe')] })
    await wrapper.find('input[type="file"]').trigger('change')
    await flushPromises()
    expect(wrapper.text()).toContain('Only Word documents and PDF forms.')
    expect(wrapper.text()).toContain('Ask Claude for a new link')
  })
})
