import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import FillSpot from '@/components/workspace/FillSpot.vue'

const baseProps = {
  rowIndex: null,
  type: 'CHECKBOX' as const,
  value: 'unchecked',
  label: 'Get news by email',
  placeholder: null,
  styleCss: { top: '10px', left: '10px' },
  required: false,
  locked: false,
  state: null,
  selected: false,
  inputId: 'spot-news',
}

describe('FillSpot as a checkbox', () => {
  it('renders a real checkbox the person toggles', () => {
    const spot = mount(FillSpot, { props: baseProps })
    const input = spot.find('input[type="checkbox"]')
    expect(input.exists()).toBe(true)
    expect(input.attributes('aria-checked')).toBe('false')
    expect(input.attributes('aria-label')).toContain('Get news by email')
  })

  it('emits checked and unchecked the way the server writes them', async () => {
    const spot = mount(FillSpot, { props: baseProps })
    const input = spot.find('input[type="checkbox"]')
    await input.setValue(true)
    expect(spot.emitted('update:value')?.[0]).toEqual(['checked'])
    await input.setValue(false)
    expect(spot.emitted('update:value')?.[1]).toEqual(['unchecked'])
  })

  it('keeps locked and proposed checkboxes focusable without allowing changes', async () => {
    for (const props of [{ locked: true }, { preview: 'checked' }]) {
      const spot = mount(FillSpot, { props: { ...baseProps, ...props }, attachTo: document.body })
      const input = spot.get('input[type="checkbox"]')
      const element = input.element as HTMLInputElement
      const original = element.checked
      expect(element.disabled).toBe(false)
      expect(input.attributes('aria-readonly')).toBe('true')
      expect(input.attributes('aria-keyshortcuts')).toBe('Alt+Enter')
      element.focus()
      expect(document.activeElement).toBe(element)
      await input.trigger('click')
      expect(element.checked).toBe(original)
      expect(spot.emitted('update:value')).toBeUndefined()
      await input.trigger('keydown', { key: 'Enter', altKey: true })
      expect(spot.emitted('actions')).toHaveLength(1)
      spot.unmount()
    }
  })
})
