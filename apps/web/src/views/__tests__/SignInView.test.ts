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

const ONE_PAGE_FOR_BOTH =
  'Brownie uses one page for both: sign in with an account you already have, ' +
  'or create one there if this is your first time.'

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
    // The only way out of this page is the one action; the steps below it are information, not links.
    expect(wrapper.findAll('a')).toHaveLength(1)
  })

  /** Being interrupted on the way somewhere should say where, so the page does not read like an unprompted demand. */
  it('names what the sign-in is for when it interrupted something', async () => {
    const wrapper = await mountAt('/signin?next=%2F')

    expect(wrapper.text()).toContain('You need to be signed in to upload your documents and start a new one.')
  })

  it.each([
    ['/signin', 'You need to be signed in to open your documents.'],
    ['/signin?next=%2Fyour-data', 'You need to be signed in to see what Brownie keeps and for how long.'],
    ['/signin?next=%2Fdocuments%2F12', 'You need to be signed in to open that document.'],
    // An in-app address with no sentence of its own is not guessed at.
    ['/signin?next=%2Fsomewhere-else', 'You need to be signed in to open your documents.'],
  ])('reads %s as one paragraph that says why and how', async (address, reason) => {
    const wrapper = await mountAt(address)

    const lede = wrapper.findAll('p').find((paragraph) => paragraph.text().includes('You need to be signed in'))
    expect(lede?.text()).toBe(`${reason} ${ONE_PAGE_FOR_BOTH}`)
  })

  it('tells someone who just deleted their workspace that it worked, before anything else', async () => {
    const wrapper = await mountAt('/signin?deleted=1')

    expect(wrapper.find('[role="status"]').text()).toContain('Your workspace and everything in it was deleted.')
    expect(wrapper.text()).toContain('Sign in or sign up')
    // "You need to be signed in" would read oddly straight after being told the account's data is gone.
    expect(wrapper.text()).not.toContain('You need to be signed in')
    expect(wrapper.text()).toContain('Brownie uses one page for both')
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

  it('has one page title, and does not read the brand mark out a second time', async () => {
    const wrapper = await mountAt('/signin')

    const titles = wrapper.findAll('h1')
    expect(titles).toHaveLength(1)
    expect(titles[0]?.text()).toBe('Sign in to Brownie')
    // The wordmark above the title says the same word; it is a picture, not a second heading.
    expect(wrapper.find('p[aria-hidden="true"]').text()).toBe('Brownie')
  })

  it('says who may sign in and that the workspace is private', async () => {
    const wrapper = await mountAt('/signin')

    expect(wrapper.text()).toContain('Open to invited people only. Your workspace is private to you.')
  })

  /** What a visitor is told Brownie does before they have an account. */
  it('lays out how Brownie works in three steps, in order', async () => {
    const wrapper = await mountAt('/signin')

    const steps = wrapper.get('ol[aria-label="How Brownie works"]').findAll('li')
    expect(steps.map((step) => step.get('h2').text())).toEqual(['Upload a form', 'Add your notes', 'Check and export'])
    expect(steps.map((step) => step.get('p').text())).toEqual(['Step 1', 'Step 2', 'Step 3'])
    expect(steps[0]?.text()).toContain('Brownie learns where the values go and keeps the form’s own look.')
    expect(steps[1]?.text()).toContain('Brownie reads only what you add.')
    expect(steps[2]?.text()).toContain('You approve every value. Brownie exports exactly the version you approved.')
  })

  it('has no automatically-detectable accessibility violations', async () => {
    const wrapper = await mountAt('/signin?next=%2Ftrash')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no automatically-detectable accessibility violations when it carries no destination', async () => {
    const wrapper = await mountAt('/signin')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
