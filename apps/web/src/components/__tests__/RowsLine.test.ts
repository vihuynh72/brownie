import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RowsLine from '@/components/workspace/RowsLine.vue'
import { axe } from '@/test/axe'

describe('RowsLine', () => {
  it('says there are no rows yet and offers the first one', async () => {
    const line = mount(RowsLine, { props: { empty: true, locked: false, button: true } })
    expect(line.get('.rows-line__text').text()).toBe('No rows yet.')
    const button = line.get('button')
    expect(button.text()).toBe('Add row')
    expect(button.attributes('data-add-row')).toBeDefined()
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    expect(line.emitted('add')).toHaveLength(1)
  })

  it('is only the button once there are rows', () => {
    const line = mount(RowsLine, { props: { empty: false, locked: false, button: true } })
    expect(line.text()).toBe('Add row')
    expect(line.find('.rows-line__text').exists()).toBe(false)
  })

  it('leaves the button to another line when it is not the one that carries it', () => {
    const line = mount(RowsLine, { props: { empty: true, locked: false } })
    expect(line.text()).toBe('No rows yet.')
    expect(line.find('button').exists()).toBe(false)
  })

  it('holds the button back, and says why, while a row is locked', async () => {
    const line = mount(RowsLine, { props: { empty: false, locked: true, button: true } })
    expect(line.get('button').attributes('disabled')).toBeDefined()
    expect(line.text()).toContain('A row is locked, so rows cannot be added until it is unlocked.')
    await line.get('button').trigger('click')
    expect(line.emitted('add')).toBeUndefined()
  })

  it('says in words that Brownie found the table, for a reader who cannot see the mark', () => {
    const found = mount(RowsLine, { props: { empty: true, found: true, locked: false, button: true } })
    expect(found.get('.visually-hidden').text()).toBe('Found by Brownie.')
    expect(found.find('svg[aria-hidden="true"]').exists()).toBe(true)
    const plain = mount(RowsLine, { props: { empty: true, locked: false, button: true } })
    expect(plain.text()).not.toContain('Found by Brownie')
  })

  it('passes an accessibility scan empty, with rows and while locked', async () => {
    const states: Array<{ empty: boolean; found?: boolean; locked: boolean; button?: boolean }> = [
      { empty: true, found: true, locked: false, button: true },
      { empty: false, locked: false, button: true },
      { empty: false, locked: true, button: true },
    ]
    for (const props of states) {
      const line = mount(RowsLine, { props, attachTo: document.body })
      expect(await axe(line.element)).toHaveNoViolations()
      line.unmount()
    }
  })
})
