import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, listDocuments: vi.fn() }
})

import { listDocuments } from '@/api/client'

async function mountWithRouter(path = '/') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: HomeView },
      { path: '/signin', name: 'signin', component: { template: '<div />' } },
      { path: '/documents/new', component: { template: '<div />' } },
      { path: '/documents/:id', component: { template: '<div />' } },
    ],
  })
  router.push(path)
  await router.isReady()
  return mount(HomeView, { global: { plugins: [router] } })
}

function signedIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = {
    userId: 1,
    issuer: 'x',
    subject: 'y',
    displayName: 'Vi Huynh',
    memberships: [{ workspaceId: 7, role: 'OWNER' }],
  }
}

function summary(id: number, title: string, createdAt: string) {
  return { id, title, templateId: 1, templateVersionId: 1, currentRevisionId: 1, createdAt }
}

describe('HomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listDocuments).mockReset()
  })

  /** A visitor who is not signed in gets the same front door, not a wall: the upload action is still there to follow. */
  it('welcomes a signed-out visitor and still offers the upload action', async () => {
    const session = useSessionStore()
    session.status = 'anonymous'

    const wrapper = await mountWithRouter()

    expect(wrapper.text()).toContain('Welcome to Brownie!')
    expect(wrapper.find('a[href="/documents/new"]').text()).toContain('Upload your documents')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(listDocuments).not.toHaveBeenCalled()
  })

  /** The API sends a refused sign-in back here with only the provider's error code; the page must say so rather than look like a fresh visit. */
  it('explains a failed sign-in, with its reason, and offers to try again', async () => {
    const session = useSessionStore()
    session.status = 'anonymous'

    const wrapper = await mountWithRouter('/?signin=failed&reason=access_denied')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Sign-in did not complete')
    expect(alert.text()).toContain('access_denied')
    expect(alert.find('a[href="/signin"]').text()).toBe('Try again')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('greets a signed-in person by their first name only', async () => {
    vi.mocked(listDocuments).mockResolvedValue([])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe("What's on your mind today, Vi?")
  })

  it('groups recent documents by the day they were made, newest first, with the time beside each', async () => {
    const today = new Date()
    const earlier = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000)
    vi.mocked(listDocuments).mockResolvedValue([
      summary(1, 'Older Minutes', earlier.toISOString()),
      summary(2, 'March Minutes', today.toISOString()),
    ])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(listDocuments).toHaveBeenCalledWith(7)
    const headings = wrapper.findAll('h3').map((h) => h.text())
    expect(headings[0]).toBe('Today')
    expect(headings).toHaveLength(2)

    const rows = wrapper.findAll('.home__row')
    expect(rows[0]!.text()).toContain('March Minutes')
    expect(rows[0]!.attributes('href')).toBe('/documents/2')
    expect(rows[1]!.text()).toContain('Older Minutes')
    expect(rows[0]!.text()).toContain(new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(today))
  })

  it('shows an empty-state prompt when there are no documents', async () => {
    vi.mocked(listDocuments).mockResolvedValue([])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.text()).toContain("don't have any documents yet")
  })

  it('has no automatically-detectable accessibility violations with documents listed', async () => {
    vi.mocked(listDocuments).mockResolvedValue([
      summary(1, 'March Minutes', '2026-03-01T10:00:00Z'),
      summary(2, 'April Minutes', '2026-04-01T10:00:00Z'),
    ])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}
