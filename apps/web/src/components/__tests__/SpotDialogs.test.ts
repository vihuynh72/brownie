import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import PlaceSpotDialog, { type PlaceSpotRefusal, type PlaceSpotRequest } from '@/components/workspace/PlaceSpotDialog.vue'
import RenameSpotDialog from '@/components/workspace/RenameSpotDialog.vue'
import RemoveSpotDialog from '@/components/workspace/RemoveSpotDialog.vue'
import { placeForCaret, placeForSelection, type AnchorLine } from '@/workspace/anchors'
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

    expect(wrapper.find('.place-spot__step').text()).toBe('Step 1 of 3')
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

    expect(wrapper.find('legend').text()).toBe('Where in the paragraph?')
    const radios = wrapper.findAll('input[type="radio"]')
    expect(radios.map((radio) => wrapper.get(`label[for="${radio.attributes('id')}"]`).text())).toEqual(["Replace '________'", 'At the end of the paragraph'])
    expect(active()).toBe(radios[0]!.element)
    expect(await axe(wrapper.element)).toHaveNoViolations()
    await buttonNamed(wrapper, 'Next').trigger('click')
    await flush()

    const name = wrapper.get('input[type="text"]')
    expect(active()).toBe(name.element)
    expect((name.element as HTMLInputElement).value).toBe('Company')
    expect(wrapper.get('.place-spot__preview').text()).toBe('Company: ________Company')
    expect(wrapper.text()).toContain('The fill spot goes in place of "________".')
    expect(await axe(wrapper.element)).toHaveNoViolations()

    await wrapper.get('form').trigger('submit')
    await flush()
    expect(send).toHaveBeenCalledWith({ line: LINES[1], place: expect.objectContaining({ placement: 'REPLACE', start: 9, end: 17 }), label: 'Company', type: 'TEXT' })
    expect(wrapper.emitted('added')).toHaveLength(1)
    // The page moves focus to the new spot; the dialog does not hand it back to its opener.
    expect(active()).not.toBe(opener)
    expect(wrapper.find('dialog').attributes('open')).toBeUndefined()
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
    expect(wrapper.text()).toContain('The fill spot goes in an empty paragraph.')
    // An empty paragraph has no words to read out around the place.
    expect(wrapper.get('.place-spot__step-body .visually-hidden').text()).toBe('')

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
    // Escape waits for the change.
    await key(wrapper.get('dialog').element, 'Escape')
    expect(wrapper.find('dialog').attributes('open')).toBeDefined()

    finish({ message: 'Brownie cannot put a fill spot inside a link. Choose a place next to it.', choosePlaceAgain: false })
    await flush()
    expect(wrapper.get('[role="alert"]').text()).toBe('Brownie cannot put a fill spot inside a link. Choose a place next to it.')
    expect(wrapper.get('[role="status"]').text()).toBe('')
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

    expect(wrapper.find('legend').text()).toBe('Where in the paragraph?')
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

    expect(wrapper.find('.place-spot__step').exists()).toBe(false)
    expect(active()).toBe(wrapper.get('input[type="text"]').element)
    expect(wrapper.get('.place-spot__covered').text()).toBe('N/A')
    expect(wrapper.get('.place-spot__marker').text()).toBe('Fill spot')
    expect(wrapper.get('.place-spot__preview').attributes('aria-hidden')).toBe('true')
    expect(wrapper.text()).toContain('The fill spot goes in place of "N/A".')
    // The preview is not read out, so a screen reader is told the words around the place instead.
    expect(wrapper.get('.place-spot__step-body .visually-hidden').text()).toBe('The paragraph reads "Reference N/A here".')
    await wrapper.get('input[type="text"]').setValue('Reference')
    expect(wrapper.get('.place-spot__marker').text()).toBe('Reference')
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

    const preview = wrapper.get('.place-spot__preview')
    expect(preview.get('.place-spot__covered').text()).toBe('garden office')
    const [before, , , after] = preview.findAll('span, del').map((part) => part.element.textContent)
    expect(before).toBe('\u2026fill in this form and return it to the ')
    expect(after).toBe('. Membership runs for one year from the\u2026')
    expect(wrapper.text()).not.toContain('renewed at any time')
    expect(wrapper.get('.place-spot__step-body .visually-hidden').text()).toBe(
      'The paragraph reads "\u2026fill in this form and return it to the garden office. Membership runs for one year from the\u2026".',
    )
  })

  it('keeps Cancel and "Add the fill spot" together, apart from the way back', async () => {
    const { wrapper, exposed } = mountPlace(vi.fn(async () => null), [LONG])
    exposed.openAtPlace(LONG, placeForCaret(LONG, 5), withOpener())
    await flush()

    const finish = wrapper.get('.place-spot__finish')
    expect(finish.findAll('button').map((button) => button.text())).toEqual(['Cancel', 'Add the fill spot'])
    expect(finish.element.parentElement?.firstElementChild?.textContent?.trim()).toBe('Choose another place')
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
    await wrapper.get('form').trigger('submit')
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
    const question = wrapper.findAll('.spot-change__line').map((line) => line.text())
    expect(question).toEqual(['Remove the fill spot Company? Its value stays in the version history.', 'The form keeps its own box; Brownie just stops filling it.'])
    expect(wrapper.get('dialog').attributes('aria-describedby')).toBe(wrapper.get('.spot-change__line').element.parentElement!.id)
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
    expect(wrapper.findAll('.spot-change__line').map((line) => line.text())).toEqual(['Remove the fill spot Fax?'])
    await buttonNamed(wrapper, 'Remove the fill spot').trigger('click')
    await flush()
    expect(wrapper.get('[role="alert"]').text()).toContain('Nothing was changed.')
    expect(wrapper.emitted('removed')).toBeUndefined()
  })
})
