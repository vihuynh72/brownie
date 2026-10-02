import { afterEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import DocumentPage from '@/components/workspace/DocumentPage.vue'
import FillSpot from '@/components/workspace/FillSpot.vue'
import { axe } from '@/test/axe'
import type {
  DocumentRevisionResponse,
  FieldStateResponse,
  TemplateLayoutBlockResponse,
  TemplateLayoutInlineResponse,
  TemplateLayoutResponse,
  TemplateLayoutStyleResponse,
} from '@/api/client'
import type { EditableField } from '@/workspace/layout'

// ---- A layout shaped like the built-in meeting minutes -------------------------------------------
//
// Written by hand from the built-in templates' own document.xml: Liberation Sans throughout, 11 pt
// labels in bold and placeholders in regular, a 16 pt bold title, 13 pt bold section headings, a
// logo, a header line and a "Page 1" footer. One version ends in the table-led template's action
// items table (its last row repeats); the other in the flowing template's numbered paragraph.

const BODY: TemplateLayoutStyleResponse = { fontFamily: 'Liberation Sans', fontSizeHalfPoints: 22, colorHex: '000000' }
const LABEL: TemplateLayoutStyleResponse = { ...BODY, bold: true }
const TITLE: TemplateLayoutStyleResponse = { ...LABEL, fontSizeHalfPoints: 32 }
const SECTION: TemplateLayoutStyleResponse = { ...LABEL, fontSizeHalfPoints: 26 }

function text(value: string, style: TemplateLayoutStyleResponse = BODY): TemplateLayoutInlineResponse {
  return { kind: 'TEXT', text: value, style }
}

function spot(fieldId: string, placeholder: string): TemplateLayoutInlineResponse {
  return { kind: 'FILL_SPOT', fieldId, placeholder, style: BODY }
}

function paragraph(inlines: TemplateLayoutInlineResponse[], extra: Partial<TemplateLayoutBlockResponse> = {}): TemplateLayoutBlockResponse {
  return { kind: 'PARAGRAPH', alignment: null, listLevel: null, repeating: false, inlines, ...extra }
}

function cell(inlines: TemplateLayoutInlineResponse[]) {
  return { blocks: [paragraph(inlines)] }
}

const OPENING: TemplateLayoutBlockResponse[] = [
  paragraph([{ kind: 'IMAGE' }]),
  paragraph([text('Meeting Minutes', TITLE)], { alignment: 'START' }),
  paragraph([text('Title: ', LABEL), spot('meeting.title', '[meeting title]')]),
  paragraph([text('Organization: ', LABEL), spot('meeting.organization', '[organization]')]),
  paragraph([text('Date: ', LABEL), spot('meeting.date', '[date]')]),
  paragraph([text('Location: ', LABEL), spot('meeting.location', '[location]')]),
  paragraph([text('Attendees: ', LABEL), spot('meeting.attendees', '[attendees]')]),
  paragraph([text('Decisions', SECTION)], { alignment: 'START' }),
  paragraph([spot('meeting.decisions', '[decisions]')]),
  paragraph([]),
  paragraph([text('Action Items', SECTION)], { alignment: 'START' }),
]

function minutes(actionItems: TemplateLayoutBlockResponse): TemplateLayoutResponse {
  return {
    templateId: 1,
    versionId: 1,
    parserVersion: 'brownie-docx-graph-v2+poi-5.5.1',
    parts: [
      { kind: 'MAIN_DOCUMENT', blocks: [...OPENING, actionItems] },
      { kind: 'HEADER', blocks: [paragraph([text('Brownie Meeting Minutes Template', LABEL)])] },
      { kind: 'FOOTER', blocks: [paragraph([text('Page 1')])] },
    ],
    unplacedFieldIds: [],
  }
}

const MINUTES_WITH_TABLE = minutes({
  kind: 'TABLE',
  repeating: false,
  rows: [
    { repeating: false, cells: [cell([text('Task', LABEL)]), cell([text('Owner', LABEL)]), cell([text('Due date', LABEL)])] },
    {
      repeating: true,
      cells: [
        cell([spot('action.item.task', '[task]')]),
        cell([spot('action.item.owner', '[owner]')]),
        cell([spot('action.item.due', '[due date]')]),
      ],
    },
  ],
})

const MINUTES_WITH_LIST = minutes(
  paragraph(
    [
      text('Task: '),
      spot('action.item.task', '[task]'),
      text('   Owner: '),
      spot('action.item.owner', '[owner]'),
      text('   Due: '),
      spot('action.item.due', '[due date]'),
    ],
    { listLevel: 0, repeating: true },
  ),
)

const FIELDS: EditableField[] = [
  { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'REQUIRED' },
  { fieldId: 'meeting.organization', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL' },
  { fieldId: 'meeting.date', type: 'DATE', cardinality: 'SCALAR', requiredness: 'REQUIRED' },
  { fieldId: 'meeting.location', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL' },
  { fieldId: 'meeting.attendees', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL' },
  { fieldId: 'meeting.decisions', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL' },
  { fieldId: 'action.item.task', type: 'TEXT', cardinality: 'REPEATED', requiredness: 'OPTIONAL' },
  { fieldId: 'action.item.owner', type: 'TEXT', cardinality: 'REPEATED', requiredness: 'OPTIONAL' },
  { fieldId: 'action.item.due', type: 'DATE', cardinality: 'REPEATED', requiredness: 'OPTIONAL' },
]

type Drafts = Record<string, string | string[]>

function drafts(overrides: Drafts = {}): Drafts {
  return {
    'meeting.title': 'Q3 planning',
    'meeting.organization': '',
    'meeting.date': '2026-09-28',
    'meeting.location': '',
    'meeting.attendees': 'Ana, Ben',
    'meeting.decisions': '',
    'action.item.task': ['Book the room', 'Send the agenda'],
    'action.item.owner': ['Ana', ''],
    'action.item.due': ['2026-10-01', '2026-10-03'],
    ...overrides,
  }
}

function state(overrides: Partial<FieldStateResponse> = {}): FieldStateResponse {
  return { authorship: 'AI_COMPOSED', evidenceSupport: 'DIRECT', validation: 'NOT_RUN', review: 'UNREVIEWED', lock: 'EDITABLE', ...overrides }
}

type PageProps = InstanceType<typeof DocumentPage>['$props']

let wrapper: VueWrapper | null = null

function mountPage(overrides: Partial<PageProps> = {}): VueWrapper {
  wrapper = mount(DocumentPage, {
    props: {
      layout: MINUTES_WITH_TABLE,
      layoutState: 'ready',
      fields: FIELDS,
      drafts: drafts(),
      revisionFields: null,
      requiredFieldIds: new Set(['meeting.title', 'meeting.date']),
      lockedFieldIds: new Set<string>(),
      rowsLocked: false,
      selected: null,
      ...overrides,
    },
    attachTo: document.body,
  })
  return wrapper
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

/** Finds a control by its element id; field ids hold dots, which a plain #id selector would read as classes. */
function control(page: VueWrapper, id: string) {
  return page.get(`[id="${id}"]`)
}

describe('DocumentPage', () => {
  it('draws the template as a page: header, styled text, fill spots in place, the table and the footer', () => {
    const page = mountPage()

    expect(page.get('h2').text()).toBe('Document')
    expect(page.get('section').attributes('aria-labelledby')).toBe(page.get('h2').attributes('id'))
    const sheet = page.get('.document-page__sheet').element as HTMLElement
    expect(sheet.style.fontFamily).toContain('Liberation Sans')
    expect(sheet.style.fontSize).toBe('1rem')

    const header = page.get('.document-page__header-part')
    expect(header.text()).toBe('Brownie Meeting Minutes Template')
    // The header keeps its weight but takes the page's quieter colour instead of the template's black.
    const headerText = header.get('span').element as HTMLElement
    expect(headerText.style.fontWeight).toBe('700')
    expect(headerText.style.color).toBe('')

    const title = page.findAll('.document-page__text').find((span) => span.text() === 'Meeting Minutes')!
    expect((title.element as HTMLElement).style.fontWeight).toBe('700')
    expect((title.element as HTMLElement).style.fontSize).toBe('1.4545em')
    expect(page.get('[role="img"]').attributes('aria-label')).toBe('Image from the template')

    const titleSpot = control(page, 'edit-meeting.title')
    expect(titleSpot.element.tagName).toBe('TEXTAREA')
    expect(titleSpot.attributes('aria-label')).toBe('Meeting title, required')
    expect(titleSpot.attributes('placeholder')).toBe('[meeting title]')
    expect((titleSpot.element as HTMLTextAreaElement).value).toBe('Q3 planning')
    // The label and the spot share one line of the document, as they do in the template.
    expect(titleSpot.element.closest('p')!.textContent).toContain('Title: ')

    const date = control(page, 'edit-meeting.date')
    expect(date.attributes('type')).toBe('date')
    expect((date.element as HTMLInputElement).value).toBe('2026-09-28')
    expect(date.attributes('aria-label')).toBe('Meeting date, required')

    expect(page.findAll('.document-page__main-part p').some((p) => p.text() === '' && p.find('textarea').exists() === false)).toBe(true)
    expect(page.get('.document-page__footer-part').text()).toBe('Page 1')
    expect(page.find('.document-page__others').exists()).toBe(false)
  })

  it('draws the repeating row once per item and says "No rows yet." when there are none', async () => {
    const page = mountPage()

    const rows = page.findAll('.document-page__main-part tr')
    expect(rows).toHaveLength(3)
    expect(rows[0]!.text()).toBe('TaskOwnerDue date')
    const secondTask = control(page, 'edit-action.item.task-1')
    expect((secondTask.element as HTMLTextAreaElement).value).toBe('Send the agenda')
    expect(secondTask.attributes('aria-label')).toBe('Action item task, row 2')
    expect(control(page, 'edit-action.item.due-0').attributes('type')).toBe('date')

    const addRow = page.findAll('button').filter((button) => button.text() === 'Add row')
    expect(addRow).toHaveLength(1)
    await addRow[0]!.trigger('click')
    expect(page.emitted('add-row')).toHaveLength(1)

    await page.setProps({ drafts: drafts({ 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }) })
    const empty = page.findAll('.document-page__main-part tr')
    expect(empty).toHaveLength(2)
    expect(empty[1]!.get('td').text()).toBe('No rows yet.')
    expect(empty[1]!.get('td').attributes('colspan')).toBe('3')
    expect(page.findAll('button').filter((button) => button.text() === 'Add row')).toHaveLength(1)
  })

  it('stops rows being added while one is locked, and says why', () => {
    const page = mountPage({ rowsLocked: true })
    const addRow = page.findAll('button').find((button) => button.text() === 'Add row')!
    expect(addRow.attributes('disabled')).toBeDefined()
    expect(page.text()).toContain('A row is locked, so rows cannot be added until it is unlocked.')
  })

  it('moves focus into a new row once the parent adds it', async () => {
    const current = drafts()
    const page = mountPage({
      drafts: current,
      'onAdd-row': () => {
        void page.setProps({
          drafts: {
            ...current,
            'action.item.task': [...(current['action.item.task'] as string[]), ''],
            'action.item.owner': [...(current['action.item.owner'] as string[]), ''],
            'action.item.due': [...(current['action.item.due'] as string[]), ''],
          },
        })
      },
    })
    await page.findAll('button').find((button) => button.text() === 'Add row')!.trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(document.activeElement?.id).toBe('edit-action.item.task-2')
  })

  it('repeats the numbered paragraph of the flowing template, keeping its spacing and bullet', () => {
    const page = mountPage({ layout: MINUTES_WITH_LIST })
    const items = page.findAll('.document-page__main-part p').filter((p) => p.find('.document-page__bullet').exists())
    expect(items).toHaveLength(2)
    expect((items[1]!.element as HTMLElement).style.paddingInlineStart).toBe('1.5em')
    expect(items[1]!.text()).toContain('Owner:')
    expect(items[1]!.findAll('.document-page__text')[1]!.text()).toBe('Owner:')
    expect(items[1]!.findAll('.document-page__text')[1]!.element.textContent).toBe('   Owner: ')
    expect(control(page, 'edit-action.item.owner-1').attributes('aria-label')).toBe('Action item owner, row 2')
    expect(page.findAll('button').filter((button) => button.text() === 'Add row')).toHaveLength(1)
  })

  it('reports typing with the exact value, for a scalar field, a row and a date', async () => {
    const page = mountPage()

    await control(page, 'edit-meeting.title').setValue('  Budget review  ')
    expect(page.emitted('update-scalar')).toEqual([['meeting.title', '  Budget review  ']])

    await control(page, 'edit-action.item.task-1').setValue('Call the vendor')
    expect(page.emitted('update-row')).toEqual([['action.item.task', 1, 'Call the vendor']])

    await control(page, 'edit-meeting.date').setValue('2026-10-02')
    expect(page.emitted('update-scalar')!.at(-1)).toEqual(['meeting.date', '2026-10-02'])
  })

  it('keeps a value on one line: Enter is refused and pasted or typed line breaks become spaces', async () => {
    const page = mountPage()
    const title = control(page, 'edit-meeting.title').element as HTMLTextAreaElement

    const enter = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true })
    title.dispatchEvent(enter)
    expect(enter.defaultPrevented).toBe(true)
    const composing = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true, isComposing: true })
    title.dispatchEvent(composing)
    expect(composing.defaultPrevented).toBe(false)
    const letter = new KeyboardEvent('keydown', { key: 'a', cancelable: true })
    title.dispatchEvent(letter)
    expect(letter.defaultPrevented).toBe(false)

    title.setSelectionRange(title.value.length, title.value.length)
    await control(page, 'edit-meeting.title').trigger('paste', { clipboardData: { getData: () => '\nline one\r\nline two\n' } })
    expect(page.emitted('update-scalar')!.at(-1)).toEqual(['meeting.title', 'Q3 planning line one line two '])

    await control(page, 'edit-meeting.decisions').setValue('First\n\nSecond')
    expect(page.emitted('update-scalar')!.at(-1)).toEqual(['meeting.decisions', 'First Second'])
    expect((control(page, 'edit-meeting.decisions').element as HTMLTextAreaElement).value).toBe('First Second')
  })

  it('lets a paste without line breaks through untouched', async () => {
    const page = mountPage()
    const paste = new Event('paste', { cancelable: true })
    Object.assign(paste, { clipboardData: { getData: () => 'plain words' } })
    control(page, 'edit-meeting.title').element.dispatchEvent(paste)
    expect(paste.defaultPrevented).toBe(false)
    expect(page.emitted('update-scalar')).toBeUndefined()
  })

  it('lists fields the drawing has no place for under "Other fill spots"', () => {
    const layout: TemplateLayoutResponse = {
      ...MINUTES_WITH_TABLE,
      parts: MINUTES_WITH_TABLE.parts.map((part) =>
        part.kind === 'MAIN_DOCUMENT'
          ? { ...part, blocks: part.blocks.filter((block) => !block.inlines?.some((inline) => inline.fieldId === 'meeting.location')) }
          : part,
      ),
      unplacedFieldIds: ['meeting.location'],
    }
    const fields: EditableField[] = [...FIELDS, { fieldId: 'meeting.next_steps', type: 'TEXT', cardinality: 'SCALAR', requiredness: null }]
    const page = mountPage({ layout, fields })

    const others = page.get('.document-page__list--others')
    expect(others.get('h3').text()).toBe('Other fill spots')
    const lines = others.findAll('p.document-page__paragraph')
    expect(lines.map((line) => line.get('.document-page__field-label').text())).toEqual(['Meeting location:', 'Meeting next steps:'])
    expect(control(page, 'edit-meeting.location').attributes('aria-label')).toBe('Meeting location')
    expect(page.find('[id="edit-meeting.next_steps"]').exists()).toBe(true)
    // The list sits between the document's main text and its footer.
    const order = [...page.get('.document-page__sheet').element.children].map((child) => child.className)
    expect(order.indexOf('document-page__list document-page__list--others')).toBeLessThan(order.indexOf('document-page__footer-part'))
    expect(order.indexOf('document-page__list document-page__list--others')).toBeGreaterThan(order.indexOf('document-page__main-part'))
  })

  it('lists the fields when there is no layout, and says why only when the layout could not be drawn', async () => {
    const page = mountPage({ layout: null, layoutState: 'loading' })

    expect(page.find('[role="status"]').exists()).toBe(false)
    expect(page.find('h3').exists()).toBe(false)
    const lines = page.findAll('p.document-page__paragraph')
    expect(lines.map((line) => line.get('.document-page__field-label').text())).toEqual([
      'Meeting title:',
      'Meeting organization:',
      'Meeting date:',
      'Meeting location:',
      'Meeting attendees:',
      'Meeting decisions:',
    ])
    expect(page.findAll('th').map((th) => th.text())).toEqual(['Action item task', 'Action item owner', 'Action item due'])
    expect(page.findAll('th').every((th) => th.attributes('scope') === 'col')).toBe(true)
    expect((control(page, 'edit-action.item.owner-0').element as HTMLTextAreaElement).value).toBe('Ana')
    expect(control(page, 'edit-action.item.due-1').attributes('aria-label')).toBe('Action item due, row 2')
    expect(page.findAll('button').filter((button) => button.text() === 'Add row')).toHaveLength(1)

    await page.setProps({ layoutState: 'unavailable' })
    expect(page.get('[role="status"]').text()).toBe(
      "Brownie could not draw this template's layout, so its fill spots are listed instead.",
    )

    await page.setProps({ drafts: drafts({ 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }) })
    expect(page.get('tbody td').text()).toBe('No rows yet.')
    expect(page.get('tbody td').attributes('colspan')).toBe('3')
  })

  it("names a spot by the form's own label wherever the page names it", () => {
    const fields: EditableField[] = [
      { fieldId: 'ho.va.ten', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'REQUIRED', label: 'Họ và tên', origin: 'FOUND_BY_BROWNIE' },
      { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', label: null },
      { fieldId: 'spot.2', type: 'TEXT', cardinality: 'REPEATED', requiredness: 'OPTIONAL', label: 'Owner', origin: 'FOUND_BY_BROWNIE' },
    ]
    const page = mountPage({
      layout: null,
      fields,
      drafts: { 'ho.va.ten': '', 'meeting.title': '', 'spot.2': ['Ana'] },
      requiredFieldIds: new Set(['ho.va.ten']),
    })

    const lines = page.findAll('p.document-page__paragraph')
    expect(lines.map((line) => line.get('.document-page__field-label').text())).toEqual(['Họ và tên:', 'Meeting title:'])
    expect(control(page, 'edit-ho.va.ten').attributes('aria-label')).toBe('Họ và tên, required')
    expect(page.findAll('th').map((th) => th.text())).toEqual(['Owner'])
    expect(control(page, 'edit-spot.2-0').attributes('aria-label')).toBe('Owner, row 1')
  })

  it('says there is nothing to fill in when there are no fields and no layout', () => {
    const page = mountPage({ layout: null, fields: [], drafts: {} })
    expect(page.text()).toContain('This document has no fill spots.')
    expect(page.find('.document-page__progress').exists()).toBe(false)
  })

  it('says the page is loading, not that it has no fill spots, while what it holds is still on its way', () => {
    const page = mountPage({ layout: null, layoutState: 'loading', fields: [], drafts: {} })
    expect(page.get('[role="status"]').text()).toBe('Loading the page…')
    expect(page.text()).not.toContain('This document has no fill spots.')
  })

  it('marks required spots on screen and says what the marks mean', () => {
    const page = mountPage({
      revisionFields: {
        'meeting.attendees': { type: 'TEXT', cardinality: 'SCALAR', value: 'Ana, Ben', fieldState: state() },
      },
    })
    const markOf = (id: string) => control(page, id).element.closest(".fill-spot")!.querySelector('.fill-spot__required')
    expect(markOf('edit-meeting.title')?.textContent?.trim()).toMatch(/\*$/)
    expect(markOf('edit-meeting.title')?.getAttribute('aria-hidden')).toBe('true')
    expect(markOf('edit-meeting.location')).toBeNull()
    const legend = page.get('.document-page__legend')
    expect(legend.text()).toContain('Required before export')
    // A value from Brownie is on the page, so its mark is explained too.
    expect(legend.text()).toContain('Filled by Brownie')
  })

  it('leaves the marks unexplained when no spot carries them', () => {
    const page = mountPage({ requiredFieldIds: new Set(), revisionFields: null })
    expect(page.find('.document-page__legend').exists()).toBe(false)
  })

  it('asks for a spot\'s review and lock controls on Alt+Enter, and never starts a second line', async () => {
    const page = mountPage()
    const title = control(page, 'edit-meeting.title')
    const plain = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true })
    title.element.dispatchEvent(plain)
    expect(plain.defaultPrevented).toBe(true)
    expect(page.emitted('open-actions')).toBeUndefined()

    const withAlt = new KeyboardEvent('keydown', { key: 'Enter', altKey: true, cancelable: true })
    title.element.dispatchEvent(withAlt)
    expect(withAlt.defaultPrevented).toBe(true)
    await control(page, 'edit-action.item.due-0').trigger('keydown', { key: 'Enter', altKey: true })
    expect(page.emitted('open-actions')).toEqual([[{ fieldId: 'meeting.title', rowIndex: null }], [{ fieldId: 'action.item.due', rowIndex: 0 }]])
  })

  it('marks the Add row button so the page can send focus to it', () => {
    const page = mountPage()
    expect(page.get('[data-add-row]').text()).toBe('Add row')
  })

  it('makes a locked spot read-only, says why, and describes each state in words', () => {
    const revisionFields: DocumentRevisionResponse['fields'] = {
      'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Q3 planning', fieldState: state({ lock: 'EXPLICITLY_LOCKED' }) },
      'meeting.organization': {
        type: 'TEXT',
        cardinality: 'SCALAR',
        value: 'Acme',
        fieldState: state({ authorship: 'USER_AUTHORED', evidenceSupport: 'MISSING', validation: 'BLOCKING' }),
      },
      'action.item.task': {
        type: 'TEXT',
        cardinality: 'REPEATED',
        values: ['Book the room', 'Send the agenda'],
        itemFieldStates: [state({ lock: 'EXPLICITLY_LOCKED' }), state()],
      },
    }
    const page = mountPage({
      revisionFields,
      lockedFieldIds: new Set(['meeting.title']),
      rowsLocked: true,
      drafts: drafts({ 'meeting.organization': 'Acme' }),
    })

    // Each spot is described by its own state, then by the page's one note on reaching its review and lock controls.
    const describedBy = (spot: ReturnType<typeof control>) =>
      (spot.attributes('aria-describedby') ?? '').split(' ').filter(Boolean).map((id) => page.get(`[id="${id}"]`).text())
    const KEYS = 'Alt+Enter moves to the bar about this spot.'

    const title = control(page, 'edit-meeting.title')
    expect(title.attributes('readonly')).toBeDefined()
    expect(title.attributes('aria-readonly')).toBe('true')
    expect(title.attributes('disabled')).toBeUndefined()
    expect(title.attributes('aria-keyshortcuts')).toBe('Alt+Enter')
    expect(describedBy(title)).toEqual(['From Brownie. Source cited. Not reviewed. Locked: unlock it to edit.', KEYS])
    expect(title.element.closest(".fill-spot")!.classList.contains('fill-spot--locked')).toBe(true)
    expect(title.element.closest(".fill-spot")!.querySelector('svg[aria-hidden="true"]')).not.toBeNull()

    const organization = control(page, 'edit-meeting.organization')
    expect(organization.attributes('aria-invalid')).toBe('true')
    expect(describedBy(organization)).toEqual(['Typed by you. Blocks export. Not reviewed.', KEYS])
    expect(organization.element.closest(".fill-spot")!.classList.contains('fill-spot--attention')).toBe(true)
    expect(organization.attributes('readonly')).toBeUndefined()

    const lockedRow = control(page, 'edit-action.item.task-0')
    expect(describedBy(lockedRow)).toEqual(['From Brownie. Source cited. Not reviewed. Locked: unlock it to edit.', KEYS])
    const heldRow = control(page, 'edit-action.item.task-1')
    expect(heldRow.attributes('readonly')).toBeDefined()
    expect(describedBy(heldRow)).toEqual([
      'From Brownie. Source cited. Not reviewed. A row is locked, so rows cannot be edited until it is unlocked.',
      KEYS,
    ])

    // A spot with no state to describe points only at the page's note on its controls.
    expect(describedBy(control(page, 'edit-meeting.location'))).toEqual([KEYS])
  })

  it('shows empty, typed, from-Assist and blank-date-row spots differently', () => {
    const page = mountPage({
      revisionFields: {
        'meeting.attendees': { type: 'TEXT', cardinality: 'SCALAR', value: 'Ana, Ben', fieldState: state() },
      },
      drafts: drafts({ 'action.item.due': ['2026-10-01', ''] }),
    })
    const wrapperOf = (id: string) => control(page, id).element.closest(".fill-spot")!.classList

    expect(wrapperOf('edit-meeting.location').contains('fill-spot--empty')).toBe(true)
    expect(control(page, 'edit-meeting.location').attributes('placeholder')).toBe('[location]')
    expect(wrapperOf('edit-meeting.title').contains('fill-spot--filled')).toBe(true)
    expect(wrapperOf('edit-meeting.title').contains('fill-spot--assist')).toBe(false)
    expect(wrapperOf('edit-meeting.attendees').contains('fill-spot--assist')).toBe(true)
    expect(wrapperOf('edit-action.item.due-1').contains('fill-spot--attention')).toBe(true)
    expect(control(page, 'edit-action.item.due-1').attributes('aria-invalid')).toBe('true')
    expect(wrapperOf('edit-action.item.due-0').contains('fill-spot--attention')).toBe(false)
    // A blank text item in a row is allowed, so it is only empty.
    expect(wrapperOf('edit-action.item.owner-1').contains('fill-spot--attention')).toBe(false)
  })

  it('tells the parent which spot is selected when one takes focus, and marks the selected one', async () => {
    const page = mountPage()
    await control(page, 'edit-meeting.title').trigger('focus')
    await control(page, 'edit-action.item.owner-1').trigger('focus')
    expect(page.emitted('select')).toEqual([[{ fieldId: 'meeting.title', rowIndex: null }], [{ fieldId: 'action.item.owner', rowIndex: 1 }]])

    await page.setProps({ selected: { fieldId: 'action.item.owner', rowIndex: 1 } })
    expect(control(page, 'edit-action.item.owner-1').element.closest(".fill-spot")!.classList.contains('fill-spot--selected')).toBe(true)
    expect(control(page, 'edit-action.item.owner-0').element.closest(".fill-spot")!.classList.contains('fill-spot--selected')).toBe(false)
  })

  it('gives only the first place a value appears the plain id, and every place edits the one value', async () => {
    const layout: TemplateLayoutResponse = {
      ...MINUTES_WITH_TABLE,
      parts: [
        { kind: 'HEADER', blocks: [paragraph([text('Minutes of '), spot('meeting.title', '[meeting title]')])] },
        ...MINUTES_WITH_TABLE.parts.filter((part) => part.kind !== 'HEADER'),
      ],
    }
    const page = mountPage({ layout })

    expect(page.findAll('[id^="edit-meeting.title"]').map((element) => element.attributes('id'))).toEqual([
      'edit-meeting.title',
      'edit-meeting.title--2',
    ])
    expect(control(page, 'edit-meeting.title').element.closest('.document-page__header-part')).not.toBeNull()
    expect((control(page, 'edit-meeting.title--2').element as HTMLTextAreaElement).value).toBe('Q3 planning')
    await control(page, 'edit-meeting.title--2').setValue('Offsite')
    expect(page.emitted('update-scalar')).toEqual([['meeting.title', 'Offsite']])
  })

  it('keeps the same control and its caret while the parent passes back what was typed', async () => {
    const page = mountPage()
    const element = control(page, 'edit-meeting.title').element as HTMLTextAreaElement
    element.value = 'Q3 planning review'
    element.setSelectionRange(3, 3)
    element.dispatchEvent(new Event('input'))
    expect(element.selectionStart).toBe(3)

    await page.setProps({ drafts: drafts({ 'meeting.title': 'Q3 planning review' }) })
    expect(control(page, 'edit-meeting.title').element).toBe(element)
    expect(element.selectionStart).toBe(3)

    await page.setProps({ drafts: drafts({ 'meeting.title': 'Q3 planning review', 'meeting.location': 'Room 4' }) })
    expect(control(page, 'edit-meeting.title').element).toBe(element)
    expect(element.selectionStart).toBe(3)
  })

  it('counts the filled spots and moves to the next empty one after the last focused, wrapping', async () => {
    const page = mountPage()
    // Six scalar fields and three repeated fields over two rows: twelve values, of which eight hold one.
    expect(page.get('.document-page__progress').text()).toContain('8 of 12 fill spots filled')

    const next = page.findAll('button').find((button) => button.text() === 'Next empty spot')!
    await next.trigger('click')
    expect(document.activeElement?.id).toBe('edit-meeting.organization')

    await control(page, 'edit-meeting.location').trigger('focus')
    await next.trigger('click')
    expect(document.activeElement?.id).toBe('edit-meeting.decisions')
    await next.trigger('click')
    expect(document.activeElement?.id).toBe('edit-action.item.owner-1')
    await next.trigger('click')
    expect(document.activeElement?.id).toBe('edit-meeting.organization')

    await page.setProps({
      drafts: drafts({
        'meeting.organization': 'Acme',
        'meeting.location': 'Room 4',
        'meeting.decisions': 'Ship it',
        'action.item.owner': ['Ana', 'Ben'],
      }),
    })
    expect(page.get('.document-page__progress').text()).toContain('12 of 12 fill spots filled')
    expect(page.findAll('button').some((button) => button.text() === 'Next empty spot')).toBe(false)
  })

  it('passes over a locked empty spot when moving to the next empty one', async () => {
    const page = mountPage({ lockedFieldIds: new Set(['meeting.organization']) })
    await page.findAll('button').find((button) => button.text() === 'Next empty spot')!.trigger('click')
    expect(document.activeElement?.id).toBe('edit-meeting.location')
  })

  it('marks each spot on a rail hidden from assistive technology, and focuses a spot from its mark', async () => {
    const page = mountPage({ drafts: drafts({ 'action.item.due': ['2026-10-01', ''] }) })
    const rail = page.get('.document-page__rail')
    expect(rail.attributes('aria-hidden')).toBe('true')
    const markers = rail.findAll('.document-page__marker')
    expect(markers).toHaveLength(12)
    expect(markers[0]!.classes()).toContain('document-page__marker--filled')
    expect(markers[1]!.classes()).toContain('document-page__marker--empty')
    expect(markers.at(-1)!.classes()).toContain('document-page__marker--attention')
    expect(rail.find('button, a, [tabindex]').exists()).toBe(false)

    await markers[3]!.trigger('click')
    expect(document.activeElement?.id).toBe('edit-meeting.location')
  })

  it('passes an accessibility scan with the drawn page', async () => {
    const page = mountPage({
      revisionFields: {
        'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Q3 planning', fieldState: state({ lock: 'EXPLICITLY_LOCKED' }) },
        'meeting.date': { type: 'DATE', cardinality: 'SCALAR', value: '2026-09-28', fieldState: state({ validation: 'BLOCKING', lock: 'EXPLICITLY_LOCKED' }) },
      },
      lockedFieldIds: new Set(['meeting.title', 'meeting.date']),
      selected: { fieldId: 'meeting.attendees', rowIndex: null },
    })
    expect(await axe(page.element)).toHaveNoViolations()
  })

  it('passes an accessibility scan with the field list', async () => {
    const page = mountPage({ layout: null, layoutState: 'unavailable', rowsLocked: true, lockedFieldIds: new Set(['meeting.date']) })
    expect(await axe(page.element)).toHaveNoViolations()
  })

  /** A place Brownie found itself says so in words beside it; a repeated one says it once, on its first row. */
  it('marks the places Brownie found in words, a repeated one on its first row only', async () => {
    const page = mountPage({ foundFieldIds: new Set(['meeting.location', 'action.item.owner']) })

    const marked = page.findAll('.fill-spot__found').map((badge) => badge.element.parentElement!.querySelector('.fill-spot__control')!.id)
    expect(marked).toEqual(['edit-meeting.location', 'edit-action.item.owner-0'])
    expect(page.get('.fill-spot__found').text()).toBe('Found by Brownie')
    expect(control(page, 'edit-meeting.title').element.parentElement!.querySelector('.fill-spot__found')).toBeNull()
    expect(await axe(page.element)).toHaveNoViolations()

    await page.setProps({ foundFieldIds: new Set<string>() })
    expect(page.findAll('.fill-spot__found')).toHaveLength(0)
  })
})

describe('FillSpot', () => {
  const base = {
    fieldId: 'meeting.location',
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

  it('brackets a placeholder so an empty spot reads as a gap, and falls back to the label', () => {
    expect(mount(FillSpot, { props: base }).get('textarea').attributes('placeholder')).toBe('[Meeting location]')
    expect(mount(FillSpot, { props: { ...base, placeholder: 'Where it happened' } }).get('textarea').attributes('placeholder')).toBe(
      '[Where it happened]',
    )
    expect(mount(FillSpot, { props: { ...base, placeholder: '[location]' } }).get('textarea').attributes('placeholder')).toBe('[location]')
  })

  it('draws a text spot as one line in the template style, and a date spot as a date input', () => {
    const text = mount(FillSpot, { props: base }).get('textarea')
    expect(text.attributes('rows')).toBe('1')
    expect((text.element as HTMLElement).style.fontFamily).toContain('Liberation Sans')
    const date = mount(FillSpot, { props: { ...base, type: 'DATE', value: '2026-09-28', rowIndex: 2, required: true } }).get('input')
    expect(date.attributes('type')).toBe('date')
    expect(date.attributes('aria-label')).toBe('Meeting location, required, row 3')
  })

  it('is as wide as what it shows, from a small minimum, where the browser cannot size it to its content', () => {
    const chars = (props: { value?: string; placeholder?: string }) =>
      (mount(FillSpot, { props: { ...base, ...props } }).get('textarea').element as HTMLElement).style.getPropertyValue('--spot-chars')
    // Empty, it shows its placeholder; typed, its value.
    expect(chars({})).toBe(String('[Meeting location]'.length + 1))
    expect(chars({ value: 'Hall' })).toBe('5')
    expect(chars({ value: 'A', placeholder: '[x]' })).toBe('4')
    expect(chars({ value: 'x'.repeat(200) })).toBe('60')
  })

  it('reports focus, and a click on a spot that already has it, so its bar can open again', async () => {
    const spot = mount(FillSpot, { props: base })
    await spot.get('textarea').trigger('focus')
    await spot.get('textarea').trigger('click')
    expect(spot.emitted('focus')).toHaveLength(2)
  })

  it('does not treat Alt+Enter that finishes an input-method composition as a request for the controls', async () => {
    const spot = mount(FillSpot, { props: base })
    await spot.get('textarea').trigger('keydown', { key: 'Enter', altKey: true, isComposing: true })
    expect(spot.emitted('actions')).toBeUndefined()
    await spot.get('textarea').trigger('keydown', { key: 'Enter', altKey: true, ctrlKey: true })
    expect(spot.emitted('actions')).toBeUndefined()
  })
})
