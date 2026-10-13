import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PdfSpotDialog from '@/components/workspace/PdfSpotDialog.vue'
import type { PdfPageModel, PdfSpotRequest, PdfSpotResult } from '@/workspace/pdfPage'
import { axe } from '@/test/axe'

function page(pageNumber: number, lines: { index: number; text: string }[], rotation: 0 | 90 = 0): PdfPageModel {
  return {
    pageNumber,
    width: 612,
    height: 792,
    rotation,
    shownWidth: rotation === 0 ? 612 : 792,
    shownHeight: rotation === 0 ? 792 : 612,
    hasText: lines.length > 0,
    lines: lines.map((line) => ({ ...line, x: 72, y: 100 + line.index * 20, w: 200, h: 12 })),
    spots: [],
  }
}

const pages = [
  page(1, [
    { index: 0, text: 'Application for membership' },
    { index: 1, text: 'Company:' },
    { index: 2, text: 'Tên người nộp đơn:' },
  ]),
  page(2, []),
]

let wrapper: VueWrapper | null = null

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

function mountDialog(send = vi.fn(async (): Promise<PdfSpotResult> => ({ ok: true, focusId: null }))) {
  const suggest = vi.fn(async () => ({ box: { x: 130, y: 118, width: 180, height: 14 }, style: { font: 'SERIF' as const, bold: false, sizePt: 10 }, labelGuess: 'Company' }))
  const opener = document.createElement('button')
  opener.textContent = 'Opener'
  document.body.appendChild(opener)
  opener.focus()
  wrapper = mount(PdfSpotDialog, { props: { pages, suggest, send }, attachTo: document.body })
  const dialog = wrapper.vm as unknown as { open: (mode: unknown, beside?: HTMLElement | null) => void; close: (focusId?: string | null) => void }
  return { wrapper, dialog, send, suggest, opener }
}

function sent(send: ReturnType<typeof vi.fn>): PdfSpotRequest {
  return send.mock.calls[0]![0] as PdfSpotRequest
}

describe('PdfSpotDialog', () => {
  it('names a drawn box, with smaller text to fit chosen from the start, and sends it as a new box', async () => {
    const { wrapper, dialog, send } = mountDialog()
    dialog.open({ kind: 'add', pageNumber: 1, box: { x: 72, y: 300, width: 150, height: 16 }, style: null, labelGuess: 'Tên công ty' })
    await flushPromises()

    expect(wrapper.find('h2').text()).toBe('Name the fill spot')
    const name = wrapper.get<HTMLInputElement>('input[type="text"]')
    expect(name.element.value).toBe('Tên công ty')
    expect(document.activeElement).toBe(name.element)
    expect(wrapper.get<HTMLInputElement>('input[value="SHRINK_TO_FIT"]').element.checked).toBe(true)
    expect(wrapper.text()).toContain('Make the text smaller to fit (down to 6 pt)')
    expect(wrapper.text()).toContain('Stop me before export')
    expect((await axe(document.body)).violations).toEqual([])

    await wrapper.get('input[value="DATE"]').setValue(true)
    await wrapper.get('input[type="number"]').setValue('9')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(sent(send)).toEqual({
      kind: 'add',
      label: 'Tên công ty',
      changes: [
        {
          kind: 'ADD_BOX',
          pageNumber: 1,
          box: { x: 72, y: 300, width: 150, height: 16 },
          label: 'Tên công ty',
          type: 'DATE',
          style: { font: 'SANS', bold: false, sizePt: 9 },
          overflow: 'SHRINK_TO_FIT',
        },
      ],
    })
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('takes the keyboard from a page and a line to a box beside it, then to its name', async () => {
    const { wrapper, dialog, send, suggest } = mountDialog()
    dialog.open({ kind: 'add', pageNumber: null, box: null, style: null, labelGuess: null })
    await flushPromises()

    expect(wrapper.find('h2').text()).toBe('Add a fill spot')
    const pageSelect = wrapper.get<HTMLSelectElement>('select:not([size])')
    expect(document.activeElement).toBe(pageSelect.element)
    expect(pageSelect.findAll('option').map((option) => option.text())).toEqual(['Page 1 of 2', 'Page 2 of 2'])

    // Nothing chosen yet: it says what is missing rather than guessing.
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role="alert"]').text()).toBe('Choose the line the fill spot goes next to.')

    await wrapper.get('input[type="search"]').setValue('company')
    expect(wrapper.findAll('select[size] option').map((option) => option.text())).toEqual(['Company:'])
    await wrapper.get('select[size]').setValue('1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(suggest).toHaveBeenCalledWith(1, { lineIndex: 1 })
    expect(wrapper.find('h2').text()).toBe('Name the fill spot')
    expect(wrapper.text()).toContain('next to “Company:”')
    expect(wrapper.get<HTMLInputElement>('input[type="text"]').element.value).toBe('Company')
    expect(wrapper.get<HTMLInputElement>('input[type="number"]').element.value).toBe('10')
    expect(wrapper.text()).toContain('Back')

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(sent(send).changes[0]).toMatchObject({
      kind: 'ADD_BOX',
      pageNumber: 1,
      box: { x: 130, y: 118, width: 180, height: 14 },
      label: 'Company',
      type: 'TEXT',
      style: { font: 'SERIF', bold: false, sizePt: 10 },
    })
  })

  it('puts a box near the top of a scanned page, which has no lines to choose', async () => {
    const { wrapper, dialog, suggest } = mountDialog()
    dialog.open({ kind: 'add', pageNumber: null, box: null, style: null, labelGuess: null })
    await flushPromises()
    await wrapper.get('select').setValue('2')
    expect(wrapper.text()).toContain('because it is a scan')
    expect(wrapper.find('select[size]').exists()).toBe(false)

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(suggest).toHaveBeenCalledWith(2, { point: { x: 36, y: 36 } })
  })

  it('refuses a name that is not one, and keeps the dialog open with the reason a change was refused', async () => {
    const send = vi.fn(async (): Promise<PdfSpotResult> => ({ ok: false, message: 'That box covers another fill spot; move it a little.' }))
    const { wrapper, dialog } = mountDialog(send)
    dialog.open({ kind: 'add', pageNumber: 1, box: { x: 72, y: 300, width: 150, height: 16 }, style: null, labelGuess: null })
    await flushPromises()

    await wrapper.get('input[type="text"]').setValue('  ')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role="alert"]').text()).toBe('Give the fill spot a name of 1 to 60 characters, with at least one letter or number.')
    expect(send).not.toHaveBeenCalled()

    await wrapper.get('input[type="text"]').setValue('Signature date')
    await wrapper.get('input[type="number"]').setValue('100')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role="alert"]').text()).toBe('Choose a text size from 4 to 72 points.')

    await wrapper.get('input[type="number"]').setValue('11')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('That box covers another fill spot; move it a little.')
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('shows what it is doing while the change is checked, and moves focus to the new spot after', async () => {
    let finish: (result: PdfSpotResult) => void = () => {}
    const send = vi.fn(() => new Promise<PdfSpotResult>((resolve) => (finish = resolve)))
    const { wrapper, dialog } = mountDialog(send)
    const spot = document.createElement('textarea')
    spot.id = 'edit-company'
    document.body.appendChild(spot)
    dialog.open({ kind: 'add', pageNumber: 1, box: { x: 72, y: 300, width: 150, height: 16 }, style: null, labelGuess: 'Company' })
    await flushPromises()

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('Adding the fill spot… Brownie is checking the form still prints correctly.')

    finish({ ok: true, focusId: 'edit-company' })
    await flushPromises()
    expect(document.activeElement).toBe(spot)
  })

  it('changes the text size and what happens to long text', async () => {
    const { wrapper, dialog, send } = mountDialog()
    dialog.open({ kind: 'restyle', fieldId: 'company', label: 'Company', sizePt: 11, overflow: 'SHRINK_TO_FIT' })
    await flushPromises()

    expect(wrapper.find('h2').text()).toBe('Text size and overflow for Company')
    expect(wrapper.find('input[type="text"]').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('input[type="number"]').element)
    await wrapper.get('input[type="number"]').setValue('8.5')
    await wrapper.get('input[value="BLOCK"]').setValue(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(sent(send)).toEqual({
      kind: 'restyle',
      label: 'Company',
      changes: [{ kind: 'RESTYLE_BOX', fieldId: 'company', sizePt: 8.5, overflow: 'BLOCK' }],
    })
  })

  it('steps the text size a point at a time with the buttons beside it, within what the server accepts', async () => {
    const { wrapper, dialog, send } = mountDialog()
    dialog.open({ kind: 'restyle', fieldId: 'company', label: 'Company', sizePt: 10, overflow: 'SHRINK_TO_FIT' })
    await flushPromises()

    const size = wrapper.get<HTMLInputElement>('input[type="number"]')
    const smaller = wrapper.findAll('button').find((button) => button.text() === 'Decrease text size')!
    const larger = wrapper.findAll('button').find((button) => button.text() === 'Increase text size')!
    // Minus, the size and plus sit in that order, in one box beside the label that names the size.
    expect(smaller.element.nextElementSibling).toBe(size.element)
    expect(size.element.nextElementSibling).toBe(larger.element)
    expect(wrapper.get('label[for]').text()).toBe('Text size (points)')
    expect(wrapper.get('label[for]').attributes('for')).toBe(size.attributes('id'))

    // Focus stays on the button, so what the size became is said where the box beside it cannot be seen.
    const said = () => wrapper.findAll('[role="status"]').at(-1)!.text()
    await larger.trigger('click')
    expect(size.element.value).toBe('11')
    expect(said()).toBe('Text size 11 points')
    await smaller.trigger('click')
    await smaller.trigger('click')
    expect(size.element.value).toBe('9')
    expect(said()).toBe('Text size 9 points')
    // Half a point typed in is kept and stepped from.
    await size.setValue('8.5')
    await larger.trigger('click')
    expect(size.element.value).toBe('9.5')

    // At either end of what is accepted the button says so and stays where it is.
    await size.setValue('4')
    expect(smaller.attributes('aria-disabled')).toBe('true')
    await smaller.trigger('click')
    expect(size.element.value).toBe('4')
    await size.setValue('72')
    expect(larger.attributes('aria-disabled')).toBe('true')
    await larger.trigger('click')
    expect(size.element.value).toBe('72')
    // A blank size is stepped from the usual one.
    await size.setValue('')
    await larger.trigger('click')
    expect(size.element.value).toBe('12')

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(sent(send).changes).toEqual([{ kind: 'RESTYLE_BOX', fieldId: 'company', sizePt: 12, overflow: 'SHRINK_TO_FIT' }])
  })

  it('asks the size question in a card with nothing dimmed behind it, and the other questions in a dialog', async () => {
    const { wrapper, dialog } = mountDialog()
    dialog.open({ kind: 'restyle', fieldId: 'company', label: 'Company', sizePt: 10, overflow: 'SHRINK_TO_FIT' })
    await flushPromises()
    expect(wrapper.get('dialog').classes()).toContain('pdf-spot-dialog--popover')
    expect(wrapper.get('dialog').classes()).not.toContain('dialog')
    expect(wrapper.get('h2').classes()).toContain('pdf-spot-dialog__title')
    // Cancel before the answer, which is last: the order they are read in is the order they are seen in.
    expect(wrapper.findAll('.pdf-spot-dialog__actions button').map((button) => button.text())).toEqual(['Cancel', 'Save'])

    dialog.open({ kind: 'rename', fieldId: 'company', label: 'Company' })
    await flushPromises()
    expect(wrapper.get('dialog').classes()).toEqual(expect.arrayContaining(['dialog', 'pdf-spot-dialog']))
    expect(wrapper.get('dialog').classes()).not.toContain('pdf-spot-dialog--popover')
    expect(wrapper.get('h2').classes()).toContain('dialog__title')
    expect(wrapper.findAll('.dialog__foot button').map((button) => button.text())).toEqual(['Cancel', 'Rename'])
  })

  it('shows the two ways long text can go as round choices in one group, the first chosen', async () => {
    const { wrapper, dialog } = mountDialog()
    dialog.open({ kind: 'restyle', fieldId: 'company', label: 'Company', sizePt: 10, overflow: 'SHRINK_TO_FIT' })
    await flushPromises()

    const group = wrapper.get('fieldset')
    expect(group.get('legend').text()).toBe('If the text is too long:')
    const choices = group.findAll<HTMLInputElement>('input[type="radio"]')
    expect(choices.map((choice) => choice.element.value)).toEqual(['SHRINK_TO_FIT', 'BLOCK'])
    expect(choices.map((choice) => choice.element.checked)).toEqual([true, false])
    expect(group.findAll('label').map((label) => label.text())).toEqual(['Make the text smaller to fit (down to 6 pt)', 'Stop me before export'])
    await choices[1]!.setValue(true)
    expect(choices.map((choice) => choice.element.checked)).toEqual([false, true])
    expect((await axe(document.body)).violations).toEqual([])
  })

  it('hangs the size card below its box, level with the box’s right edge, and keeps it inside the window', async () => {
    const { wrapper, dialog } = mountDialog()
    const box = document.createElement('div')
    document.body.appendChild(box)
    const place = (left: number, top: number, width: number, height: number) =>
      (box.getBoundingClientRect = () => ({ left, top, right: left + width, bottom: top + height, width, height, x: left, y: top, toJSON: () => ({}) }) as DOMRect)
    vi.spyOn(HTMLDialogElement.prototype, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, right: 340, bottom: 264, width: 340, height: 264, x: 0, y: 0, toJSON: () => ({}) } as DOMRect)
    const restyle = { kind: 'restyle', fieldId: 'company', label: 'Company', sizePt: 10, overflow: 'SHRINK_TO_FIT' } as const
    const style = () => wrapper.get('dialog').attributes('style') ?? ''

    // A wide box well down the window: 14 below its foot, right edges level.
    place(139, 200, 504, 74)
    dialog.open(restyle, box)
    await flushPromises()
    expect(wrapper.get('dialog').classes()).toContain('pdf-spot-dialog--anchored')
    expect(style()).toContain('left: 303px')
    expect(style()).toContain('top: 288px')

    // No room below it: above it instead.
    dialog.close()
    place(139, 600, 504, 74)
    dialog.open(restyle, box)
    await flushPromises()
    expect(style()).toContain('top: 322px')

    // A box at the window's left edge: the card is kept inside the window.
    dialog.close()
    place(0, 100, 80, 16)
    dialog.open(restyle, box)
    await flushPromises()
    expect(style()).toContain('left: 12px')

    // A box that is not on screen, or none: the card stays in the middle of the window like any dialog.
    dialog.close()
    place(139, 5000, 504, 74)
    dialog.open(restyle, box)
    await flushPromises()
    expect(wrapper.get('dialog').classes()).not.toContain('pdf-spot-dialog--anchored')
    expect(wrapper.get('dialog').attributes('style')).toBeUndefined()
    dialog.close()
    dialog.open(restyle)
    await flushPromises()
    expect(wrapper.get('dialog').classes()).not.toContain('pdf-spot-dialog--anchored')
  })

  it('cancels the size question when the page behind it is clicked, but not the other questions, and not a press that began in the card', async () => {
    const { wrapper, dialog, send, opener } = mountDialog()
    const press = async (target: Element, type: 'pointerdown' | 'click') => {
      target.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true }))
      await flushPromises()
    }
    dialog.open({ kind: 'restyle', fieldId: 'company', label: 'Company', sizePt: 10, overflow: 'SHRINK_TO_FIT' })
    await flushPromises()

    // Selecting text in the card and letting go outside it is not a click on the page.
    await press(wrapper.get('h2').element, 'pointerdown')
    await press(wrapper.get('dialog').element, 'click')
    expect(wrapper.find('form').exists()).toBe(true)
    // A click inside the card does nothing to it.
    await press(wrapper.get('h2').element, 'pointerdown')
    await press(wrapper.get('h2').element, 'click')
    expect(wrapper.find('form').exists()).toBe(true)

    await press(wrapper.get('dialog').element, 'pointerdown')
    await press(wrapper.get('dialog').element, 'click')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(send).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(opener)

    dialog.open({ kind: 'rename', fieldId: 'company', label: 'Company' })
    await flushPromises()
    await press(wrapper.get('dialog').element, 'pointerdown')
    await press(wrapper.get('dialog').element, 'click')
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('renames a spot', async () => {
    const { wrapper, dialog, send } = mountDialog()
    dialog.open({ kind: 'rename', fieldId: 'company', label: 'Company' })
    await flushPromises()

    expect(wrapper.find('h2').text()).toBe('Rename Company')
    await wrapper.get('input[type="text"]').setValue('Company name')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(sent(send)).toEqual({ kind: 'rename', label: 'Company name', changes: [{ kind: 'RENAME', fieldId: 'company', label: 'Company name' }] })
  })

  it('asks before removing a spot, and says a form keeps its own box', async () => {
    const { wrapper, dialog, send } = mountDialog()
    dialog.open({ kind: 'remove', fieldId: 'company', label: 'Company', fromForm: true })
    await flushPromises()

    expect(wrapper.find('h2').text()).toBe('Remove the fill spot Company?')
    expect(document.activeElement).toBe(wrapper.find('h2').element)
    expect(wrapper.text()).toContain('Its value stays in the version history.')
    expect(wrapper.text()).toContain('The form keeps its own box; Brownie just stops filling it.')
    expect((await axe(document.body)).violations).toEqual([])

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(sent(send)).toEqual({ kind: 'remove', label: 'Company', changes: [{ kind: 'REMOVE', fieldId: 'company' }] })
  })

  it('cancels with Escape and gives focus back to where it came from', async () => {
    const { wrapper, dialog, send, opener } = mountDialog()
    dialog.open({ kind: 'rename', fieldId: 'company', label: 'Company' })
    await flushPromises()
    expect(document.activeElement).not.toBe(opener)

    await wrapper.get('dialog').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(document.activeElement).toBe(opener)
    expect(send).not.toHaveBeenCalled()
  })
})
