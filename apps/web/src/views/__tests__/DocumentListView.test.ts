import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import DocumentListView from '@/views/DocumentListView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, listDocuments: vi.fn() }
})

import { listDocuments } from '@/api/client'

async function mountWithRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: DocumentListView },
      { path: '/documents/new', component: { template: '<div />' } },
      { path: '/documents/:id', component: { template: '<div />' } },
    ],
  })
  router.push('/')
  await router.isReady()
  return mount(DocumentListView, { global: { plugins: [router] } })
}

describe('DocumentListView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listDocuments).mockReset()
  })

  it('prompts sign-in when the session is anonymous', async () => {
    const session = useSessionStore()
    session.status = 'anonymous'

    const wrapper = await mountWithRouter()

    expect(wrapper.text()).toContain('Sign in')
    expect(listDocuments).not.toHaveBeenCalled()
  })

  it('lists documents returned for the personal workspace once authenticated', async () => {
    vi.mocked(listDocuments).mockResolvedValue([
      { id: 1, title: 'March Minutes', templateId: 1, templateVersionId: 1, currentRevisionId: 1, createdAt: '2026-03-01T00:00:00Z' },
    ])
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(listDocuments).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('March Minutes')
  })

  it('shows an empty-state prompt when there are no documents', async () => {
    vi.mocked(listDocuments).mockResolvedValue([])
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.text()).toContain("don't have any documents yet")
  })

  it('has no automatically-detectable accessibility violations with documents listed', async () => {
    vi.mocked(listDocuments).mockResolvedValue([
      { id: 1, title: 'March Minutes', templateId: 1, templateVersionId: 1, currentRevisionId: 1, createdAt: '2026-03-01T00:00:00Z' },
      { id: 2, title: 'April Minutes', templateId: 1, templateVersionId: 1, currentRevisionId: 1, createdAt: '2026-04-01T00:00:00Z' },
    ])
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}
