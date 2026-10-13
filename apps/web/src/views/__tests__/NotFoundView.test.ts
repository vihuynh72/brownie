import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import NotFoundView from '@/views/NotFoundView.vue'
import { axe } from '@/test/axe'

async function mountAt(path: string) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: NotFoundView },
    ],
  })
  router.push(path)
  await router.isReady()
  return mount(NotFoundView, { global: { plugins: [router] }, attachTo: document.body })
}

describe('NotFoundView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  /** A mistyped or stale link gets a page that says so and a way back, not a blank main region. */
  it('says nothing is at the address, shows which address, and offers the way back', async () => {
    const wrapper = await mountAt('/documents/12/whatever')

    expect(wrapper.get('h1').text()).toBe('There is nothing at this address')
    expect(wrapper.get('code').text()).toBe('/documents/12/whatever')
    expect(wrapper.text()).toContain('is not a page in Brownie')

    const back = wrapper.get('a.button')
    expect(back.text()).toBe('Go to your documents')
    expect(back.attributes('href')).toBe('/')
  })

  it('has no automatically-detectable accessibility violations', async () => {
    const wrapper = await mountAt('/nope')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
