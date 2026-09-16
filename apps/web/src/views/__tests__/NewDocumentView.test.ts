import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import NewDocumentView from '@/views/NewDocumentView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, listTemplates: vi.fn(), createDocument: vi.fn() }
})

import { listTemplates, createDocument } from '@/api/client'

async function mountWithRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/documents/new', component: NewDocumentView },
      { path: '/documents/:id', component: { template: '<div />' } },
    ],
  })
  router.push('/documents/new')
  await router.isReady()
  return mount(NewDocumentView, { global: { plugins: [router] } })
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

describe('NewDocumentView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listTemplates).mockReset()
    vi.mocked(createDocument).mockReset()
  })

  it('prompts sign-in when the session is anonymous', async () => {
    const session = useSessionStore()
    session.status = 'anonymous'

    const wrapper = await mountWithRouter()

    expect(wrapper.text()).toContain('Sign in')
    expect(listTemplates).not.toHaveBeenCalled()
  })

  it('loads templates once already authenticated at mount', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE', currentActiveVersionId: 1, createdAt: '2026-03-01T00:00:00Z' },
    ])
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(listTemplates).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('Flowing meeting minutes')
  })

  /**
   * A real bug, caught only by a real browser hitting a real cold-started backend: on a hard page
   * load, App.vue's own onMounted and the router's beforeEach guard both race to call
   * session.loadIdentity() -- when the guard loses that race (status already flipped away from
   * 'unknown' by the time it checks), it skips its own await entirely and the route component mounts
   * while identity is still in flight. Every sibling view already carries a watch(session.status, ...)
   * fallback for exactly this reason; this one didn't, so it hung on "Loading templates..." forever
   * with no retry once identity actually resolved a moment later.
   */
  it('retries loading templates once the session resolves to authenticated after mount', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE', currentActiveVersionId: 1, createdAt: '2026-03-01T00:00:00Z' },
    ])
    const session = useSessionStore()
    session.status = 'unknown'

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(listTemplates).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('Flowing meeting minutes')

    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    session.status = 'authenticated'
    await flushPromises()

    expect(listTemplates).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('Flowing meeting minutes')
  })

  it('creates a document for the selected template and title, then navigates to it', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE', currentActiveVersionId: 3, createdAt: '2026-03-01T00:00:00Z' },
    ])
    vi.mocked(createDocument).mockResolvedValue({
      id: 42,
      title: 'March Sync',
      templateId: 1,
      templateVersionId: 3,
      currentRevisionId: 1,
      createdAt: '2026-03-01T00:00:00Z',
      currentRevision: { id: 1, revisionNumber: 1, parentRevisionId: null, fields: {}, contentHash: 'a'.repeat(64), editReason: 'x', createdAt: '2026-03-01T00:00:00Z' },
    })
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()

    await wrapper.find('#title').setValue('March Sync')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createDocument).toHaveBeenCalledWith(
      7,
      expect.any(String),
      expect.objectContaining({ title: 'March Sync', templateId: 1, templateVersionId: 3 }),
    )
    expect(wrapper.vm.$router.currentRoute.value.fullPath).toBe('/documents/42')
  })

  it('has no automatically-detectable accessibility violations with templates loaded', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE', currentActiveVersionId: 1, createdAt: '2026-03-01T00:00:00Z' },
    ])
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
