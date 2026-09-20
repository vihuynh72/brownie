import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import App from '@/App.vue'
import { useSessionStore } from '@/stores/session'
import { ApiRequestError } from '@/api/client'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, getCurrentIdentity: vi.fn(), logout: vi.fn(), listTemplates: vi.fn() }
})
vi.mock('@/navigation', () => ({ navigateTo: vi.fn() }))

import { getCurrentIdentity, listTemplates, logout } from '@/api/client'
import { navigateTo } from '@/navigation'

const stub = { template: '<div data-test="stub" />' }

/** The addresses the sidebar links to, so RouterLink resolves them the way it does in the running app. */
function makeRouter(): Router {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div data-test="home">home</div>' } },
      { path: '/signin', name: 'signin', component: stub },
      { path: '/chat', name: 'chat', component: stub },
      { path: '/trash', name: 'trash', component: { template: '<div data-test="trash">trash</div>' } },
      { path: '/your-data', name: 'your-data', component: stub },
      { path: '/documents/new', name: 'new-document', component: stub },
      { path: '/templates/new', name: 'new-template', component: stub },
    ],
  })
}

async function mountApp(path = '/') {
  const router = makeRouter()
  router.push(path)
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, router }
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

const IDENTITY = {
  userId: 1,
  issuer: 'https://issuer',
  subject: 'subject',
  displayName: 'Vi',
  memberships: [{ workspaceId: 7, role: 'OWNER' }],
}

describe('App shell', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getCurrentIdentity).mockReset()
    vi.mocked(logout).mockReset()
    vi.mocked(listTemplates).mockReset().mockResolvedValue([])
    vi.mocked(navigateTo).mockReset()
    window.localStorage.clear()
    window.sessionStorage.clear()
    document.body.innerHTML = ''
  })

  it('shows a recoverable alert with a working retry when identity cannot be loaded, instead of the route', async () => {
    vi.mocked(getCurrentIdentity).mockRejectedValueOnce(new ApiRequestError(503, undefined))
    const { wrapper } = await mountApp()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toMatch(/try again/i)
    expect(wrapper.find('[data-test="home"]').exists()).toBe(false)

    vi.mocked(getCurrentIdentity).mockResolvedValueOnce(IDENTITY)
    await alert.find('button').trigger('click')
    await flushPromises()

    expect(useSessionStore().status).toBe('authenticated')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="home"]').exists()).toBe(true)
  })

  it('signs out with a real POST, then navigates to the identity provider URL the server returned', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue(IDENTITY)
    vi.mocked(logout).mockResolvedValue({ redirectUrl: 'https://idp.example/logout?x=1' })
    const { wrapper } = await mountApp()

    const signOut = wrapper.findAll('button').find((b) => b.text() === 'Sign out')
    expect(signOut).toBeDefined()
    expect(wrapper.find('a[href="/logout"]').exists()).toBe(false)

    await signOut!.trigger('click')
    await flushPromises()

    expect(logout).toHaveBeenCalledTimes(1)
    expect(navigateTo).toHaveBeenCalledWith('https://idp.example/logout?x=1')
    expect(useSessionStore().status).toBe('anonymous')
  })

  it('reports a failed sign-out and stays signed in rather than pretending it worked', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue(IDENTITY)
    vi.mocked(logout).mockRejectedValue(new ApiRequestError(403, undefined))
    const { wrapper } = await mountApp()

    const signOut = wrapper.findAll('button').find((b) => b.text() === 'Sign out')
    await signOut!.trigger('click')
    await flushPromises()

    expect(navigateTo).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toMatch(/could not sign out/i)
    expect(useSessionStore().status).toBe('authenticated')
    expect(wrapper.findAll('button').find((b) => b.text() === 'Sign out')).toBeDefined()
  })

  /**
   * Collapsing hides the panel that holds the control that was just used,
   * so a keyboard user must not be dropped back at the top of the page.
   */
  it('collapses and reopens the sidebar, carrying focus to whichever control now undoes it', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue(IDENTITY)
    const { wrapper } = await mountApp()

    const hide = wrapper.findAll('button').find((b) => b.text() === 'Hide sidebar')
    expect(hide).toBeDefined()
    await hide!.trigger('click')
    await flushPromises()

    expect(wrapper.find('#app-sidebar').classes()).toContain('sidebar--collapsed')
    expect(wrapper.find('#app-sidebar').attributes('inert')).toBeDefined()
    const show = wrapper.findAll('button').find((b) => b.text() === 'Show sidebar')
    expect(show).toBeDefined()
    expect(document.activeElement).toBe(show!.element)
    expect(window.localStorage.getItem('brownie.sidebarOpen')).toBe('false')

    await show!.trigger('click')
    await flushPromises()

    expect(wrapper.find('#app-sidebar').classes()).not.toContain('sidebar--collapsed')
    const hideAgain = wrapper.findAll('button').find((b) => b.text() === 'Hide sidebar')
    expect(document.activeElement).toBe(hideAgain!.element)
  })

  /** A collapsed sidebar is a preference, not a one-off: the next visit must not reopen it. */
  it('starts collapsed when that is how it was left', async () => {
    window.localStorage.setItem('brownie.sidebarOpen', 'false')
    vi.mocked(getCurrentIdentity).mockResolvedValue(IDENTITY)
    const { wrapper } = await mountApp()

    expect(wrapper.find('#app-sidebar').classes()).toContain('sidebar--collapsed')
    expect(wrapper.findAll('button').find((b) => b.text() === 'Show sidebar')).toBeDefined()
  })

  /**
   * Signing in leaves this origin and comes back to the home address, so
   * the page the person actually asked for has to be picked up again here.
   */
  it('goes on to the page that asked for a sign-in, once the session exists', async () => {
    window.sessionStorage.setItem('brownie.signInIntent', '/trash')
    vi.mocked(getCurrentIdentity).mockResolvedValue(IDENTITY)

    const { router } = await mountApp()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/trash')
    expect(window.sessionStorage.getItem('brownie.signInIntent')).toBeNull()
  })

  /**
   * Whichever request finds that the session has ended, the page it was on swaps to its signed-out state at once,
   * taking with it what the person pressed and any message about it. The shell says what happened and takes focus.
   */
  it('says the session has ended, offers sign-in back to where the person was, and takes focus', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue(IDENTITY)
    vi.mocked(listTemplates).mockResolvedValue([])
    const { wrapper } = await mountApp('/trash')

    useSessionStore().markSignedOut()
    await flushPromises()

    const notice = wrapper.get('[role="alert"]')
    expect(notice.text()).toContain('Your session has ended')
    expect(notice.get('a').attributes('href')).toBe('/signin?next=/trash')
    expect(document.activeElement).toBe(notice.element)
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])
    wrapper.unmount()
  })

  it('says so when a signed-in account has no workspace, instead of leaving every page loading', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue({ ...IDENTITY, memberships: [] })
    vi.mocked(listTemplates).mockResolvedValue([])
    const { wrapper } = await mountApp('/')

    expect(wrapper.find('[role="alert"]').text()).toContain('No workspace for this account')
    expect(wrapper.find('[data-test="home"]').exists()).toBe(false)
  })

  it('has no automatically-detectable accessibility violations in the identity-error state', async () => {
    vi.mocked(getCurrentIdentity).mockRejectedValueOnce(new ApiRequestError(503, undefined))
    await mountApp()
    // The whole attached document, because this component's template has several roots.
    expect(await axe(document.body)).toHaveNoViolations()
  })

  it('has no automatically-detectable accessibility violations signed in, with the sidebar showing', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue(IDENTITY)
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 3, displayName: 'Club minutes', status: 'ACTIVE', currentActiveVersionId: 9, createdAt: '2026-09-01T10:00:00Z' },
    ])
    const { wrapper } = await mountApp()
    await flushPromises()

    expect(wrapper.text()).toContain('Club minutes')
    expect(await axe(document.body)).toHaveNoViolations()
  })
})
