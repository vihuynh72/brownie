import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
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
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

/** Finds a control by its element id; field ids hold dots, which a plain #id selector would read as classes. */
function control(page: VueWrapper, id: string) {
  return page.get(`[id="${id}"]`)
}

type SpotWrapper = VueWrapper<InstanceType<typeof FillSpot>>

/** The element ids of the spots whose props satisfy `test`, in reading order: what the page told each spot. */
function spotsWhere(page: VueWrapper, test: (spot: SpotWrapper) => boolean): string[] {
  return page
    .findAllComponents(FillSpot)
    .filter(test)
    .map((spot) => spot.props('inputId'))
}

describe('DocumentPage', () => {
  it('draws the template as a page: header, styled text, fill spots in place, the table and the footer', () => {
    const page = mountPage()

    expect(page.get('h2').text()).toBe('Document')
    expect(page.get('section').attributes('aria-labelledby')).toBe(page.get('h2').attributes('id'))
    const sheet = page.get('.document-page__sheet').element as HTMLElement
    expect(sheet.style.fontFamily).toContain('Liberation Sans')
    // An 11 point body is drawn at 14px (0.875rem); the rest keep their proportion to it.
    expect(sheet.style.fontSize).toBe('0.875rem')

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
    expect(page.find('.document-page__list--others').exists()).toBe(false)
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
    expect(empty[1]!.get('td').text()).toContain('No rows yet.')
    expect(empty[1]!.get('td').attributes('colspan')).toBe('3')
    // The way to the first row sits in that line, and it is still the page's only Add row.
    expect(empty[1]!.get('td button').text()).toBe('Add row')
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

  it('still moves focus into the new row when a proposal was previewing more rows than the document has', async () => {
    // The document has no rows of its own yet; a proposal previews three, as "Show what I found" does.
    const empty = drafts({ 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] })
    const page = mountPage({
      drafts: empty,
      previewDrafts: {
        'action.item.task': ['One', 'Two', 'Three'],
        'action.item.owner': ['A', 'B', 'C'],
        'action.item.due': ['2026-10-01', '2026-10-02', '2026-10-03'],
      },
      'onAdd-row': () => {
        // Adding a row edits the table, which drops its own proposal, same as the real page does.
        void page.setProps({
          drafts: { ...empty, 'action.item.task': [''], 'action.item.owner': [''], 'action.item.due': [''] },
          previewDrafts: null,
        })
      },
    })
    await page.findAll('button').find((button) => button.text() === 'Add row')!.trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(document.activeElement?.id).toBe('edit-action.item.task-0')
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
    expect(page.get('tbody td').text()).toContain('No rows yet.')
    expect(page.get('tbody td').attributes('colspan')).toBe('3')
    expect(page.get('tbody td button').text()).toBe('Add row')
    expect(page.findAll('button').filter((button) => button.text() === 'Add row')).toHaveLength(1)
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
    expect(page.find('.document-page__status').exists()).toBe(false)
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
    expect(page.get('.document-page__legend').text()).toBe('* Required before export')
    expect(page.get('.document-page__legend-required').attributes('aria-hidden')).toBe('true')
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

  it('moves to the next empty spot after the last focused, wrapping, until none is left', async () => {
    const page = mountPage()
    expect(page.get('.document-page__summary').text()).toBe('3 of 7 fill spots filled')

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
    expect(page.get('.document-page__summary').text()).toBe('7 of 7 fill spots filled')
    expect(page.findAll('button').some((button) => button.text() === 'Next empty spot')).toBe(false)
  })

  it('passes over a locked empty spot when moving to the next empty one', async () => {
    const page = mountPage({ lockedFieldIds: new Set(['meeting.organization']) })
    await page.findAll('button').find((button) => button.text() === 'Next empty spot')!.trigger('click')
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

  /** A place Brownie found itself is marked on its spot; a repeated one is marked once, on its first row. */
  it('marks the places Brownie found, a repeated one on its first row only', async () => {
    const page = mountPage({ foundFieldIds: new Set(['meeting.location', 'action.item.owner']) })

    expect(spotsWhere(page, (spot) => spot.props('found') === true)).toEqual(['edit-meeting.location', 'edit-action.item.owner-0'])
    expect(await axe(page.element)).toHaveNoViolations()

    await page.setProps({ foundFieldIds: new Set<string>() })
    expect(spotsWhere(page, (spot) => spot.props('found') === true)).toEqual([])
  })
})

// ---- The status line ------------------------------------------------------------------------------

describe('DocumentPage: the status line', () => {
  /** The text of an element without what `hidden` matches, in one line. */
  function textWithout(element: Element, hidden: string): string {
    const copy = element.cloneNode(true) as Element
    copy.querySelectorAll(hidden).forEach((node) => node.remove())
    return copy.textContent!.replace(/\s+/g, ' ').trim()
  }
  /** What the eye reads, and what assistive technology reads. */
  const seen = (page: VueWrapper) => textWithout(page.get('.document-page__summary').element, '.visually-hidden')
  const spoken = (page: VueWrapper) => textWithout(page.get('.document-page__summary').element, '[aria-hidden="true"]')
  const summaryOf = seen

  it('counts the table of rows once, however many columns and rows it has', () => {
    // Six fields with one value each and one table: seven places, three of them filled.
    expect(summaryOf(mountPage())).toBe('3 of 7 fill spots filled')
  })

  it('says "fill spot" for a single place', () => {
    const page = mountPage({ layout: null, fields: [FIELDS[0]!], drafts: { 'meeting.title': '' } })
    expect(summaryOf(page)).toBe('0 of 1 fill spot filled')
  })

  it('counts a table as filled only once it has rows and none of them holds a blank, and as a place while it has none', async () => {
    const page = mountPage({ drafts: drafts({ 'action.item.owner': ['Ana', 'Ben'] }) })
    expect(summaryOf(page)).toBe('4 of 7 fill spots filled')

    await page.setProps({ drafts: drafts({ 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }) })
    expect(summaryOf(page)).toBe('3 of 7 fill spots filled')
  })

  it('says how many of Brownie\'s values wait for a look and how many are accepted, only when there are some', async () => {
    const page = mountPage()
    expect(summaryOf(page)).toBe('3 of 7 fill spots filled')
    expect(page.find('.document-page__summary [aria-hidden="true"]').exists()).toBe(false)

    const columns = (review: FieldStateResponse['review']) => ({
      'action.item.task': { type: 'TEXT' as const, cardinality: 'REPEATED' as const, values: ['Book the room', 'Send the agenda'], itemFieldStates: [state({ review }), state({ review })] },
      'action.item.owner': { type: 'TEXT' as const, cardinality: 'REPEATED' as const, values: ['Ana', ''], itemFieldStates: [state({ review }), state({ review })] },
      'action.item.due': { type: 'DATE' as const, cardinality: 'REPEATED' as const, values: ['2026-10-01', '2026-10-03'], itemFieldStates: [state({ review }), state({ review })] },
    })
    // The title and the table are Brownie's and nobody has looked; the date was typed, so it needs no look; the attendees are accepted.
    await page.setProps({
      revisionFields: {
        'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Q3 planning', fieldState: state() },
        'meeting.date': { type: 'DATE', cardinality: 'SCALAR', value: '2026-09-28', fieldState: state({ authorship: 'USER_AUTHORED', evidenceSupport: 'MISSING' }) },
        'meeting.attendees': { type: 'TEXT', cardinality: 'SCALAR', value: 'Ana, Ben', fieldState: state({ review: 'ACCEPTED' }) },
        ...columns('UNREVIEWED'),
      },
    })
    expect(seen(page)).toBe('3 of 7 fill spots filled · 2 to review · 1 accepted')
    // The dots are for the eye; assistive technology reads the counts as sentences.
    expect(spoken(page)).toBe('3 of 7 fill spots filled. 2 to review. 1 accepted')

    await page.setProps({
      revisionFields: {
        'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Q3 planning', fieldState: state({ review: 'ACCEPTED' }) },
        'meeting.attendees': { type: 'TEXT', cardinality: 'SCALAR', value: 'Ana, Ben', fieldState: state({ review: 'ACCEPTED' }) },
        ...columns('ACCEPTED'),
      },
    })
    expect(seen(page)).toBe('3 of 7 fill spots filled · 3 accepted')
  })

  it('puts the count first, then what the red mark means, then the ways to the next gap and a new spot', () => {
    const page = mountPage({ canAddSpots: true })
    const status = page.get('.document-page__status').element
    expect([...status.children].map((child) => child.className)).toEqual(['document-page__status-text', 'document-page__status-actions'])
    expect([...status.firstElementChild!.children].map((child) => child.className)).toEqual(['document-page__summary', 'document-page__legend'])
    expect(page.findAll('.document-page__status-actions button').map((button) => button.text())).toEqual(['Add a fill spot', 'Next empty spot'])
  })

  it('draws what the screen puts in the banner slot between the status line and the sheet', () => {
    const page = mount(DocumentPage, {
      props: {
        layout: MINUTES_WITH_TABLE,
        layoutState: 'ready',
        fields: FIELDS,
        drafts: drafts(),
        revisionFields: null,
        requiredFieldIds: new Set<string>(),
        lockedFieldIds: new Set<string>(),
        rowsLocked: false,
        selected: null,
      },
      slots: { banner: '<div class="note">Brownie found places</div>' },
      attachTo: document.body,
    })
    wrapper = page
    const order = [...page.get('section').element.children].map((child) => child.className)
    expect(order.indexOf('document-page__status')).toBeLessThan(order.indexOf('note'))
    expect(order.indexOf('note')).toBeLessThan(order.indexOf('document-page__body'))
  })

  it('draws nothing in its place when the screen gives no banner', () => {
    expect(mountPage().find('.note').exists()).toBe(false)
    expect([...wrapper!.get('section').element.children].map((child) => child.className)).not.toContain('note')
  })
})

// ---- The rail ---------------------------------------------------------------------------------------

describe('DocumentPage: the rail', () => {
  const rect = (top: number, height: number): DOMRect =>
    ({ top, height, left: 0, width: 100, right: 100, bottom: top + height, x: 0, y: top, toJSON: () => ({}) }) as DOMRect

  /** Lays the page out in the window's pixels: the sheet's top, and each control's [top, height] by element id ('add-row' for the button). */
  function layOut(sheetTop: number, boxes: Record<string, [number, number]>): void {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains('document-page__sheet')) return rect(sheetTop, 900)
      const box = boxes[this.hasAttribute('data-add-row') ? 'add-row' : this.id]
      return box ? rect(box[0], box[1]) : rect(0, 0)
    })
  }

  const ticksOf = (page: VueWrapper) => page.findAll('.document-page__tick')
  const tonesOf = (page: VueWrapper) => ticksOf(page).map((tick) => tick.classes().find((name) => name.startsWith('document-page__tick--'))!.replace('document-page__tick--', ''))

  async function mountRail(overrides: Partial<PageProps> = {}): Promise<VueWrapper> {
    const page = mountPage(overrides)
    await nextTick()
    return page
  }

  it('is hidden from assistive technology, numbers the page and holds nothing to tab to', async () => {
    const page = await mountRail()
    const rail = page.get('.document-page__rail')
    expect(rail.attributes('aria-hidden')).toBe('true')
    expect(rail.get('.document-page__page-number').text()).toBe('1')
    expect(rail.find('button, a, input, [tabindex]').exists()).toBe(false)
  })

  it('has a tick for every fill spot, the table once, coloured by what is left to do', async () => {
    const page = await mountRail({ drafts: drafts({ 'action.item.due': ['2026-10-01', '2026-10-03'] }) })
    // Title, organization, date, location, attendees, decisions, then the table (its owner column has a blank).
    expect(tonesOf(page)).toEqual(['filled', 'open', 'filled', 'open', 'filled', 'open', 'open'])

    await page.setProps({ drafts: drafts({ 'action.item.owner': ['Ana', 'Ben'] }) })
    expect(tonesOf(page).at(-1)).toBe('filled')
  })

  it('draws a tick thicker when a spot needs a look: a question, a value that cannot be saved, the one chosen', async () => {
    const page = await mountRail({ questionCounts: { 'meeting.organization': 2 } })
    expect(tonesOf(page)[1]).toBe('question')

    // A blank date in a row cannot be saved, and a value that blocks export cannot be exported: both read as invalid.
    await page.setProps({
      drafts: drafts({ 'action.item.due': ['2026-10-01', ''] }),
      revisionFields: { 'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Q3 planning', fieldState: state({ validation: 'BLOCKING' }) } },
    })
    expect(tonesOf(page)[0]).toBe('invalid')
    expect(tonesOf(page).at(-1)).toBe('invalid')

    // The same split a spot makes: a value turned down is red, and one that needs clarifying is a question.
    await page.setProps({
      drafts: drafts(),
      revisionFields: {
        'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Q3 planning', fieldState: state({ review: 'REJECTED' }) },
        'meeting.date': { type: 'DATE', cardinality: 'SCALAR', value: '2026-09-28', fieldState: state({ review: 'NEEDS_CLARIFICATION' }) },
      },
    })
    expect(tonesOf(page)[0]).toBe('invalid')
    expect(tonesOf(page)[2]).toBe('question')

    // What is chosen outranks the rest, and any row of the table chooses the table.
    await page.setProps({ selected: { fieldId: 'action.item.owner', rowIndex: 1 } })
    expect(tonesOf(page).at(-1)).toBe('selected')
    await page.setProps({ selected: { fieldId: 'meeting.title', rowIndex: null } })
    expect(tonesOf(page)[0]).toBe('selected')
  })

  it('keeps a spot with a proposed value open: it is a gap in the document until it is approved', async () => {
    const page = await mountRail({ previewDrafts: { 'meeting.title': 'Fall build season kickoff' } })
    expect(tonesOf(page)[0]).toBe('open')
  })

  it('puts each tick level with the first line of its spot, measured from the top of the sheet', async () => {
    vi.spyOn(window, 'getComputedStyle').mockImplementation(() => ({ lineHeight: '24px', borderTopWidth: '1px', paddingTop: '1px' }) as CSSStyleDeclaration)
    layOut(100, {
      'edit-meeting.title': [140, 24],
      // Two lines tall: the tick stays with the first of them.
      'edit-meeting.decisions': [300, 48],
      'edit-action.item.task-0': [420, 24],
    })
    const page = await mountRail()
    const tops = ticksOf(page).map((tick) => (tick.element as HTMLElement).style.top)
    // Each is the spot's top, its border and padding, half a line, less the sheet's top.
    expect(tops[0]).toBe('53px')
    expect(tops[5]).toBe('214px')
    // The table's tick stands level with its first row.
    expect(tops[6]).toBe('333px')
  })

  it('measures again when the page reflows, even where the sheet itself keeps its size', async () => {
    const observed: Element[] = []
    let notify: () => void = () => {}
    const disconnect = vi.fn()
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: () => void) {
          notify = callback
        }
        observe(target: Element) {
          observed.push(target)
        }
        disconnect = disconnect
      },
    )
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      callback(0)
      return 1
    })
    // A line height the browser has not worked out (the keyword), so a spot is level with its whole height.
    vi.spyOn(window, 'getComputedStyle').mockImplementation(() => ({ lineHeight: 'normal', borderTopWidth: '0px', paddingTop: '0px' }) as CSSStyleDeclaration)
    layOut(100, { 'edit-meeting.title': [140, 24] })
    const page = await mountRail()

    // The sheet is watched, and so is every part of it, because a line wrapping above a spot moves it without resizing the sheet.
    expect(observed).toContain(page.get('.document-page__sheet').element)
    expect(observed).toContain(page.get('.document-page__main-part').element)
    expect((ticksOf(page)[0]!.element as HTMLElement).style.top).toBe('52px')

    layOut(100, { 'edit-meeting.title': [161, 24] })
    notify()
    await nextTick()
    expect((ticksOf(page)[0]!.element as HTMLElement).style.top).toBe('73px')

    page.unmount()
    wrapper = null
    expect(disconnect).toHaveBeenCalled()
  })

  it('measures again when a value changes, since it can move a spot onto another line without changing any part\'s height', async () => {
    vi.spyOn(window, 'getComputedStyle').mockImplementation(() => ({ lineHeight: 'normal', borderTopWidth: '0px', paddingTop: '0px' }) as CSSStyleDeclaration)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      callback(0)
      return 1
    })
    layOut(100, { 'edit-meeting.attendees': [200, 24] })
    const page = await mountRail()
    expect((ticksOf(page)[4]!.element as HTMLElement).style.top).toBe('112px')

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      layOut(100, { 'edit-meeting.attendees': [221, 24] })
      await page.setProps({ drafts: drafts({ 'meeting.attendees': 'Ana, Ben, Cleo, Dev, Eli, Fay, Gus, Hal' }) })
      vi.advanceTimersByTime(200)
      await nextTick()
      expect((ticksOf(page)[4]!.element as HTMLElement).style.top).toBe('133px')

      layOut(100, { 'edit-meeting.attendees': [242, 24] })
      await page.setProps({ previewDrafts: { 'meeting.attendees': 'Ana, Ben, Cleo, Dev, Eli, Fay, Gus, Hal, Ira, Jo' } })
      vi.advanceTimersByTime(200)
      await nextTick()
      expect((ticksOf(page)[4]!.element as HTMLElement).style.top).toBe('154px')
    } finally {
      vi.useRealTimers()
    }
  })

  it('waits for a pause in a run of changes instead of measuring the whole page back for each one', async () => {
    vi.spyOn(window, 'getComputedStyle').mockImplementation(() => ({ lineHeight: 'normal', borderTopWidth: '0px', paddingTop: '0px' }) as CSSStyleDeclaration)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      callback(0)
      return 1
    })
    layOut(100, { 'edit-meeting.attendees': [200, 24] })
    const page = await mountRail()
    expect((ticksOf(page)[4]!.element as HTMLElement).style.top).toBe('112px')

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      // Two keystrokes close together, each well inside the other's wait: neither should measure on its own.
      layOut(100, { 'edit-meeting.attendees': [221, 24] })
      await page.setProps({ drafts: drafts({ 'meeting.attendees': 'Ana, Ben, Cleo' }) })
      vi.advanceTimersByTime(80)
      await nextTick()
      expect((ticksOf(page)[4]!.element as HTMLElement).style.top).toBe('112px')

      layOut(100, { 'edit-meeting.attendees': [242, 24] })
      await page.setProps({ drafts: drafts({ 'meeting.attendees': 'Ana, Ben, Cleo, Dev' }) })
      vi.advanceTimersByTime(80)
      await nextTick()
      expect((ticksOf(page)[4]!.element as HTMLElement).style.top).toBe('112px')

      // Once the pause is long enough, one measurement finds wherever the typing actually left things.
      vi.advanceTimersByTime(200)
      await nextTick()
      expect((ticksOf(page)[4]!.element as HTMLElement).style.top).toBe('154px')
    } finally {
      vi.useRealTimers()
    }
  })

  it('has no ticks on a page with no fill spots', async () => {
    const page = await mountRail({ layout: null, fields: [], drafts: {} })
    expect(ticksOf(page)).toHaveLength(0)
  })

  it('takes a click on a tick to the spot, the table to its first row, or to Add row while it has none', async () => {
    const page = await mountRail()
    await ticksOf(page)[3]!.trigger('click')
    expect(document.activeElement?.id).toBe('edit-meeting.location')
    await ticksOf(page)[6]!.trigger('click')
    expect(document.activeElement?.id).toBe('edit-action.item.task-0')

    await page.setProps({ drafts: drafts({ 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }) })
    await nextTick()
    expect(ticksOf(page)).toHaveLength(7)
    await ticksOf(page)[6]!.trigger('click')
    expect(document.activeElement?.hasAttribute('data-add-row')).toBe(true)
  })
})

// ---- What Brownie proposes ----------------------------------------------------------------------------

describe('DocumentPage: a preview of what Brownie proposes', () => {
  it('hands each spot what is proposed for it, a repeated place row by row, and nothing without a preview', () => {
    const previews = (page: VueWrapper) => Object.fromEntries(page.findAllComponents(FillSpot).map((spot) => [spot.props('inputId'), spot.props('preview')]))
    const none = previews(mountPage())
    expect(Object.values(none).every((value) => value === null)).toBe(true)
    wrapper!.unmount()

    expect(Object.values(previews(mountPage({ previewDrafts: null }))).every((value) => value === null)).toBe(true)
    wrapper!.unmount()

    const page = mountPage({ previewDrafts: { 'meeting.location': 'Room 204', 'action.item.task': ['Order the kit', 'Book the room'], 'action.item.due': ['2026-10-13'] } })
    const shown = previews(page)
    expect(shown['edit-meeting.location']).toBe('Room 204')
    expect(shown['edit-meeting.title']).toBeNull()
    expect(shown['edit-action.item.task-0']).toBe('Order the kit')
    expect(shown['edit-action.item.task-1']).toBe('Book the room')
    // Only the first row has a proposed due date.
    expect(shown['edit-action.item.due-0']).toBe('2026-10-13')
    expect(shown['edit-action.item.due-1']).toBeNull()
  })

  it('is never written: typing and the values the page holds are left as they are', () => {
    const page = mountPage({ previewDrafts: { 'meeting.title': 'Something else' } })
    const title = page.findAllComponents(FillSpot).find((spot) => spot.props('inputId') === 'edit-meeting.title')!
    expect(title.props('value')).toBe('Q3 planning')
    expect(page.emitted('update-scalar')).toBeUndefined()
  })

  it('still counts a gap that holds a proposal as a gap, so the next empty spot walks through the proposals too', async () => {
    const page = mountPage({ previewDrafts: { 'meeting.organization': 'Harborview Robotics Club' } })
    await page.findAll('button').find((button) => button.text() === 'Next empty spot')!.trigger('click')
    expect(document.activeElement?.id).toBe('edit-meeting.organization')
  })

  it('treats a blank proposal as none', () => {
    const page = mountPage({ previewDrafts: { 'meeting.location': '   ', 'action.item.owner': ['', '  '] } })
    expect(spotsWhere(page, (spot) => spot.props('preview') !== null)).toEqual([])
  })

  it('draws the rows a proposal carries that the document does not have yet, so they can be seen first', async () => {
    const empty = { 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }
    const page = mountPage({
      drafts: drafts(empty),
      previewDrafts: { 'action.item.task': ['One', 'Two', 'Three'], 'action.item.owner': ['A', 'B', 'C'], 'action.item.due': ['2026-10-01', '2026-10-02', '2026-10-03'] },
    })
    expect(page.findAll('.document-page__main-part tr')).toHaveLength(4)
    expect(page.text()).not.toContain('No rows yet.')
    expect(page.find('[id="edit-action.item.task-2"]').exists()).toBe(true)

    // Hiding the preview takes them away again.
    await page.setProps({ previewDrafts: null })
    expect(page.findAll('.document-page__main-part tr')).toHaveLength(2)
    expect(page.text()).toContain('No rows yet.')
  })

  it('does not count a proposal as filled, and does not flag the blank date of a row only proposed', async () => {
    const empty = { 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }
    const page = mountPage({
      drafts: drafts(empty),
      previewDrafts: { 'meeting.location': 'Room 204', 'action.item.task': ['One'], 'action.item.owner': ['A'], 'action.item.due': ['2026-10-01'] },
    })
    expect(page.get('.document-page__summary').text()).toBe('3 of 7 fill spots filled')
    const due = page.findAllComponents(FillSpot).find((spot) => spot.props('inputId') === 'edit-action.item.due-0')!
    expect(due.props('attention')).toBe(false)
    await nextTick()
    expect(page.findAll('.document-page__tick').at(-1)!.classes()).toContain('document-page__tick--open')
  })
})

// ---- Questions ----------------------------------------------------------------------------------------

describe('DocumentPage: questions Brownie has asked', () => {
  it('tells a spot how many are open, a repeated place on its first row only', () => {
    const page = mountPage({ questionCounts: { 'meeting.location': 2, 'action.item.owner': 1, 'meeting.title': 0 } })
    const counts = Object.fromEntries(page.findAllComponents(FillSpot).map((spot) => [spot.props('inputId'), spot.props('questionCount')]))
    expect(counts['edit-meeting.location']).toBe(2)
    expect(counts['edit-action.item.owner-0']).toBe(1)
    expect(counts['edit-action.item.owner-1'] ?? 0).toBe(0)
    expect(counts['edit-meeting.title'] ?? 0).toBe(0)
  })
})

// ---- Where the one Add row goes ---------------------------------------------------------------------

describe('DocumentPage: the way to the first row', () => {
  const addRows = (page: VueWrapper) => page.findAll('button').filter((button) => button.text() === 'Add row')

  it('puts the one Add row in the first line that stands in for rows, however many there are', () => {
    const twice = paragraph(
      [text('Task: '), spot('action.item.task', '[task]'), text('   Owner: '), spot('action.item.owner', '[owner]'), text('   Due: '), spot('action.item.due', '[due date]')],
      { repeating: true },
    )
    const layout: TemplateLayoutResponse = {
      ...MINUTES_WITH_LIST,
      parts: MINUTES_WITH_LIST.parts.map((part) => (part.kind === 'MAIN_DOCUMENT' ? { ...part, blocks: [...part.blocks, twice] } : part)),
    }
    const page = mountPage({ layout, drafts: drafts({ 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }) })
    expect(page.findAll('.document-page__no-rows')).toHaveLength(2)
    expect(addRows(page)).toHaveLength(1)
    expect(page.findAll('.document-page__no-rows')[0]!.find('button').exists()).toBe(true)
  })

  it('moves to the line below the rows once there are some, and says why when a row is locked', async () => {
    const page = mountPage({ drafts: drafts({ 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }), rowsLocked: true })
    expect(page.get('.document-page__no-rows button').attributes('disabled')).toBeDefined()
    expect(page.get('.document-page__no-rows').text()).toContain('A row is locked, so rows cannot be added until it is unlocked.')

    await page.setProps({ drafts: drafts() })
    expect(page.find('.document-page__no-rows').exists()).toBe(false)
    expect(addRows(page)).toHaveLength(1)
    expect(page.get('.document-page__table-wrap').element.parentElement!.contains(addRows(page)[0]!.element)).toBe(true)
  })

  it('shows a table Brownie found as found while it has no rows, in words for assistive technology too', async () => {
    const empty = { 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }
    const page = mountPage({ drafts: drafts(empty), foundFieldIds: new Set(['action.item.owner']) })
    expect(page.get('.document-page__table-wrap').classes()).toContain('document-page__table-wrap--found')
    expect(page.get('.document-page__no-rows').text()).toContain('Found by Brownie.')

    await page.setProps({ foundFieldIds: new Set<string>() })
    expect(page.get('.document-page__table-wrap').classes()).not.toContain('document-page__table-wrap--found')
    expect(page.get('.document-page__no-rows').text()).not.toContain('Found by Brownie.')
  })

  it('moves focus to the first row\'s first spot when the way to it was in the empty line, which goes with it', async () => {
    const empty = { 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }
    const page = mountPage({
      drafts: drafts(empty),
      'onAdd-row': () => {
        void page.setProps({ drafts: drafts({ 'action.item.task': [''], 'action.item.owner': [''], 'action.item.due': [''] }) })
      },
    })
    await page.get('.document-page__no-rows button').trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(page.find('.document-page__no-rows').exists()).toBe(false)
    expect(document.activeElement?.id).toBe('edit-action.item.task-0')
  })

  it('passes an accessibility scan with the status line, an empty table and a preview', async () => {
    const empty = { 'action.item.task': [], 'action.item.owner': [], 'action.item.due': [] }
    const page = mountPage({
      drafts: drafts(empty),
      canAddSpots: true,
      foundFieldIds: new Set(['action.item.owner']),
      previewDrafts: { 'meeting.location': 'Room 204' },
      questionCounts: { 'meeting.organization': 1 },
    })
    expect(await axe(page.element)).toHaveNoViolations()
  })
})

describe('FillSpot', () => {
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
