import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import BrownieDemo from '@/components/BrownieDemo.vue'

describe('BrownieDemo', () => {
  it('reviews edited example context, approves once and replays without creating a file', async () => {
    const wrapper = mount(BrownieDemo, { attachTo: document.body })
    const button = (text: string) => wrapper.findAll('button').find((b) => b.text().includes(text))!
    await button('Event registration').trigger('click')
    await wrapper.get('textarea').setValue('Taylor Example is attending a workshop.')
    await button('Prepare this example').trigger('click')
    expect(wrapper.text()).toContain('Taylor Example is attending a workshop.')
    expect(document.activeElement).toBe(wrapper.get('h3').element)
    await button('Approve example').trigger('click')
    expect(wrapper.text()).toContain('No AI request was made')
    expect(wrapper.text()).toContain('no file was created or downloaded')
    expect(document.activeElement).toBe(wrapper.get('h3').element)
    await button('Try it again').trigger('click')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain('Alex Rivera')
    wrapper.unmount()
  })
})
