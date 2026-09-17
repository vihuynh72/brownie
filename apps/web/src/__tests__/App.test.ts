import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from '@/App.vue'
import { useSessionStore } from '@/stores/session'
import { ApiRequestError } from '@/api/client'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, getCurrentIdentity: vi.fn(), logout: vi.fn() }
})
vi.mock('@/navigation', () => ({ navigateTo: vi.fn() }))

import { getCurrentIdentity, logout } from '@/api/client'
import { navigateTo } from '@/navigation'

async function mountApp() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/', component: { template: '<div data-test="home">home</div>' } }],
  })
  router.push('/')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
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
    vi.mocked(navigateTo).mockReset()
  })

  it('shows a recoverable alert with a working retry when identity cannot be loaded, instead of the route', async () => {
    vi.mocked(getCurrentIdentity).mockRejectedValueOnce(new ApiRequestError(503, undefined))
    const wrapper = await mountApp()

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
    const wrapper = await mountApp()

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
    const wrapper = await mountApp()

    const signOut = wrapper.findAll('button').find((b) => b.text() === 'Sign out')
    await signOut!.trigger('click')
    await flushPromises()

    expect(navigateTo).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toMatch(/could not sign out/i)
    expect(useSessionStore().status).toBe('authenticated')
    expect(wrapper.findAll('button').find((b) => b.text() === 'Sign out')).toBeDefined()
  })

  it('has no automatically-detectable accessibility violations in the identity-error state', async () => {
    vi.mocked(getCurrentIdentity).mockRejectedValueOnce(new ApiRequestError(503, undefined))
    const wrapper = await mountApp()
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
