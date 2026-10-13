import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import PlaceSpotDialog, { type PlaceSpotRefusal, type PlaceSpotRequest } from '@/components/workspace/PlaceSpotDialog.vue'
import RenameSpotDialog from '@/components/workspace/RenameSpotDialog.vue'
import RemoveSpotDialog from '@/components/workspace/RemoveSpotDialog.vue'
import { placeForCaret, placeForControl, placeForSelection, placementsForLine, type AnchorLine } from '@/workspace/anchors'
import { axe } from '@/test/axe'

const LINES: AnchorLine[] = [
  { nodeId: 'p0', anchorTextHash: 'h0', text: 'Application form', controls: [], where: null, besideText: null },
  { nodeId: 'p1', anchorTextHash: 'h1', text: 'Company: ________', controls: [], where: null, besideText: null },
  { nodeId: 'p2', anchorTextHash: 'h2', text: 'Date of birth:', controls: [], where: null, besideText: null },
  { nodeId: 'tbl3/row0/cell1/p0', anchorTextHash: 'h3', text: '', controls: [], where: 'Table 1, row 1, column 2', besideText: 'Phone' },
]

const mounted: VueWrapper[] = []
let opener: HTMLButtonElement

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  document.body.innerHTML = ''
})

async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function withOpener(): HTMLButtonElement {
  opener = document.createElement('button')
  opener.textContent = 'Add a fill spot'
  document.body.append(opener)
  opener.focus()
  return opener
}

function active(): Element | null {
  return document.activeElement
}

async function key(target: Element, name: string): Promise<void> {
  target.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }))
  await flush()
}

/** The small line over the heading, with the hidden words that make it read as a sentence left out of the way. */
function caption(wrapper: VueWrapper): string {
  return wrapper.get('.dialog__subtitle').text().replace(/\s+/g, ' ')
}

/** How many bars the line under the heading has, and how many of them are filled. */
function bars(wrapper: VueWrapper): { total: number; reached: number } {
  return { total: wrapper.findAll('.spot-dialog__bar').length, reached: wrapper.findAll('.spot-dialog__bar--reached').length }
}

function buttonNamed(wrapper: VueWrapper, name: string) {
  const found = wrapper.findAll('button').find((button) => button.text().replace(/\s+/g, ' ').trim() === name)
  if (!found) throw new Error(`No button "${name}": ${wrapper.findAll('button').map((button) => button.text()).join(' | ')}`)
  return found
}

type PlaceExposed = { openAtLines: () => void; openAtPlace: (line: AnchorLine, place: ReturnType<typeof placeForCaret>, returnTo?: HTMLElement | null) => void }

function mountPlace(send: (request: PlaceSpotRequest) => Promise<PlaceSpotRefusal | null>, lines: AnchorLine[] = LINES) {
  const wrapper = mount(PlaceSpotDialog, { props: { lines, send }, attachTo: document.body })
  mounted.push(wrapper)
  return { wrapper, exposed: wrapper.vm as unknown as PlaceExposed }
}

describe('adding a fill spot from the keyboard', () => {
  it('goes paragraph, place, name, with focus on each step and the keys a list takes', async () => {
    withOpener()
    const send = vi.fn(async () => null)
    const { wrapper, exposed } = mountPlace(send)
    exposed.openAtLines()
    await flush()

    expect(caption(wrapper)).toContain('Add a fill spot')
    expect(caption(wrapper)).toContain('Step 1 of 3')
    expect(wrapper.get('h2').text()).toBe('Which paragraph?')
    expect(bars(wrapper)).toEqual({ total: 3, reached: 1 })
    const filter = wrapper.get('input[type="search"]')
    expect(active()).toBe(filter.element)
    const list = wrapper.get('[role="listbox"]')
    expect(list.findAll('[role="option"]').map((option) => option.text())).toEqual([
      'Application form',
      'Company: ________',
      'Date of birth:',
      '(empty paragraph)Table 1, row 1, column 2',
    ])
    expect(await axe(wrapper.element)).toHaveNoViolations()

    await filter.setValue('comp')
    expect(list.findAll('[role="option"]')).toHaveLength(1)
    await filter.setValue('')
    await key(filter.element, 'ArrowDown')
    expect(active()).toBe(list.element)
    const selected = () => list.find('[aria-selected="true"]')
    // The line the filter left chosen stays chosen once the filter is cleared.
    expect(selected().text()).toBe('Company: ________')
    await key(list.element, 'ArrowDown')
    expect(selected().text()).toBe('Date of birth:')
    expect(list.attributes('aria-activedescendant')).toBe(selected().attributes('id'))
    await key(list.element, 'End')
    expect(selected().text()).toContain('(empty paragraph)')
    await key(list.element, 'Home')
    await key(list.element, 'ArrowDown')
    await key(list.element, 'Enter')

    expect(wrapper.get('h2').text()).toBe('Where in the paragraph?')
    expect(caption(wrapper)).toContain('Step 2 of 3')
    expect(bars(wrapper)).toEqual({ total: 3, reached: 2 })
    expect(wrapper.get('[role="radiogroup"]').attributes('aria-label')).toBe('Where in the paragraph?')
    const radios = wrapper.findAll('input[type="radio"]')
    expect(radios.map((radio) => wrapper.get(`label[for="${radio.attributes('id')}"]`).text())).toEqual(["Replace '________'", 'At the end of the paragraph'])
    expect(active()).toBe(radios[0]!.element)
    expect(await axe(wrapper.element)).toHaveNoViolations()
    await buttonNamed(wrapper, 'Next').trigger('click')
    await flush()

    const name = wrapper.get('input[type="text"]')
    expect(active()).toBe(name.element)
    expect((name.element as HTMLInputElement).value).toBe('Company')
    expect(wrapper.get('h2').text()).toBe('What goes here?')
    expect(caption(wrapper)).toContain('Step 3 of 3')
    expect(bars(wrapper)).toEqual({ total: 3, reached: 3 })
    // The words around the place with the new spot drawn in, then the same place said in words, with the paragraph read out unseen.
    expect(wrapper.get('.place-spot__preview-line').text()).toBe('Company: [Company]')
    expect(wrapper.get('.place-spot__where-words').text()).toBe(
      'Where it goes: Replace \u201c____\u201d after \u201cCompany:\u201d. The paragraph reads "Company: ________".',
    )
    expect(await axe(wrapper.element)).toHaveNoViolations()

    await wrapper.get('form').trigger('submit')
    await flush()
    expect(send).toHaveBeenCalledWith({ line: LINES[1], place: expect.objectContaining({ placement: 'REPLACE', start: 9, end: 17 }), label: 'Company', type: 'TEXT' })
    expect(wrapper.emitted('added')).toHaveLength(1)
    // The page moves focus to the new spot; the dialog does not hand it back to its opener.
    expect(active()).not.toBe(opener)
    expect(wrapper.find('dialog').attributes('open')).toBeUndefined()
  })

  it('says so when no paragraph has the words typed, or when the form has none a spot can go in', async () => {
    const first = mountPlace(vi.fn(async () => null))
    first.exposed.openAtLines()
    await flush()
    await first.wrapper.get('input[type="search"]').setValue('no such words')
    expect(first.wrapper.find('[role="listbox"]').exists()).toBe(false)
    expect(first.wrapper.get('.field-hint[role="status"]').text()).toBe('No paragraph has those words.')
    // With nothing chosen, Next does nothing and says it cannot.
    expect(buttonNamed(first.wrapper, 'Next').attributes('aria-disabled')).toBe('true')
    await buttonNamed(first.wrapper, 'Next').trigger('click')
    expect(first.wrapper.get('h2').text()).toBe('Which paragraph?')

    const second = mountPlace(vi.fn(async () => null), [])
    second.exposed.openAtLines()
    await flush()
    expect(second.wrapper.get('.field-hint[role="status"]').text()).toBe('This form has no paragraph a fill spot can go in.')
  })

  it('offers a date for a name about a date, and names an empty line in a table by the cell beside it', async () => {
    const send = vi.fn(async () => null)
    const { wrapper, exposed } = mountPlace(send)
    exposed.openAtLines()
    await flush()
    await wrapper.get('input[type="search"]').setValue('table 1')
    await buttonNamed(wrapper, 'Next').trigger('click')
    await buttonNamed(wrapper, 'Next').trigger('click')
    await flush()
    expect((wrapper.get('input[type="text"]').element as HTMLInputElement).value).toBe('Phone')
    expect(wrapper.get('.place-spot__where-words').text()).toBe('Where it goes: In this empty paragraph.')
    // An empty paragraph has no words to read out around the place.
    expect(wrapper.get('.place-spot__around').text()).toBe('')

    await buttonNamed(wrapper, 'Back').trigger('click')
    await buttonNamed(wrapper, 'Back').trigger('click')
    await wrapper.get('input[type="search"]').setValue('birth')
    await buttonNamed(wrapper, 'Next').trigger('click')
    await buttonNamed(wrapper, 'Next').trigger('click')
    await flush()
    expect((wrapper.get('input[type="text"]').element as HTMLInputElement).value).toBe('Date of birth')
    expect((wrapper.get('input[value="DATE"]').element as HTMLInputElement).checked).toBe(true)
  })

  it('asks for a name before sending anything', async () => {
    const send = vi.fn(async () => null)
    const { wrapper, exposed } = mountPlace(send)
    exposed.openAtLines()
    await flush()
    await buttonNamed(wrapper, 'Next').trigger('click')
    await buttonNamed(wrapper, 'Next').trigger('click')
    await flush()
    const name = wrapper.get('input[type="text"]')
    await name.setValue('   ')
    await wrapper.get('form').trigger('submit')
    await flush()

    expect(send).not.toHaveBeenCalled()
    expect(name.attributes('aria-invalid')).toBe('true')
    // Focus was on the name already, so moving it there says nothing: the error is an alert of its own.
    expect(wrapper.get('[role="alert"]').text()).toBe('Give the fill spot a name.')
    expect(active()).toBe(name.element)
    expect(name.attributes('aria-describedby')).toContain(wrapper.get('.field-error').attributes('id'))
  })

  it('says it is working while the change is made, and why it was refused, staying open', async () => {
    let finish: (refusal: PlaceSpotRefusal | null) => void = () => undefined
    const send = vi.fn(() => new Promise<PlaceSpotRefusal | null>((resolve) => (finish = resolve)))
    const { wrapper, exposed } = mountPlace(send)
    exposed.openAtPlace(LINES[1]!, placeForCaret(LINES[1]!, 12))
    await flush()
    await wrapper.get('form').trigger('submit')
    await flush()

    expect(wrapper.get('[role="status"]').text()).toBe('Adding the fill spot\u2026 Brownie is checking the form still prints correctly.')
    expect(buttonNamed(wrapper, 'Adding\u2026').attributes('aria-disabled')).toBe('true')
    // A turning ring beside the words shows it is still going; it is not read out, the words are.
    expect(wrapper.get('[role="status"] .activity-indicator').attributes('aria-hidden')).toBe('true')
    // Escape waits for the change.
    await key(wrapper.get('dialog').element, 'Escape')
    expect(wrapper.find('dialog').attributes('open')).toBeDefined()

    finish({ message: 'Brownie cannot put a fill spot inside a link. Choose a place next to it.', choosePlaceAgain: false })
    await flush()
    expect(wrapper.get('[role="alert"]').text()).toBe('Brownie cannot put a fill spot inside a link. Choose a place next to it.')
    expect(wrapper.get('[role="status"]').text()).toBe('')
    expect(wrapper.find('[role="status"] .activity-indicator').exists()).toBe(false)
    expect(wrapper.find('dialog').attributes('open')).toBeDefined()
    expect(wrapper.emitted('added')).toBeUndefined()
  })

  it('goes back to the places in the line when the page changed under it', async () => {
    const send = vi.fn(async () => ({ message: 'The page changed; select the place again.', choosePlaceAgain: true }))
    const { wrapper, exposed } = mountPlace(send)
    exposed.openAtPlace(LINES[1]!, placeForCaret(LINES[1]!, 12))
    await flush()
    await wrapper.get('form').trigger('submit')
    await flush()

    expect(wrapper.get('h2').text()).toBe('Where in the paragraph?')
    expect(wrapper.get('[role="alert"]').text()).toBe('The page changed; select the place again.')
  })
})

describe('adding a fill spot at a place chosen on the page', () => {
  it('opens at the name, shows the paragraph with the place marked, and can choose another place in it', async () => {
    const returnTo = withOpener()
    const send = vi.fn(async () => null)
    const line = { ...LINES[0]!, text: 'Reference N/A here' }
    const { wrapper, exposed } = mountPlace(send, [line, ...LINES.slice(1)])
    exposed.openAtPlace(line, placeForSelection(line, 10, 13), returnTo)
    await flush()

    // The person chose the paragraph and the place on the page, which stand for the first two steps.
    expect(caption(wrapper)).toContain('Step 3 of 3')
    expect(active()).toBe(wrapper.get('input[type="text"]').element)
    expect(wrapper.get('.place-spot__marker').text()).toBe('[Fill spot]')
    expect(wrapper.get('.place-spot__preview-line').text()).toBe('Reference [Fill spot] here')
    expect(wrapper.get('.place-spot__preview').attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('.place-spot__where-words').text()).toContain('Replace \u201cN/A\u201d after \u201cReference\u201d')
    // The preview is not read out, so a screen reader is told the words around the place instead.
    expect(wrapper.get('.place-spot__around').text()).toBe('The paragraph reads "Reference N/A here".')
    await wrapper.get('input[type="text"]').setValue('Reference')
    expect(wrapper.get('.place-spot__marker').text()).toBe('[Reference]')
    expect(await axe(wrapper.element)).toHaveNoViolations()

    await buttonNamed(wrapper, 'Choose another place').trigger('click')
    await flush()
    const labels = wrapper.findAll('input[type="radio"]').map((radio) => wrapper.get(`label[for="${radio.attributes('id')}"]`).text())
    expect(labels).toEqual(["Replace 'N/A'", 'At the end of the paragraph'])
    // No way back to a list of lines the person never saw.
    expect(wrapper.findAll('button').some((button) => button.text() === 'Back')).toBe(false)

    await buttonNamed(wrapper, 'Cancel').trigger('click')
    await flush()
    expect(active()).toBe(returnTo)
  })
})

describe('the words around a place in a long paragraph', () => {
  const LONG: AnchorLine = {
    nodeId: 'p0',
    anchorTextHash: 'h0',
    text:
      'Please fill in this form and return it to the garden office. Membership runs for one year from the date it is ' +
      'approved, and it can be renewed at any time before it ends.',
    controls: [],
    where: null,
    besideText: null,
  }

  it('shows about forty characters each side of the place, cut at words, instead of the whole paragraph', async () => {
    const { wrapper, exposed } = mountPlace(vi.fn(async () => null), [LONG])
    const start = LONG.text.indexOf('garden office')
    exposed.openAtPlace(LONG, placeForSelection(LONG, start, start + 'garden office'.length), withOpener())
    await flush()

    const [before, , after] = wrapper.get('.place-spot__preview-line').findAll('span').map((part) => part.element.textContent)
    expect(before).toBe('\u2026fill in this form and return it to the ')
    expect(after).toBe('. Membership runs for one year from the\u2026')
    // What the spot takes the place of is said in the row above, not drawn in the preview.
    expect(wrapper.get('.place-spot__where-words').text()).toContain('Replace \u201cgarden office\u201d after \u201cit to the\u201d')
    expect(wrapper.text()).not.toContain('renewed at any time')
    expect(wrapper.get('.place-spot__around').text()).toBe(
      'The paragraph reads "\u2026fill in this form and return it to the garden office. Membership runs for one year from the\u2026".',
    )
  })

  it('says a long blank short, and quotes only the last few words before it, in the row that says where the spot goes', async () => {
    const blank: AnchorLine = {
      nodeId: 'p9',
      anchorTextHash: 'h9',
      text: `Name of the organisation that is making the request today: ${'_'.repeat(30)}`,
      controls: [],
      where: null,
      besideText: null,
    }
    const { wrapper, exposed } = mountPlace(vi.fn(async () => null), [blank])
    const start = blank.text.indexOf('_')
    exposed.openAtPlace(blank, placeForCaret(blank, start + 3), withOpener())
    await flush()

    // Thirty underscores read as a blank, not as thirty characters; only the last three words before it are quoted.
    expect(wrapper.get('.place-spot__where-words').text()).toContain('Replace \u201c____\u201d after \u201cthe request today:\u201d')
  })

  it('keeps Cancel and "Add the fill spot" together at the end of the foot, apart from the way back at its start', async () => {
    const { wrapper, exposed } = mountPlace(vi.fn(async () => null), [LONG])
    exposed.openAtPlace(LONG, placeForCaret(LONG, 5), withOpener())
    await flush()

    const foot = wrapper.get('.dialog__foot')
    expect(foot.findAll('button').map((button) => button.text())).toEqual(['Back', 'Cancel', 'Add the fill spot'])
    // The way back is the one button pushed to the start; the other two are one block, so a narrow foot never splits them.
    expect(foot.element.firstElementChild?.textContent?.trim()).toBe('Back')
    expect(foot.get('.place-spot__back').text()).toBe('Back')
    expect(wrapper.get('.place-spot__finish').findAll('button').map((button) => button.text())).toEqual(['Cancel', 'Add the fill spot'])
  })
})

describe('renaming a fill spot', () => {
  type Exposed = { open: (label: string, returnTo?: HTMLElement | null) => void }

  it('starts from the current name, refuses the same name, and gives focus back once renamed', async () => {
    const returnTo = withOpener()
    const send = vi.fn(async () => null)
    const wrapper = mount(RenameSpotDialog, { props: { send }, attachTo: document.body })
    mounted.push(wrapper)
    ;(wrapper.vm as unknown as Exposed).open('Company', returnTo)
    await flush()

    expect(wrapper.get('h2').text()).toBe('Rename Company')
    expect(caption(wrapper)).toBe('Rename a fill spot')
    // One step: one bar, filled; and the heading alone names the dialog, since the caption adds nothing to it.
    expect(bars(wrapper)).toEqual({ total: 1, reached: 1 })
    expect(wrapper.get('dialog').attributes('aria-labelledby')).toBe(wrapper.get('h2').attributes('id'))
    const input = wrapper.get('input')
    expect(active()).toBe(input.element)
    expect((input.element as HTMLInputElement).value).toBe('Company')
    expect(await axe(wrapper.element)).toHaveNoViolations()
    await wrapper.get('form').trigger('submit')
    await flush()
    expect(send).not.toHaveBeenCalled()
    // Enter in the name, which has focus already: only an alert says why the dialog stayed open.
    expect(wrapper.get('[role="alert"]').text()).toBe('That is its name already. Type a new name, or choose Cancel.')
    expect(active()).toBe(input.element)

    await input.setValue('  Employer  ')
    // The Rename button sits in the foot, outside the form, and still submits it.
    await buttonNamed(wrapper, 'Rename').trigger('click')
    await flush()
    expect(send).toHaveBeenCalledWith('Employer')
    expect(wrapper.emitted('renamed')).toEqual([['Employer']])
    expect(active()).toBe(returnTo)
  })

  it('says why a name was refused and stays open', async () => {
    const send = vi.fn(async () => 'Unlock Company first; its value would be lost.')
    const wrapper = mount(RenameSpotDialog, { props: { send }, attachTo: document.body })
    mounted.push(wrapper)
    ;(wrapper.vm as unknown as Exposed).open('Company')
    await flush()
    await wrapper.get('input').setValue('a'.repeat(61))
    await wrapper.get('form').trigger('submit')
    await flush()
    expect(wrapper.text()).toContain('Keep the name to 60 characters or fewer.')
    await wrapper.get('input').setValue('Employer')
    await wrapper.get('form').trigger('submit')
    await flush()
    expect(wrapper.get('[role="alert"]').text()).toBe('Unlock Company first; its value would be lost.')
    expect(wrapper.find('dialog').attributes('open')).toBeDefined()
  })
})

describe('removing a fill spot', () => {
  type Exposed = { open: (label: string, hasValue: boolean, fromTheForm: boolean, returnTo?: HTMLElement | null) => void }

  it('asks first, with the safe answer focused, and says the form keeps its own box', async () => {
    withOpener()
    const send = vi.fn(async () => null)
    const wrapper = mount(RemoveSpotDialog, { props: { send }, attachTo: document.body })
    mounted.push(wrapper)
    ;(wrapper.vm as unknown as Exposed).open('Company', true, true)
    await flush()

    expect(wrapper.get('h2').text()).toBe('Remove Company?')
    expect(caption(wrapper)).toBe('Remove a fill spot')
    // Taking something away is red from the line under the heading to the button that does it.
    expect(wrapper.get('.spot-dialog__progress').classes()).toContain('spot-dialog__progress--danger')
    expect(buttonNamed(wrapper, 'Remove the fill spot').classes()).toContain('button--danger')
    const question = wrapper.findAll('.spot-remove__line').map((line) => line.text())
    expect(question).toEqual(['Remove the fill spot Company? Its value stays in the version history.', 'The form keeps its own box; Brownie just stops filling it.'])
    expect(wrapper.get('dialog').attributes('aria-describedby')).toBe(wrapper.get('.spot-remove__line').element.parentElement!.id)
    expect(active()).toBe(buttonNamed(wrapper, 'Cancel').element)
    expect(await axe(wrapper.element)).toHaveNoViolations()

    await buttonNamed(wrapper, 'Remove the fill spot').trigger('click')
    await flush()
    expect(send).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('removed')).toHaveLength(1)
    // What opened it went with the spot; the page decides where focus goes.
    expect(active()).not.toBe(opener)
  })

  it('says why the spot was not removed', async () => {
    const send = vi.fn(async () => 'Brownie could not remove the fill spot, because the form would not print correctly without it. Nothing was changed.')
    const wrapper = mount(RemoveSpotDialog, { props: { send }, attachTo: document.body })
    mounted.push(wrapper)
    ;(wrapper.vm as unknown as Exposed).open('Fax', false, false)
    await flush()
    expect(wrapper.findAll('.spot-remove__line').map((line) => line.text())).toEqual(['Remove the fill spot Fax?'])
    await buttonNamed(wrapper, 'Remove the fill spot').trigger('click')
    await flush()
    expect(wrapper.get('[role="alert"]').text()).toContain('Nothing was changed.')
    expect(wrapper.emitted('removed')).toBeUndefined()
  })
})

describe('the frame the three dialogs share', () => {
  it('names the dialog by its caption and heading when it has steps, and fills one more bar at each step', async () => {
    const { wrapper, exposed } = mountPlace(vi.fn(async () => null))
    exposed.openAtLines()
    await flush()

    const labelledBy = () => (wrapper.get('dialog').attributes('aria-labelledby') ?? '').split(' ')
    expect(labelledBy()).toEqual([wrapper.get('.dialog__subtitle').attributes('id'), wrapper.get('h2').attributes('id')])
    // The bars only show the step, which the caption already says in words, so they are not read out.
    expect(wrapper.get('.spot-dialog__progress').attributes('aria-hidden')).toBe('true')
    expect(bars(wrapper).reached).toBe(1)
    await buttonNamed(wrapper, 'Next').trigger('click')
    expect(bars(wrapper).reached).toBe(2)
    await buttonNamed(wrapper, 'Next').trigger('click')
    expect(bars(wrapper).reached).toBe(3)
    await buttonNamed(wrapper, 'Back').trigger('click')
    expect(bars(wrapper).reached).toBe(2)
  })

  it('closes on Escape when nothing is being changed, and gives focus back to what opened it', async () => {
    const returnTo = withOpener()
    const { wrapper, exposed } = mountPlace(vi.fn(async () => null))
    exposed.openAtLines()
    await flush()
    expect(wrapper.find('dialog').attributes('open')).toBeDefined()

    await key(wrapper.get('dialog').element, 'Escape')
    expect(wrapper.find('dialog').attributes('open')).toBeUndefined()
    expect(active()).toBe(returnTo)
  })

  it('closes from its Close button, which waits while a change is being made', async () => {
    let finish: (refusal: PlaceSpotRefusal | null) => void = () => undefined
    const send = vi.fn(() => new Promise<PlaceSpotRefusal | null>((resolve) => (finish = resolve)))
    const { wrapper, exposed } = mountPlace(send)
    exposed.openAtPlace(LINES[1]!, placeForCaret(LINES[1]!, 12))
    await flush()
    const close = buttonNamed(wrapper, 'Close')
    expect(close.attributes('aria-disabled')).toBeUndefined()

    await wrapper.get('form').trigger('submit')
    await flush()
    expect(close.attributes('aria-disabled')).toBe('true')
    await close.trigger('click')
    expect(wrapper.find('dialog').attributes('open')).toBeDefined()

    finish({ message: 'Try another place.', choosePlaceAgain: false })
    await flush()
    expect(close.attributes('aria-disabled')).toBeUndefined()
    await close.trigger('click')
    expect(wrapper.find('dialog').attributes('open')).toBeUndefined()
  })
})

describe('the last step of adding a fill spot', () => {
  it('says where the spot goes beside a way to choose another place, and Back and that way both lead to the places', async () => {
    const { wrapper, exposed } = mountPlace(vi.fn(async () => null))
    exposed.openAtLines()
    await flush()
    await buttonNamed(wrapper, 'Next').trigger('click')
    await buttonNamed(wrapper, 'Next').trigger('click')
    await flush()
    expect(wrapper.get('h2').text()).toBe('What goes here?')

    await buttonNamed(wrapper, 'Choose another place').trigger('click')
    await flush()
    expect(wrapper.get('h2').text()).toBe('Where in the paragraph?')
    // Back to the list of paragraphs is still there, since this person did come by way of the list.
    expect(wrapper.findAll('button').some((button) => button.text() === 'Back')).toBe(true)
    await buttonNamed(wrapper, 'Next').trigger('click')
    await buttonNamed(wrapper, 'Back').trigger('click')
    await flush()
    expect(wrapper.get('h2').text()).toBe('Where in the paragraph?')
  })

  it.each([
    ['a place after a label', { text: 'Date of birth:' }, (line: AnchorLine) => placementsForLine(line)[0]!, 'After \u201cDate of birth:\u201d'],
    ['a point inside the words', { text: 'Please sign here today' }, (line: AnchorLine) => placeForCaret(line, 11), 'After \u201cPlease sign\u201d'],
    ['the start of the paragraph', { text: 'Application form' }, (line: AnchorLine) => placeForCaret(line, 0), 'At the start of the paragraph'],
    ['the end of the paragraph', { text: 'Application form' }, (line: AnchorLine) => placeForCaret(line, 16), 'At the end of the paragraph'],
    ['a paragraph that is only a blank', { text: '________________' }, (line: AnchorLine) => placementsForLine(line).at(-1)!, 'Instead of the whole paragraph'],
    [
      'the words of a control the form left unnamed',
      { text: 'Date: ', controls: [{ controlNodeId: 'c1', text: '[Pick a date]', at: 6 }] },
      (line: AnchorLine) => placeForControl(line, 'c1')!,
      'Replace \u201c[Pick a date]\u201d after \u201cDate:\u201d',
    ],
  ])('says where the spot goes for %s', async (_what, fields, choose, words) => {
    const line: AnchorLine = { nodeId: 'p7', anchorTextHash: 'h7', controls: [], where: null, besideText: null, ...fields }
    const { wrapper, exposed } = mountPlace(vi.fn(async () => null), [line])
    exposed.openAtPlace(line, choose(line), withOpener())
    await flush()

    expect(wrapper.get('.place-spot__where-words').text()).toContain(`Where it goes: ${words}.`)
  })

  it('offers Text and Date as cards that say what each is, and sends the one chosen', async () => {
    const send = vi.fn(async () => null)
    const { wrapper, exposed } = mountPlace(send)
    exposed.openAtPlace(LINES[1]!, placeForCaret(LINES[1]!, 12))
    await flush()

    const text = wrapper.get('input[value="TEXT"]')
    const date = wrapper.get('input[value="DATE"]')
    expect((text.element as HTMLInputElement).checked).toBe(true)
    // Each radio is named by its title alone and described by the line under it.
    const nameOf = (radio: typeof text) => wrapper.get(`#${radio.attributes('aria-labelledby')}`).text()
    const hintOf = (radio: typeof text) => wrapper.get(`#${radio.attributes('aria-describedby')}`).text()
    expect(nameOf(text)).toBe('Text')
    expect(hintOf(text)).toBe('Any words on one line.')
    expect(nameOf(date)).toBe('Date')
    expect(hintOf(date)).toBe('A date picker. Reads like October 13, 2026.')

    // The whole card is its label, so pressing anywhere on it chooses it.
    await wrapper.get(`label[for="${date.attributes('id')}"]`).trigger('click')
    await date.setValue(true)
    expect((date.element as HTMLInputElement).checked).toBe(true)
    await wrapper.get('form').trigger('submit')
    await flush()
    expect(send).toHaveBeenCalledWith(expect.objectContaining({ type: 'DATE' }))
  })

  it('is submitted by the button in the foot, which sits outside the form', async () => {
    const send = vi.fn(async () => null)
    const { wrapper, exposed } = mountPlace(send)
    exposed.openAtPlace(LINES[1]!, placeForCaret(LINES[1]!, 12))
    await flush()

    const add = buttonNamed(wrapper, 'Add the fill spot')
    expect(add.attributes('type')).toBe('submit')
    expect(add.attributes('form')).toBe(wrapper.get('form').attributes('id'))
    expect(wrapper.get('form').element.contains(add.element)).toBe(false)
    await add.trigger('click')
    await flush()
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('marks the name as wrong and says why when it is too long, and takes the complaint back once the name is typed again', async () => {
    const send = vi.fn(async () => null)
    const { wrapper, exposed } = mountPlace(send)
    exposed.openAtPlace(LINES[1]!, placeForCaret(LINES[1]!, 12))
    await flush()
    const name = wrapper.get('input[type="text"]')
    await name.setValue('a'.repeat(61))
    await wrapper.get('form').trigger('submit')
    await flush()

    expect(send).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toBe('Keep the name to 60 characters or fewer.')
    expect(name.attributes('aria-invalid')).toBe('true')
    // Typing again clears the complaint, and the new name shows in the preview as typed.
    await name.setValue('Employer')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(name.attributes('aria-invalid')).toBeUndefined()
    expect(wrapper.get('.place-spot__marker').text()).toBe('[Employer]')
  })
})
