import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import TrashView from '@/views/TrashView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, listDocuments: vi.fn() }
})

import { listDocuments } from '@/api/client'

const stub = { template: '<div />' }

async function mountView(component: typeof ChatView | typeof TrashView) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: stub },
      { path: '/documents/new', component: stub },
      { path: '/documents/:id', component: stub },
    ],
  })
  router.push('/')
  await router.isReady()
  return mount(component, { global: { plugins: [router] } })
}

function signIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
}

function summary(id: number, title: string, createdAt: string) {
  return { id, title, templateId: 1, templateVersionId: 1, currentRevisionId: 1, createdAt }
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

describe('ChatView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listDocuments).mockReset()
  })

  /**
   * Assist reads and changes one document's own fields, sources and
   * checks, so this page's job is to get you to that document rather than
   * to imply a conversation that exists anywhere else.
   */
  it('explains that Brownie works inside a document, and links to the recent ones', async () => {
    vi.mocked(listDocuments).mockResolvedValue([
      summary(1, 'March Minutes', '2026-03-01T10:00:00Z'),
      summary(2, 'April Minutes', '2026-04-01T10:00:00Z'),
    ])
    signIn()

    const wrapper = await mountView(ChatView)
    await flushPromises()

    expect(wrapper.text()).toContain('one document at a time')
    const rows = wrapper.findAll('.chat__row')
    expect(rows[0]!.attributes('href')).toBe('/documents/2')
    expect(rows[1]!.attributes('href')).toBe('/documents/1')
    expect(wrapper.find('a[href="/documents/new"]').exists()).toBe(true)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('says so plainly when there is nothing to open yet', async () => {
    vi.mocked(listDocuments).mockResolvedValue([])
    signIn()

    const wrapper = await mountView(ChatView)
    await flushPromises()

    expect(wrapper.text()).toContain("don't have any documents yet")
  })
})

describe('TrashView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  /** An empty list would read like a load that failed; the page says why there is nothing here instead. */
  it('says the bin is empty and why nothing arrives in it', async () => {
    const wrapper = await mountView(TrashView)

    expect(wrapper.get('h1').text()).toBe('Your trash bin is empty')
    expect(wrapper.text()).toContain('nothing you can do in the app removes a document')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
