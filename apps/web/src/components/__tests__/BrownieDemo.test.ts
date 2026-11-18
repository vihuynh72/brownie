import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import BrownieDemo from '@/components/BrownieDemo.vue'

describe('BrownieDemo', () => {
  it('reviews edited example context, approves once and replays without creating a file', async () => {
    const wrapper = mount(BrownieDemo, { attachTo: document.body })
    const button = (text: string) => wrapper.findAll('button').find((b) => b.text().includes(text))!
    await button('Event registration').trigger('click')
    await button('Review example').trigger('click')
    expect((wrapper.findAll('input')[0].element as HTMLInputElement).value).toBe('Alex Rivera')
    expect(wrapper.text()).toContain('Not provided in the example')
    await wrapper.findAll('input')[2].setValue('alex@example.com')
    expect(document.activeElement).toBe(wrapper.get('h3').element)
    await button('Approve example').trigger('click')
    expect(wrapper.text()).toContain('Email address: alex@example.com')
    await button('Edit review').trigger('click')
    expect((wrapper.findAll('input')[2].element as HTMLInputElement).value).toBe('alex@example.com')
    await button('Approve example').trigger('click')
    expect(wrapper.text()).toContain('No AI request was made')
    expect(wrapper.text()).toContain('no file was created or downloaded')
    expect(document.activeElement).toBe(wrapper.get('h3').element)
    await button('Try it again').trigger('click')
    expect(wrapper.text()).toContain('Alex Rivera')
    await button('Review example').trigger('click')
    expect((wrapper.findAll('input')[2].element as HTMLInputElement).value).toBe('')
    await button('Approve example').trigger('click')
    expect(wrapper.text()).toContain('Email address: left blank')
    wrapper.unmount()
  })
})
