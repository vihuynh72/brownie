import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ChoiceList from '@/components/workspace/ChoiceList.vue'
import { axe } from '@/test/axe'

const base = { options: ['I approve, fill it in', 'Not now'], name: 'Your decision about these values', idPrefix: 'proposal' }

describe('ChoiceList', () => {
  it('offers each choice as a numbered button in a named group, then a line for typing something else', () => {
    const list = mount(ChoiceList, { props: base })
    expect(list.get('[role="group"]').attributes('aria-label')).toBe('Your decision about these values')
    const buttons = list.findAll('.choices__option')
    expect(buttons.map((button) => button.text())).toEqual(['1I approve, fill it in', '2Not now'])
    expect(buttons[0]!.get('.choices__number').attributes('aria-hidden')).toBe('true')
    expect(list.get('label[for="proposal-other"]').text()).toBe('Other:')
    expect(list.get('.choices__other .choices__number').text()).toBe('3')
  })

  it('reports the choice pressed, and ignores presses while busy', async () => {
    const list = mount(ChoiceList, { props: base })
    await list.findAll('.choices__option')[1]!.trigger('click')
    expect(list.emitted('choose')).toEqual([[1]])

    await list.setProps({ busy: true })
    await list.findAll('.choices__option')[0]!.trigger('click')
    expect(list.emitted('choose')).toEqual([[1]])
    expect(list.findAll('.choices__option')[0]!.attributes('aria-disabled')).toBe('true')
  })

  it('sends typed words trimmed, never an empty line, and empties the line unless told to keep it', async () => {
    const list = mount(ChoiceList, { props: base })
    await list.get('#proposal-other').setValue('   ')
    await list.get('form').trigger('submit')
    expect(list.emitted('other')).toBeUndefined()

    await list.get('#proposal-other').setValue('  Use Friday instead ')
    await list.get('form').trigger('submit')
    expect(list.emitted('other')).toEqual([['Use Friday instead']])
    expect((list.get('#proposal-other').element as HTMLInputElement).value).toBe('')

    const kept = mount(ChoiceList, { props: { ...base, idPrefix: 'question-4', clearOnSend: false } })
    await kept.get('#question-4-other').setValue('2026-10-02')
    await kept.get('form').trigger('submit')
    expect(kept.emitted('other')).toEqual([['2026-10-02']])
    expect((kept.get('#question-4-other').element as HTMLInputElement).value).toBe('2026-10-02')
  })

  it('shows only the typing line, unnumbered, when there is nothing to choose from', () => {
    const list = mount(ChoiceList, { props: { ...base, options: [], idPrefix: 'question-9', otherLabel: 'Your answer' } })
    expect(list.findAll('.choices__option')).toHaveLength(0)
    expect(list.find('.choices__number').exists()).toBe(false)
    expect(list.get('label[for="question-9-other"]').text()).toBe('Your answer:')
    expect(list.get('.choices__send').text()).toBe('Send your answer')
    expect(mount(ChoiceList, { props: base }).get('.choices__send').text()).toBe('Send other answer')
  })

  it('is a card of its own, or sits without one inside a card that already holds it', () => {
    const alone = mount(ChoiceList, { props: base })
    expect(alone.classes()).toContain('msg-card')
    expect(alone.classes()).not.toContain('choices--flush')

    const inside = mount(ChoiceList, { props: { ...base, flush: true } })
    expect(inside.classes()).toContain('choices--flush')
    expect(inside.classes()).not.toContain('msg-card')
    // Only its look changes: the group and its choices are the same.
    expect(inside.get('[role="group"]').attributes('aria-label')).toBe(base.name)
    expect(inside.findAll('.choices__option')).toHaveLength(2)
  })

  it('shows the send button only once something is typed, and not for a line that is empty again after sending', async () => {
    const list = mount(ChoiceList, { props: base })
    const send = () => list.get('.choices__send')
    expect(send().classes()).toContain('choices__send--idle')

    await list.get('#proposal-other').setValue('Use Friday')
    expect(send().classes()).not.toContain('choices__send--idle')
    expect(send().attributes('aria-disabled')).toBe('false')

    await list.get('form').trigger('submit')
    expect(send().classes()).toContain('choices__send--idle')

    // While Brownie is busy, typed words wait: the button is there but not pressable.
    await list.get('#proposal-other').setValue('Use Monday')
    await list.setProps({ busy: true })
    expect(send().classes()).not.toContain('choices__send--idle')
    expect(send().attributes('aria-disabled')).toBe('true')
  })

  it('passes an accessibility scan', async () => {
    const list = mount(ChoiceList, { props: base, attachTo: document.body })
    expect(await axe(list.element as HTMLElement)).toHaveNoViolations()
    list.unmount()
  })

  it('passes an accessibility scan inside a card too, with a line typed in', async () => {
    const list = mount(ChoiceList, { props: { ...base, flush: true }, attachTo: document.body })
    await list.get('#proposal-other').setValue('Use Friday')
    expect(await axe(list.element as HTMLElement)).toHaveNoViolations()
    list.unmount()
  })
})
