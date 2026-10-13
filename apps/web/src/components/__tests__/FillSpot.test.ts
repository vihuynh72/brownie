import { describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import FillSpot from '@/components/workspace/FillSpot.vue'
import { ICONS, type IconName } from '@/components/icons'
import { axe } from '@/test/axe'
import type { FieldStateResponse } from '@/api/client'

const base = {
  rowIndex: null,
  type: 'TEXT' as const,
  value: '',
  label: 'Meeting location',
  placeholder: null,
  styleCss: { fontFamily: '"Liberation Sans", sans-serif' },
  required: false,
  locked: false,
  state: null,
  selected: false,
  inputId: 'edit-meeting.location',
}

function state(over: Partial<FieldStateResponse> = {}): FieldStateResponse {
  return { authorship: 'AI_COMPOSED', evidenceSupport: 'DIRECT', validation: 'NOT_RUN', review: 'UNREVIEWED', lock: 'EDITABLE', ...over }
}

function spotWith(props: Record<string, unknown> = {}): VueWrapper {
  return mount(FillSpot, { props: { ...base, ...props } })
}

/** The words the control is described by, in the one element that holds them. */
function description(spot: VueWrapper): string {
  return spot.get(`[id="${base.inputId}-state"]`).text()
}

/** Which icon of the shared set an svg in the spot draws, told by its path data. */
function iconIn(spot: VueWrapper, selector: string): IconName | undefined {
  const drawn = spot.find(`${selector} path`)
  if (!drawn.exists()) return undefined
  return (Object.keys(ICONS) as IconName[]).find((name) => ICONS[name].paths[0] === drawn.attributes('d'))
}

const markOf = (spot: VueWrapper) => iconIn(spot, '.fill-spot__mark svg')
const lockOf = (spot: VueWrapper) => iconIn(spot, 'svg.fill-spot__lock')

describe('FillSpot: what each state looks like', () => {
  it('draws an empty spot as a gap with nothing at its start, and a typed value plainly', () => {
    const empty = spotWith()
    expect(empty.classes()).toContain('fill-spot--empty')
    expect(empty.find('.fill-spot__mark').exists()).toBe(false)
    expect(empty.find('.fill-spot__count').exists()).toBe(false)

    const typed = spotWith({ value: 'Hall B', state: state({ authorship: 'USER_AUTHORED', evidenceSupport: 'MISSING' }) })
    expect(typed.classes()).toEqual(expect.arrayContaining(['fill-spot--filled']))
    expect(typed.classes()).not.toContain('fill-spot--assist')
    expect(typed.find('.fill-spot__mark').exists()).toBe(false)
  })

  it('marks a place Brownie found with a sparkle, keeping its words out of sight and for the description', () => {
    const spot = spotWith({ found: true })
    expect(spot.classes()).toContain('fill-spot--found')
    expect(markOf(spot)).toBe('sparkle')
    const found = spot.get('.fill-spot__found')
    expect(found.attributes('aria-hidden')).toBe('true')
    expect(found.text()).toBe('Found by Brownie')
    expect(found.find('.visually-hidden').exists()).toBe(true)
    expect(description(spot)).toBe('Found by Brownie; check that this is the right place.')
    expect(spot.get('textarea').attributes('aria-describedby')).toBe(`${base.inputId}-state`)
  })

  it('draws a spot with open questions as a question, with a count after it that is for the eye only', () => {
    const one = spotWith({ questionCount: 1 })
    expect(one.classes()).toContain('fill-spot--question')
    expect(markOf(one)).toBe('help-circle')
    expect(one.get('.fill-spot__count').text()).toBe('1')
    expect(one.get('.fill-spot__count').attributes('aria-hidden')).toBe('true')
    expect(description(one)).toBe('Brownie has a question about this spot. Answer it in the chat.')

    const several = spotWith({ questionCount: 3, value: 'Hall B', state: state({ authorship: 'USER_AUTHORED' }) })
    expect(several.get('.fill-spot__count').text()).toBe('3')
    expect(description(several)).toContain('Brownie has 3 questions about this spot. Answer them in the chat.')

    expect(spotWith({ questionCount: 120 }).get('.fill-spot__count').text()).toBe('99+')
    expect(spotWith({ questionCount: 0 }).find('.fill-spot__count').exists()).toBe(false)
    expect(spotWith({ questionCount: undefined }).find('.fill-spot__count').exists()).toBe(false)
  })

  it('draws a value someone asked to have clarified as a question too, without a count', () => {
    const spot = spotWith({ value: 'Acme', state: state({ review: 'NEEDS_CLARIFICATION' }) })
    expect(spot.classes()).toContain('fill-spot--question')
    expect(spot.classes()).not.toContain('fill-spot--attention')
    expect(markOf(spot)).toBe('help-circle')
    expect(spot.find('.fill-spot__count').exists()).toBe(false)
    expect(description(spot)).toContain('Needs clarification.')
  })

  it('draws a value that cannot be exported, or that a reviewer turned down, in red with an alert', () => {
    const blocked = spotWith({ value: 'Acme', state: state({ authorship: 'USER_AUTHORED', validation: 'BLOCKING' }) })
    expect(blocked.classes()).toContain('fill-spot--attention')
    expect(markOf(blocked)).toBe('alert-circle')
    expect(blocked.get('textarea').attributes('aria-invalid')).toBe('true')

    const blank = spotWith({ type: 'DATE', attention: true })
    expect(blank.classes()).toContain('fill-spot--attention')
    expect(markOf(blank)).toBe('alert-circle')
    expect(blank.get('input').attributes('aria-invalid')).toBe('true')

    // Turned down is not "cannot be saved": it looks like a problem but is not announced as invalid input.
    const rejected = spotWith({ value: 'Acme', state: state({ review: 'REJECTED' }) })
    expect(rejected.classes()).toContain('fill-spot--attention')
    expect(markOf(rejected)).toBe('alert-circle')
    expect(rejected.get('textarea').attributes('aria-invalid')).toBeUndefined()
  })

  it('shows the mark that matters most: a proposal over a problem over a question over a found place', () => {
    const found = { found: true }
    expect(markOf(spotWith(found))).toBe('sparkle')
    expect(markOf(spotWith({ ...found, questionCount: 1 }))).toBe('help-circle')
    expect(markOf(spotWith({ ...found, questionCount: 1, attention: true }))).toBe('alert-circle')
    expect(markOf(spotWith({ ...found, questionCount: 1, attention: true, preview: 'Hall B' }))).toBe('sparkle')
    // The words that say a place was found are only kept where the sparkle stands for them.
    expect(spotWith({ ...found, questionCount: 1 }).find('.fill-spot__found').exists()).toBe(false)
    expect(spotWith({ ...found, questionCount: 1 }).get('textarea').attributes('aria-describedby')).toBe(`${base.inputId}-state`)
    expect(description(spotWith({ ...found, questionCount: 1 }))).toContain('Found by Brownie; check that this is the right place.')
  })

  it('keeps a value from Brownie apart from a typed one by more than colour, and keeps the room under it in a question', () => {
    const fromBrownie = spotWith({ value: 'Hall B', state: state() })
    expect(fromBrownie.classes()).toContain('fill-spot--assist')
    expect(fromBrownie.find('.fill-spot__mark').exists()).toBe(false)
    expect(description(fromBrownie)).toBe('From Brownie. Source cited. Not reviewed.')

    const asked = spotWith({ value: 'Hall B', state: state(), questionCount: 1 })
    expect(asked.classes()).toEqual(expect.arrayContaining(['fill-spot--assist', 'fill-spot--question']))
  })

  it('shows a lock at the end of a locked spot and says why it cannot change', () => {
    const spot = spotWith({ value: 'Q3 planning', locked: true, state: state({ lock: 'EXPLICITLY_LOCKED' }) })
    expect(spot.classes()).toContain('fill-spot--locked')
    expect(lockOf(spot)).toBe('lock')
    expect(spot.get('.fill-spot__lock').attributes('aria-hidden')).toBe('true')
    const control = spot.get('textarea')
    expect(control.attributes('readonly')).toBeDefined()
    expect(control.attributes('aria-readonly')).toBe('true')
    expect(description(spot)).toBe('From Brownie. Source cited. Not reviewed. Locked: unlock it to edit.')
    expect(spotWith({ value: 'Q3 planning' }).find('.fill-spot__lock').exists()).toBe(false)
  })

  it('marks the selected spot and a required one, the mark for the eye only', () => {
    const spot = spotWith({ selected: true, required: true })
    expect(spot.classes()).toContain('fill-spot--selected')
    const required = spot.get('.fill-spot__required')
    expect(required.attributes('aria-hidden')).toBe('true')
    expect(required.text()).toMatch(/\*$/)
    expect(spot.get('textarea').attributes('aria-label')).toBe('Meeting location, required')
  })
})

describe('FillSpot: a value Brownie proposes', () => {
  const proposed = { preview: 'Room 204, Student Union' }

  it('shows the proposal in place of the value, as a ghost with a sparkle, without touching the value', () => {
    const spot = spotWith({ ...proposed, value: '' })
    expect(spot.classes()).toContain('fill-spot--preview')
    expect(markOf(spot)).toBe('sparkle')
    const control = spot.get('textarea')
    expect((control.element as HTMLTextAreaElement).value).toBe('Room 204, Student Union')
    // Always honey ink and italics, whatever the template gives that text.
    const style = (control.element as HTMLElement).style
    expect(style.fontStyle).toBe('italic')
    expect(style.color).toBe('var(--color-honey-ink)')
    expect(style.fontFamily).toContain('Liberation Sans')
    expect(spot.emitted('update:value')).toBeUndefined()
  })

  it('cannot be edited while it is a proposal, but can still be selected and asked about', async () => {
    const spot = spotWith({ ...proposed })
    const control = spot.get('textarea')
    expect(control.attributes('readonly')).toBeDefined()
    expect(control.attributes('aria-readonly')).toBe('true')

    await control.trigger('focus')
    await control.trigger('click')
    expect(spot.emitted('focus')).toHaveLength(2)
    const withAlt = new KeyboardEvent('keydown', { key: 'Enter', altKey: true, cancelable: true })
    control.element.dispatchEvent(withAlt)
    expect(withAlt.defaultPrevented).toBe(true)
    expect(spot.emitted('actions')).toHaveLength(1)

    // A paste with line breaks is not folded into a proposal: the field refuses it, and the page does not step in.
    const paste = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent
    Object.defineProperty(paste, 'clipboardData', { value: { getData: () => 'one\ntwo' } })
    control.element.dispatchEvent(paste)
    expect(paste.defaultPrevented).toBe(false)
    expect(spot.emitted('update:value')).toBeUndefined()
  })

  it('says in words that it is proposed and what to do about it', () => {
    const spot = spotWith({ ...proposed, value: 'Hall B', state: state() })
    expect(description(spot)).toBe(
      'Proposed by Brownie, not in the document yet. Approve or hide the preview to edit it. From Brownie. Source cited. Not reviewed.',
    )
  })

  it('is as wide as the proposal, and goes back to the value when the proposal goes', async () => {
    const spot = spotWith({ value: 'Hall', preview: 'x'.repeat(30) })
    const control = () => spot.get('textarea').element as HTMLTextAreaElement
    expect(control().style.getPropertyValue('--spot-chars')).toBe('31')
    expect(control().value).toBe('x'.repeat(30))

    await spot.setProps({ preview: null })
    expect(spot.classes()).not.toContain('fill-spot--preview')
    expect(control().value).toBe('Hall')
    expect(control().style.getPropertyValue('--spot-chars')).toBe('5')
    expect(control().readOnly).toBe(false)
    expect(control().style.fontStyle).toBe('')
    expect(spot.find('.fill-spot__mark').exists()).toBe(false)
  })

  it('treats a proposal with nothing in it as no proposal', () => {
    for (const preview of ['', '   ', null, undefined]) {
      const spot = spotWith({ preview, value: 'Hall B' })
      expect(spot.classes()).not.toContain('fill-spot--preview')
      expect((spot.get('textarea').element as HTMLTextAreaElement).value).toBe('Hall B')
    }
  })

  it('keeps a locked spot locked when a proposal arrives for it', () => {
    const spot = spotWith({ ...proposed, locked: true, state: state({ lock: 'EXPLICITLY_LOCKED' }) })
    expect(spot.classes()).toEqual(expect.arrayContaining(['fill-spot--preview', 'fill-spot--locked']))
    expect(lockOf(spot)).toBe('lock')
    expect(description(spot)).toContain('Locked: unlock it to edit.')
  })
})

describe('FillSpot: a date reads like the other spots', () => {
  const date = { type: 'DATE' as const, label: 'Meeting date', inputId: 'edit-meeting.date' }

  it('draws the date the way the export prints it over the date control, which stays the one that is focused', () => {
    const spot = spotWith({ ...date, value: '2026-10-06', required: true })
    const text = spot.get('.fill-spot__date-text')
    expect(text.text()).toBe('October 6, 2026')
    expect(text.attributes('aria-hidden')).toBe('true')
    const control = spot.get('input')
    expect(control.attributes('type')).toBe('date')
    expect((control.element as HTMLInputElement).value).toBe('2026-10-06')
    expect(control.attributes('aria-label')).toBe('Meeting date, required')
    expect(spot.findAll('input')).toHaveLength(1)
  })

  it('draws the bracketed placeholder while empty, never the browser\'s own mask', () => {
    expect(spotWith({ ...date }).get('.fill-spot__date-text').text()).toBe('[Meeting date]')
    expect(spotWith({ ...date, placeholder: 'Date' }).get('.fill-spot__date-text').text()).toBe('[Date]')
    expect(spotWith({ ...date, placeholder: '[date]', value: '   ' }).get('.fill-spot__date-text').text()).toBe('[date]')
  })

  it('never hides a date it cannot read: what the field holds is what shows', () => {
    expect(spotWith({ ...date, value: 'Tuesday next week' }).get('.fill-spot__date-text').text()).toBe('Tuesday next week')
    expect(spotWith({ ...date, value: '2026-02-30' }).get('.fill-spot__date-text').text()).toBe('2026-02-30')
    expect(spotWith({ ...date, value: '10/06/2026' }).get('.fill-spot__date-text').text()).toBe('10/06/2026')
  })

  it('keeps a date it can read on one line, and lets anything else wrap', () => {
    const oneLine = (props: Record<string, unknown>) => spotWith({ ...date, ...props }).get('.fill-spot__date-text').classes('fill-spot__date-text--one-line')
    expect(oneLine({ value: '2026-10-06' })).toBe(true)
    expect(oneLine({ preview: '2026-10-13' })).toBe(true)
    expect(oneLine({ value: 'Tuesday next week' })).toBe(false)
    expect(oneLine({ value: '2026-02-30' })).toBe(false)
    expect(oneLine({})).toBe(false)
  })

  it('draws a proposed date formatted too, and cannot be edited', () => {
    const spot = spotWith({ ...date, preview: '2026-10-13' })
    expect(spot.get('.fill-spot__date-text').text()).toBe('October 13, 2026')
    expect(spot.get('.fill-spot__date-text').attributes('style')).toContain('font-style: italic')
    expect(spot.get('input').attributes('readonly')).toBeDefined()
    expect((spot.get('input').element as HTMLInputElement).value).toBe('2026-10-13')
    expect(markOf(spot)).toBe('sparkle')
  })

  it('takes the template colour for a date it holds, but lets the placeholder keep its own quiet one', () => {
    const styleCss = { fontFamily: '"Liberation Sans", sans-serif', color: '#112233' }
    const filled = spotWith({ ...date, styleCss, value: '2026-10-06' }).get('.fill-spot__date-text')
    expect((filled.element as HTMLElement).style.color).not.toBe('')
    const empty = spotWith({ ...date, styleCss }).get('.fill-spot__date-text')
    expect((empty.element as HTMLElement).style.color).toBe('')
  })

  it('reports typing and Alt+Enter from the date control as before', async () => {
    const spot = spotWith({ ...date })
    const control = spot.get('input')
    await control.setValue('2026-10-06')
    expect(spot.emitted('update:value')).toEqual([['2026-10-06']])
    const withAlt = new KeyboardEvent('keydown', { key: 'Enter', altKey: true, cancelable: true })
    control.element.dispatchEvent(withAlt)
    expect(withAlt.defaultPrevented).toBe(true)
    expect(spot.emitted('actions')).toHaveLength(1)
  })

  it('shows the same marks as a text spot', () => {
    expect(markOf(spotWith({ ...date, found: true }))).toBe('sparkle')
    expect(markOf(spotWith({ ...date, questionCount: 2 }))).toBe('help-circle')
    expect(lockOf(spotWith({ ...date, value: '2026-10-06', locked: true }))).toBe('lock')
    expect(spotWith({ ...date, found: true }).get('.fill-spot__found').text()).toBe('Found by Brownie')
  })
})

describe('FillSpot: accessibility', () => {
  it('passes an accessibility scan in every state at once', async () => {
    const states: Array<Record<string, unknown>> = [
      {},
      { found: true },
      { value: 'Hall B', state: state() },
      { value: 'Hall B', state: state({ authorship: 'USER_AUTHORED' }), selected: true, required: true },
      { questionCount: 2 },
      { attention: true },
      { preview: 'Room 204' },
      { value: 'Q3', locked: true, state: state({ lock: 'EXPLICITLY_LOCKED' }) },
      { type: 'DATE', value: '2026-10-06' },
      { type: 'DATE', found: true, questionCount: 1 },
      { type: 'DATE', preview: '2026-10-13' },
    ]
    const Page = defineComponent({
      render: () =>
        h(
          'main',
          states.map((over, index) =>
            h('p', { key: index }, [h(FillSpot, { ...base, ...over, inputId: `spot-${index}`, label: `Spot ${index}` })]),
          ),
        ),
    })
    const page = mount(Page, { attachTo: document.body })
    expect(await axe(page.element as HTMLElement)).toHaveNoViolations()
    page.unmount()
  })
})
