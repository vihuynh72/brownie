import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
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
      { path: '/connections', name: 'connections', component: stub },
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

/**
 * A window narrower than the width at which the sidebar docks: matchMedia says so, and the listener the shell
 * leaves on it is kept, so a test can widen the window and cross the breakpoint the way a resize does.
 */
function narrowWindow(): { widen: () => void; narrow: () => void; restore: () => void } {
  const listeners = new Set<(event: MediaQueryListEvent) => void>()
  const query = {
    matches: false,
    media: '(min-width: 64rem)',
    addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.delete(listener),
  }
  const original = window.matchMedia
  // Only the dock query has an answer; any other (a preference for reduced motion, say) is simply "no".
  window.matchMedia = ((media: string) => (media === query.media ? query : { ...query, media, matches: false })) as unknown as typeof window.matchMedia
  const cross = (matches: boolean) => {
    query.matches = matches
    listeners.forEach((listener) => listener({ matches } as MediaQueryListEvent))
  }
  return {
    widen: () => cross(true),
    narrow: () => cross(false),
    restore: () => {
      window.matchMedia = original
    },
  }
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

  describe('as a drawer on a narrow window', () => {
    let narrow: ReturnType<typeof narrowWindow>
    let mounted: VueWrapper | null = null

    beforeEach(() => {
      narrow = narrowWindow()
      vi.mocked(getCurrentIdentity).mockResolvedValue(IDENTITY)
    })

    afterEach(() => {
      // The shell listens on the document and on the window; a copy left mounted would answer the next test's keys.
      mounted?.unmount()
      mounted = null
      narrow.restore()
      document.body.style.overflow = ''
    })

    async function mountNarrow(): Promise<VueWrapper> {
      mounted = (await mountApp()).wrapper
      return mounted
    }

    function button(wrapper: VueWrapper, name: string) {
      return wrapper.findAll('button').find((b) => b.text() === name)
    }

    it('starts closed, with a menu button over the page, however the sidebar was left on a wide window', async () => {
      window.localStorage.setItem('brownie.sidebarOpen', 'true')
      const wrapper = await mountNarrow()

      expect(wrapper.find('#app-sidebar').attributes('inert')).toBeDefined()
      expect(wrapper.find('.shell__scrim').exists()).toBe(false)
      expect(button(wrapper, 'Open menu')).toBeDefined()
      expect(button(wrapper, 'Show sidebar')).toBeUndefined()
      expect(document.body.style.overflow).toBe('')
    })

    it('opens over a dimmed page that cannot scroll, takes focus, and gives it back on Escape', async () => {
      const wrapper = await mountNarrow()
      const open = button(wrapper, 'Open menu')!

      await open.trigger('click')
      await flushPromises()

      expect(wrapper.find('#app-sidebar').attributes('inert')).toBeUndefined()
      expect(wrapper.find('.shell__scrim').exists()).toBe(true)
      expect(document.body.style.overflow).toBe('hidden')
      expect(document.activeElement).toBe(button(wrapper, 'Close menu')!.element)

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
      await flushPromises()

      expect(wrapper.find('#app-sidebar').attributes('inert')).toBeDefined()
      expect(wrapper.find('.shell__scrim').exists()).toBe(false)
      expect(document.body.style.overflow).toBe('')
      expect(document.activeElement).toBe(open.element)
      // Closing a drawer is not a choice about the docked sidebar, so it is not remembered as one.
      expect(window.localStorage.getItem('brownie.sidebarOpen')).toBeNull()
    })

    it('closes on a tap anywhere on the dimmed page, and when a link inside it is followed', async () => {
      const wrapper = await mountNarrow()

      await button(wrapper, 'Open menu')!.trigger('click')
      await flushPromises()
      await wrapper.get('.shell__scrim').trigger('click')
      await flushPromises()
      expect(wrapper.find('.shell__scrim').exists()).toBe(false)
      expect(document.activeElement).toBe(button(wrapper, 'Open menu')!.element)

      await button(wrapper, 'Open menu')!.trigger('click')
      await flushPromises()
      await wrapper.get('#app-sidebar a[href="/trash"]').trigger('click')
      await flushPromises()
      expect(wrapper.find('.shell__scrim').exists()).toBe(false)
      expect(document.body.style.overflow).toBe('')
    })

    it('lands on the shape of each width as the window crosses the breakpoint, not on the other one\'s leftovers', async () => {
      const wrapper = await mountNarrow()
      await button(wrapper, 'Open menu')!.trigger('click')
      await flushPromises()
      expect(wrapper.find('.shell__scrim').exists()).toBe(true)

      // Widened with the drawer open: a docked column, open as docked sidebars start, and nothing dimmed or locked.
      narrow.widen()
      await flushPromises()
      expect(wrapper.find('#app-sidebar').classes()).toContain('sidebar--open')
      expect(wrapper.find('#app-sidebar').attributes('inert')).toBeUndefined()
      expect(wrapper.find('.shell__scrim').exists()).toBe(false)
      expect(document.body.style.overflow).toBe('')
      expect(button(wrapper, 'Hide sidebar')).toBeDefined()

      // Narrowed again: a closed drawer, and the page is not covered.
      narrow.narrow()
      await flushPromises()
      expect(wrapper.find('#app-sidebar').attributes('inert')).toBeDefined()
      expect(button(wrapper, 'Open menu')).toBeDefined()
    })
  })

  it('draws the failure to confirm a sign-in as the shared card, with the shared primary button to try again', async () => {
    vi.mocked(getCurrentIdentity).mockRejectedValueOnce(new ApiRequestError(503, undefined))
    const { wrapper } = await mountApp()

    const failed = wrapper.get('[role="alert"]')
    expect(failed.classes()).toContain('panel')
    expect(failed.get('h1').text()).toBe('Could not confirm your sign-in')
    expect(failed.get('button').classes()).toContain('button--primary')
    wrapper.unmount()
  })

  it('draws the ended session as the shared card, with the shared primary button to sign in again', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue(IDENTITY)
    vi.mocked(listTemplates).mockResolvedValue([])
    const { wrapper } = await mountApp('/trash')
    useSessionStore().markSignedOut()
    await flushPromises()

    const notice = wrapper.get('[role="alert"]')
    expect(notice.classes()).toContain('panel')
    const again = notice.get('a')
    expect(again.text()).toBe('Sign in again')
    expect(again.classes()).toContain('button--primary')
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
