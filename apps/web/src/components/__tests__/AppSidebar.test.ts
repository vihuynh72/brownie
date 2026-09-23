import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, listTemplates: vi.fn(), logout: vi.fn() }
})

import { listTemplates } from '@/api/client'

const stub = { template: '<div />' }

function makeRouter() {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'home', component: stub },
      { path: '/signin', name: 'signin', component: stub },
      { path: '/chat', name: 'chat', component: stub },
      { path: '/trash', name: 'trash', component: stub },
      { path: '/your-data', name: 'your-data', component: stub },
      { path: '/connections', name: 'connections', component: stub },
      { path: '/documents/new', name: 'new-document', component: stub },
      { path: '/templates/new', name: 'new-template', component: stub },
    ],
  })
}

async function mountSidebar(props: { open: boolean; docked: boolean }) {
  const router = makeRouter()
  router.push('/')
  await router.isReady()
  return mount(AppSidebar, { props, global: { plugins: [router] }, attachTo: document.body })
}

function pressKey(key: string, shiftKey = false): void {
  document.dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }))
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function signIn(): void {
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

describe('AppSidebar', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listTemplates).mockReset().mockResolvedValue([])
    document.body.innerHTML = ''
    useSessionStore().status = 'anonymous'
  })

  it('offers sign-in instead of an account while nobody is signed in, and says why the template list is empty', async () => {
    const wrapper = await mountSidebar({ open: true, docked: true })

    expect(wrapper.find('a[href="/signin"]').text()).toContain('Sign In')
    expect(wrapper.findAll('button').find((b) => b.text() === 'Sign out')).toBeUndefined()
    expect(wrapper.text()).toContain('Sign in to see your templates')
    expect(listTemplates).not.toHaveBeenCalled()
  })

  /** On every page load a signed-in person is "loading" first; telling them to sign in then is simply wrong. */
  it('says it is loading while it does not yet know who is signed in, not that they should sign in', async () => {
    useSessionStore().status = 'loading'
    const wrapper = await mountSidebar({ open: true, docked: true })

    expect(wrapper.text()).not.toContain('Sign in to see your templates')
    expect(wrapper.find('.sidebar__note').text()).toBe('Loading…')
  })

  it('lists the workspace templates that can actually start a document', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Club minutes', status: 'ACTIVE', currentActiveVersionId: 4, createdAt: '2026-09-01T10:00:00Z' },
      { id: 2, displayName: 'Half-taught draft', status: 'DRAFT', currentActiveVersionId: null, createdAt: '2026-09-02T10:00:00Z' },
    ])
    signIn()

    const wrapper = await mountSidebar({ open: true, docked: true })
    await flushPromises()

    expect(listTemplates).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('Club minutes')
    expect(wrapper.text()).not.toContain('Half-taught draft')
    expect(wrapper.find('a[href="/documents/new?templateId=1"]').exists()).toBe(true)
    // Where what is kept, for how long, and how to delete all of it can always be found.
    expect(wrapper.find('a[href="/your-data"]').text()).toBe('Your data')
    expect(wrapper.find('a[href="/connections"]').text()).toBe('Connections')
  })

  /** Opening a drawer that nothing has focus in leaves a keyboard user tabbing through the page behind it. */
  it('takes focus when it opens as a drawer', async () => {
    const wrapper = await mountSidebar({ open: false, docked: false })

    await wrapper.setProps({ open: true })
    await flushPromises()

    const close = wrapper.findAll('button').find((b) => b.text() === 'Close menu')
    expect(document.activeElement).toBe(close!.element)
  })

  it('closes a drawer on Escape, and when a link inside it is followed', async () => {
    const wrapper = await mountSidebar({ open: true, docked: false })

    pressKey('Escape')
    expect(wrapper.emitted('close')).toHaveLength(1)

    await wrapper.find('a[href="/trash"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(2)
  })

  /** Docked it is ordinary page furniture: Escape belongs to whatever else is open, and a link goes nowhere special. */
  it('does not answer Escape, or close itself on a link, while it is docked', async () => {
    const wrapper = await mountSidebar({ open: true, docked: true })

    pressKey('Escape')
    await wrapper.find('a[href="/trash"]').trigger('click')

    expect(wrapper.emitted('close')).toBeUndefined()
  })

  /** Tab must not walk out of a drawer onto the page it is covering. */
  it('wraps Tab at its own ends while it is an open drawer', async () => {
    await mountSidebar({ open: true, docked: false })
    const root = document.getElementById('app-sidebar')!
    const stops = Array.from(root.querySelectorAll<HTMLElement>('a[href], button, [tabindex]')).filter(
      (element) => !element.hasAttribute('disabled') && element.tabIndex >= 0,
    )
    expect(stops.length).toBeGreaterThan(2)
    const first = stops[0]!
    const last = stops[stops.length - 1]!

    last.focus()
    pressKey('Tab')
    expect(document.activeElement).toBe(first)

    pressKey('Tab', true)
    expect(document.activeElement).toBe(last)
  })

  it('has no automatically-detectable accessibility violations signed in', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Club minutes', status: 'ACTIVE', currentActiveVersionId: 4, createdAt: '2026-09-01T10:00:00Z' },
    ])
    signIn()

    const wrapper = await mountSidebar({ open: true, docked: true })
    await flushPromises()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
