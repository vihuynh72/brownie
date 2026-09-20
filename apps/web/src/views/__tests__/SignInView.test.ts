import { describe, expect, it, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import SignInView from '@/views/SignInView.vue'
import { axe } from '@/test/axe'

async function mountAt(path: string) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/signin', component: SignInView }],
  })
  router.push(path)
  await router.isReady()
  return mount(SignInView, { global: { plugins: [router] }, attachTo: document.body })
}

// These mount into the document so a click on a real anchor reaches this listener, which
// cancels the navigation jsdom cannot perform; the assertion is about what the handler
// parked on the way out, not about the navigation itself.
function swallowNavigation(event: Event): void {
  event.preventDefault()
}

describe('SignInView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    window.sessionStorage.clear()
    document.body.innerHTML = ''
    document.addEventListener('click', swallowNavigation)
  })

  afterEach(() => document.removeEventListener('click', swallowNavigation))

  it('sends the browser to the provider, where the same page signs in and signs up', async () => {
    const wrapper = await mountAt('/signin')

    const action = wrapper.get('a.button')
    expect(action.attributes('href')).toBe('/oauth2/authorization/entra')
    expect(action.text()).toContain('Sign in or sign up')
    expect(wrapper.text()).toContain('create one there')
  })

  /** Being interrupted on the way somewhere should say where, so the page does not read like an unprompted demand. */
  it('names what the sign-in is for when it interrupted something', async () => {
    const wrapper = await mountAt('/signin?next=%2Fdocuments%2Fnew')

    expect(wrapper.text()).toContain('to upload your documents')
  })

  it('tells someone who just deleted their workspace that it worked, before anything else', async () => {
    const wrapper = await mountAt('/signin?deleted=1')

    expect(wrapper.find('[role="status"]').text()).toContain('Your workspace and everything in it was deleted.')
    expect(wrapper.text()).toContain('Sign in or sign up')
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])
  })

  it('parks the interrupted destination before the browser leaves for the provider', async () => {
    const wrapper = await mountAt('/signin?next=%2Ftrash')
    await wrapper.get('a.button').trigger('click')

    expect(window.sessionStorage.getItem('brownie.signInIntent')).toBe('/trash')
  })

  /** A destination that is not an in-app path is a redirect off this site; it is dropped, not followed. */
  it('refuses to park anything but an in-app path', async () => {
    const wrapper = await mountAt('/signin?next=https%3A%2F%2Felsewhere.example%2Fx')
    await wrapper.get('a.button').trigger('click')

    expect(window.sessionStorage.getItem('brownie.signInIntent')).toBeNull()
  })

  it('has no automatically-detectable accessibility violations', async () => {
    const wrapper = await mountAt('/signin?next=%2Ftrash')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
