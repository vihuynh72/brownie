import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import VersionHistoryDialog from '@/components/workspace/VersionHistoryDialog.vue'
import { axe } from '@/test/axe'
import type { ApiError, DocumentRevisionResponse, RestoreRevisionResponse } from '@/api/client'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    listDocumentRevisions: vi.fn(),
    getDocumentRevision: vi.fn(),
    restoreRevision: vi.fn(),
    listMcpToolCalls: vi.fn(),
    listMcpGrants: vi.fn(),
    getCapabilities: vi.fn(),
  }
})

import {
  ApiRequestError,
  getCapabilities,
  getDocumentRevision,
  listDocumentRevisions,
  listMcpGrants,
  listMcpToolCalls,
  restoreRevision,
  type CapabilitiesResponse,
  type McpGrantResponse,
  type McpToolCallResponse,
} from '@/api/client'
import { resetCapabilitiesCache } from '@/capabilities'

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function problem(status: number, code: string, detail: string): ApiError {
  return { status, title: code, detail, code, correlationId: 'c', fields: [], recoveryActions: [] }
}

const CAPABILITIES: CapabilitiesResponse = {
  maxUploadBytes: 10485760,
  uploadMediaTypes: [],
  assistSourceMediaTypes: [],
  templateMediaTypes: [],
  trashRetentionDays: 30,
  mcpEnabled: false,
}

const REVISION_1: DocumentRevisionResponse = {
  id: 11,
  revisionNumber: 1,
  fields: {
    'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Weekly Sync', evidenceSourceSpanIds: [] },
    'meeting.location': { type: 'TEXT', cardinality: 'SCALAR', value: 'Room A', evidenceSourceSpanIds: [] },
  },
  contentHash: 'a'.repeat(64),
  editReason: 'Created from the template.',
  createdAt: '2026-03-01T09:00:00Z',
}

const REVISION_2: DocumentRevisionResponse = {
  id: 12,
  revisionNumber: 2,
  parentRevisionId: 11,
  fields: {
    'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Weekly Sync', evidenceSourceSpanIds: [] },
    'meeting.location': { type: 'TEXT', cardinality: 'SCALAR', value: 'Room B', evidenceSourceSpanIds: [] },
  },
  contentHash: 'b'.repeat(64),
  editReason: 'Edited the location.',
  createdAt: '2026-03-02T09:00:00Z',
}

const REVISION_3: DocumentRevisionResponse = {
  id: 13,
  revisionNumber: 3,
  parentRevisionId: 12,
  fields: {
    'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Weekly Sync', evidenceSourceSpanIds: [] },
    'meeting.location': { type: 'TEXT', cardinality: 'SCALAR', value: 'Room C', evidenceSourceSpanIds: [] },
    'meeting.notes': { type: 'TEXT', cardinality: 'SCALAR', value: 'Bring the budget.', evidenceSourceSpanIds: [] },
  },
  contentHash: 'c'.repeat(64),
  editReason: 'Edited in the workspace.',
  createdAt: '2026-03-03T09:00:00Z',
}

const RESTORED: DocumentRevisionResponse = {
  ...REVISION_1,
  id: 14,
  revisionNumber: 4,
  parentRevisionId: 13,
  contentHash: 'd'.repeat(64),
  editReason: 'Restored version 1.',
  createdAt: '2026-03-04T09:00:00Z',
}

const LABELS: Record<string, string> = {
  'meeting.title': 'Meeting title',
  'meeting.location': 'Meeting location',
  'meeting.notes': 'Meeting notes',
  'meeting.date': 'Meeting date',
  'action.item.task': 'Action item task',
  'action.item.owner': 'Action item owner',
}

function fieldLabel(fieldId: string): string {
  return LABELS[fieldId] ?? fieldId
}

const LOCKED = { authorship: 'USER_AUTHORED', evidenceSupport: 'MISSING', validation: 'NOT_RUN', review: 'ACCEPTED', lock: 'EXPLICITLY_LOCKED' } as const

/** The current version with its location locked: a restore would leave the location as it is. */
const LOCATION_LOCKED: DocumentRevisionResponse = {
  ...REVISION_3,
  fields: {
    ...REVISION_3.fields,
    'meeting.location': { ...REVISION_3.fields['meeting.location'], fieldState: LOCKED },
  },
}

type DialogWrapper = VueWrapper<InstanceType<typeof VersionHistoryDialog>>
type Exposed = { open(): void; close(): void }

let opener: HTMLButtonElement
let wrapper: DialogWrapper | null = null

function mountDialog(props: Partial<{ currentRevision: DocumentRevisionResponse; unsavedWork: boolean }> = {}): DialogWrapper {
  opener = document.createElement('button')
  opener.textContent = 'Version history'
  document.body.append(opener)
  opener.focus()
  wrapper = mount(VersionHistoryDialog, {
    attachTo: document.body,
    props: {
      workspaceId: 7,
      documentId: 1,
      currentRevision: REVISION_3,
      fieldLabel,
      unsavedWork: false,
      ...props,
    },
  })
  return wrapper
}

async function openDialog(props: Parameters<typeof mountDialog>[0] = {}): Promise<DialogWrapper> {
  const mounted = mountDialog(props)
  ;(mounted.vm as unknown as Exposed).open()
  await flushPromises()
  return mounted
}

function row(mounted: DialogWrapper, versionNumber: number) {
  const found = mounted.findAll('.revision-row').find((candidate) => candidate.find('.revision-row__name').text() === `Version ${versionNumber}`)
  if (!found) throw new Error(`No row for version ${versionNumber}`)
  return found
}

/** What a person does to pick a version: choose its radio button (clicking its words does the same). */
async function chooseVersion(mounted: DialogWrapper, versionNumber: number): Promise<void> {
  await row(mounted, versionNumber).get('input[type="radio"]').setValue(true)
  await flushPromises()
}

function radioChecked(mounted: DialogWrapper, versionNumber: number): boolean {
  return (row(mounted, versionNumber).get('input[type="radio"]').element as HTMLInputElement).checked
}

function buttonNamed(mounted: DialogWrapper, name: string) {
  const found = mounted.findAll('button').find((button) => (button.attributes('aria-label') ?? button.text()) === name)
  if (!found) throw new Error(`No button named "${name}"`)
  return found
}

function hasButton(mounted: DialogWrapper, name: string): boolean {
  return mounted.findAll('button').some((button) => (button.attributes('aria-label') ?? button.text()) === name)
}

/** Chooses a version and asks for its comparison, the way a person would. */
async function compareVersion(mounted: DialogWrapper, versionNumber: number, compared: DocumentRevisionResponse): Promise<void> {
  vi.mocked(getDocumentRevision).mockResolvedValue(compared)
  await chooseVersion(mounted, versionNumber)
  await buttonNamed(mounted, `Compare version ${versionNumber} with the current version`).trigger('click')
  await flushPromises()
}

/** The rows of the comparison, without its column heads. */
function comparisonRows(mounted: DialogWrapper) {
  return mounted.findAll('[role="table"] [role="row"]').filter((candidate) => candidate.find('[role="rowheader"]').exists())
}

function rowNamed(mounted: DialogWrapper, name: string) {
  return comparisonRows(mounted).find((candidate) => candidate.get('[role="rowheader"]').text() === name)
}

function cellsOf(candidate: { findAll(selector: string): { text(): string }[] }): string[] {
  return candidate.findAll('[role="cell"]').map((cell) => cell.text())
}

async function askToRestoreVersion1(mounted: DialogWrapper): Promise<void> {
  await chooseVersion(mounted, 1)
  await buttonNamed(mounted, 'Restore version 1').trigger('click')
  await flushPromises()
}

const timeOf = (moment: Date) => new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(moment)
const monthDayOf = (moment: Date) => new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(moment)
const monthDayYearOf = (moment: Date) => new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).format(moment)

describe('VersionHistoryDialog', () => {
  beforeEach(() => {
    vi.mocked(listDocumentRevisions).mockReset().mockResolvedValue([REVISION_1, REVISION_2, REVISION_3])
    vi.mocked(getDocumentRevision).mockReset()
    vi.mocked(restoreRevision).mockReset()
    vi.mocked(listMcpToolCalls).mockReset().mockResolvedValue([])
    vi.mocked(listMcpGrants).mockReset().mockResolvedValue([])
    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockReset().mockResolvedValue({ ...CAPABILITIES, mcpEnabled: true })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    opener.remove()
    resetCapabilitiesCache()
    vi.useRealTimers()
  })

  it('renders nothing inside the dialog until it is opened', () => {
    const mounted = mountDialog()

    expect(mounted.find('dialog').attributes('open')).toBeUndefined()
    expect(mounted.findAll('button')).toHaveLength(0)
    expect(listDocumentRevisions).not.toHaveBeenCalled()
  })

  it('says it is loading until the versions arrive, then lists them', async () => {
    let finish!: (revisions: DocumentRevisionResponse[]) => void
    vi.mocked(listDocumentRevisions).mockReset().mockReturnValue(new Promise<DocumentRevisionResponse[]>((resolve) => (finish = resolve)))
    const mounted = await openDialog()

    expect(mounted.text()).toContain('Loading version history…')
    expect(mounted.find('[aria-live="polite"]').text()).toBe('Loading version history…')
    expect(mounted.find('[role="radiogroup"]').exists()).toBe(false)

    finish([REVISION_1, REVISION_2, REVISION_3])
    await flushPromises()

    expect(mounted.text()).not.toContain('Loading version history…')
    expect(mounted.find('[aria-live="polite"]').text()).toBe('')
    expect(mounted.findAll('.revision-row')).toHaveLength(3)
  })

  it('lists every version newest first, names each by its number and marks the current one', async () => {
    const mounted = await openDialog()

    expect(mounted.find('dialog').attributes('open')).toBeDefined()
    expect(mounted.find('dialog').attributes('aria-labelledby')).toBe('version-history-heading')
    expect(mounted.find('#version-history-heading').text()).toBe('Version history')
    expect(mounted.find('.dialog__subtitle').text()).toBe('Every change makes a version. Restoring one keeps your current version in the history.')
    expect(listDocumentRevisions).toHaveBeenCalledWith(7, 1)

    // The versions are one choice, named for what they are, so the keyboard needs a single stop for all of them.
    const group = mounted.get('[role="radiogroup"]')
    expect(group.attributes('aria-label')).toBe('Versions')
    expect(group.findAll('input[type="radio"]')).toHaveLength(3)

    const rows = mounted.findAll('.revision-row')
    expect(rows.map((candidate) => candidate.find('.revision-row__name').text())).toEqual(['Version 3', 'Version 2', 'Version 1'])
    expect(rows.map((candidate) => candidate.find('.tag').exists())).toEqual([true, false, false])
    expect(rows[0].get('.tag').text()).toBe('Current version')
    expect(rows[1].find('.revision-row__summary').text()).toBe('Edited the location.')
    expect(rows[1].find('time').attributes('datetime')).toBe('2026-03-02T09:00:00Z')

    // A radio button is read with its label, so the whole card (number, tag, summary and time) is its name.
    const label = rows[2].get('label')
    expect(label.find('input[type="radio"]').exists()).toBe(true)
    expect(label.get('.revision-row__name').text()).toBe('Version 1')
    expect(label.get('.revision-row__summary').text()).toBe('Created from the template.')
    expect(label.find('time').exists()).toBe(true)
    // The close button is the first thing the keyboard reaches, and it is where focus lands on opening.
    expect(document.activeElement?.textContent?.trim()).toBe('Close')
  })

  it('tells when each version was made: today with the time, an earlier day by its date, the year only when it is not this one', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 7, 15, 58))
    const today = new Date(2026, 9, 7, 15, 42)
    const thisYear = new Date(2026, 2, 3, 9, 5)
    const lastYear = new Date(2025, 11, 30, 18, 20)
    vi.mocked(listDocumentRevisions)
      .mockReset()
      .mockResolvedValue([
        { ...REVISION_1, createdAt: lastYear.toISOString() },
        { ...REVISION_2, createdAt: thisYear.toISOString() },
        { ...REVISION_3, createdAt: today.toISOString() },
      ])
    const mounted = await openDialog()

    expect(mounted.findAll('.revision-row time').map((time) => time.text())).toEqual([
      `Today, ${timeOf(today)}`,
      `${monthDayOf(thisYear)}, ${timeOf(thisYear)}`,
      `${monthDayYearOf(lastYear)}, ${timeOf(lastYear)}`,
    ])
  })

  it('opens on the version before the current one, and only the chosen version offers Compare and Restore', async () => {
    const mounted = await openDialog()

    expect([3, 2, 1].map((number) => radioChecked(mounted, number))).toEqual([false, true, false])
    expect(mounted.findAll('.revision-row button')).toHaveLength(2)
    // Every button's name says which version it acts on, starting with the words the button shows.
    expect(row(mounted, 2).findAll('button').map((button) => [button.text(), button.attributes('aria-label')])).toEqual([
      ['Compare', 'Compare version 2 with the current version'],
      ['Restore', 'Restore version 2'],
    ])
    expect(mounted.get('.version-history__detail').text()).toBe('Choose Compare to see how version 2 differs from now.')

    // The buttons move with the choice. The current version cannot be restored, only compared.
    await chooseVersion(mounted, 3)
    expect([3, 2, 1].map((number) => radioChecked(mounted, number))).toEqual([true, false, false])
    expect(row(mounted, 2).findAll('button')).toHaveLength(0)
    expect(row(mounted, 3).findAll('button').map((button) => button.attributes('aria-label'))).toEqual(['Compare version 3 with the current version'])
    expect(hasButton(mounted, 'Restore version 3')).toBe(false)
  })

  it('opens on the only version there is, and says there is nothing to restore', async () => {
    vi.mocked(listDocumentRevisions).mockReset().mockResolvedValue([REVISION_3])
    const mounted = await openDialog()

    expect(mounted.findAll('.revision-row')).toHaveLength(1)
    expect(radioChecked(mounted, 3)).toBe(true)
    expect(row(mounted, 3).findAll('button').map((button) => button.text())).toEqual(['Compare'])
    expect(mounted.text()).toContain('There is only one version, so there is nothing to restore.')
  })

  it('says so when the document has no saved versions', async () => {
    vi.mocked(listDocumentRevisions).mockReset().mockResolvedValue([])
    const mounted = await openDialog()

    expect(mounted.text()).toContain('This document has no saved versions.')
    expect(mounted.find('[role="radiogroup"]').exists()).toBe(false)
    expect(mounted.get('.version-history__detail').text()).toBe('')
  })

  it('compares a version with now field by field, listing what changed and saying so in words as well as colour', async () => {
    const mounted = await openDialog()

    await compareVersion(mounted, 1, REVISION_1)

    expect(getDocumentRevision).toHaveBeenCalledWith(7, 1, 11)
    expect(mounted.get('h3').text()).toBe('Version 1 compared with now (version 3)')
    expect(mounted.get('.compare__counts').text()).toBe('2 changed · 1 the same')

    // The fill spots that differ come first, each tagged; the one that is the same is not listed until asked for.
    const table = mounted.get('[role="table"]')
    expect(table.attributes('aria-labelledby')).toBe(mounted.get('h3').attributes('id'))
    expect(table.findAll('[role="columnheader"]').map((heading) => heading.text())).toEqual(['Fill spot', 'Version 1', 'Now (version 3)', 'Status'])
    expect(comparisonRows(mounted).map((candidate) => candidate.get('[role="rowheader"]').text())).toEqual(['Meeting location', 'Meeting notes'])

    const location = rowNamed(mounted, 'Meeting location')!
    expect(cellsOf(location)).toEqual(['Room A', 'Room C', 'Changed'])
    expect(location.get('.tag').classes()).toContain('tag--honey')
    // A fill spot the older version never held reads as empty, not as a dash a screen reader would spell out.
    const notes = rowNamed(mounted, 'Meeting notes')!
    expect(notes.get('.compare-cell__empty').text()).toBe('Empty')
    expect(cellsOf(notes)).toEqual(['Empty', 'Bring the budget.', 'Changed'])

    expect(mounted.find('[aria-live="polite"]').text()).toBe('Showing version 1 compared with now. 2 changed, 1 the same.')
  })

  it('lists the unchanged fill spots too, after the changed ones, when asked', async () => {
    const mounted = await openDialog()
    await compareVersion(mounted, 1, REVISION_1)

    const toggle = buttonNamed(mounted, 'Show the same too')
    expect(toggle.attributes('aria-pressed')).toBe('false')

    await toggle.trigger('click')

    expect(toggle.attributes('aria-pressed')).toBe('true')
    expect(comparisonRows(mounted).map((candidate) => candidate.get('[role="rowheader"]').text())).toEqual(['Meeting location', 'Meeting notes', 'Meeting title'])
    const title = rowNamed(mounted, 'Meeting title')!
    expect(cellsOf(title)).toEqual(['Weekly Sync', 'Weekly Sync', 'Same'])
    expect(title.get('.tag').classes()).not.toContain('tag--honey')

    await toggle.trigger('click')
    expect(toggle.attributes('aria-pressed')).toBe('false')
    expect(comparisonRows(mounted)).toHaveLength(2)
  })

  it('offers no way to list the same ones when nothing is the same', async () => {
    const mounted = await openDialog({
      currentRevision: { ...REVISION_3, fields: { 'meeting.location': REVISION_3.fields['meeting.location'] } },
    })

    await compareVersion(mounted, 1, { ...REVISION_1, fields: { 'meeting.location': REVISION_1.fields['meeting.location'] } })

    expect(mounted.get('.compare__counts').text()).toBe('1 changed · 0 the same')
    expect(hasButton(mounted, 'Show the same too')).toBe(false)
    expect(comparisonRows(mounted)).toHaveLength(1)
  })

  it('says so when a version holds just what the document holds now', async () => {
    const mounted = await openDialog()

    await compareVersion(mounted, 3, REVISION_3)

    expect(mounted.get('.compare__counts').text()).toBe('0 changed · 3 the same')
    expect(mounted.find('[role="table"]').exists()).toBe(false)
    expect(mounted.get('.version-history__detail').text()).toContain('Nothing is different: every fill spot is the same as now.')
    // The unchanged ones are still there to be listed.
    await buttonNamed(mounted, 'Show the same too').trigger('click')
    expect(comparisonRows(mounted)).toHaveLength(3)
  })

  it('reads dates the way the export prints them, each item of a repeated fill spot on its own line, and compares what is stored', async () => {
    const dated = (value: string): DocumentRevisionResponse['fields'][string] => ({ type: 'DATE', cardinality: 'SCALAR', value, evidenceSourceSpanIds: [] })
    const owners = (values: string[]): DocumentRevisionResponse['fields'][string] => ({ type: 'TEXT', cardinality: 'REPEATED', values, evidenceSourceSpanIds: [] })
    const mounted = await openDialog({
      currentRevision: {
        ...REVISION_3,
        fields: { 'meeting.date': dated('2026-10-13'), 'action.item.owner': owners(['Ana', 'Theo']), 'meeting.title': REVISION_3.fields['meeting.title'] },
      },
    })

    await compareVersion(mounted, 1, {
      ...REVISION_1,
      fields: { 'meeting.date': dated('2026-10-06'), 'action.item.owner': owners(['Ana']), 'meeting.title': REVISION_1.fields['meeting.title'] },
    })

    expect(cellsOf(rowNamed(mounted, 'Meeting date')!)).toEqual(['October 6, 2026', 'October 13, 2026', 'Changed'])
    expect(cellsOf(rowNamed(mounted, 'Action item owner')!)).toEqual(['Ana', 'Ana\nTheo', 'Changed'])
    expect(rowNamed(mounted, 'Meeting title')).toBeUndefined()
  })

  it('says once, under the table, which of the changed fill spots a restore would leave alone because they are locked', async () => {
    const locked: DocumentRevisionResponse = {
      ...LOCATION_LOCKED,
      fields: {
        ...LOCATION_LOCKED.fields,
        // One locked row keeps every repeated field as it is, because the rows only make sense together.
        'action.item.task': { type: 'TEXT', cardinality: 'REPEATED', values: ['Book room'], itemFieldStates: [LOCKED] },
        'action.item.owner': { type: 'TEXT', cardinality: 'REPEATED', values: ['Ana'], itemFieldStates: [null] },
      },
    }
    const mounted = await openDialog({ currentRevision: locked })

    await compareVersion(mounted, 1, REVISION_1)

    expect(mounted.get('.compare__note').text()).toBe(
      'Action item owner, Action item task and Meeting location are locked, so a restore keeps their current values.',
    )
  })

  it('names a single locked fill spot in the singular', async () => {
    const mounted = await openDialog({ currentRevision: LOCATION_LOCKED })

    await compareVersion(mounted, 1, REVISION_1)

    expect(mounted.get('.compare__note').text()).toBe('Meeting location is locked, so a restore keeps its current value.')
  })

  it('says nothing of a locked fill spot that holds the same words in both versions, since a restore has nothing to leave alone there', async () => {
    const titleLocked: DocumentRevisionResponse = {
      ...REVISION_3,
      fields: { ...REVISION_3.fields, 'meeting.title': { ...REVISION_3.fields['meeting.title'], fieldState: LOCKED } },
    }
    const mounted = await openDialog({ currentRevision: titleLocked })

    await compareVersion(mounted, 1, REVISION_1)

    expect(mounted.find('.compare__note').exists()).toBe(false)
  })

  it('lets go of the comparison and of the question about restoring when another version is chosen', async () => {
    const mounted = await openDialog()
    await compareVersion(mounted, 1, REVISION_1)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()
    expect(mounted.find('[role="table"]').exists()).toBe(true)
    expect(mounted.find('.restore-card').exists()).toBe(true)

    await chooseVersion(mounted, 2)

    expect(mounted.find('[role="table"]').exists()).toBe(false)
    expect(mounted.find('.restore-card').exists()).toBe(false)
    expect(mounted.get('.version-history__detail').text()).toBe('Choose Compare to see how version 2 differs from now.')
    expect(mounted.find('[aria-live="polite"]').text()).toBe('')
    // Back on the first one, nothing was kept: it has to be asked for again.
    await chooseVersion(mounted, 1)
    expect(mounted.find('[role="table"]').exists()).toBe(false)
    expect(restoreRevision).not.toHaveBeenCalled()
  })

  it('ignores a slower compare response once another version has been chosen and compared', async () => {
    const mounted = await openDialog()
    let resolveFirst!: (revision: DocumentRevisionResponse) => void
    vi.mocked(getDocumentRevision)
      .mockReturnValueOnce(new Promise<DocumentRevisionResponse>((resolve) => (resolveFirst = resolve)))
      .mockResolvedValueOnce(REVISION_2)

    await chooseVersion(mounted, 1)
    await buttonNamed(mounted, 'Compare version 1 with the current version').trigger('click')
    expect(mounted.get('.version-history__detail').text()).toContain('Loading version 1…')
    expect(buttonNamed(mounted, 'Compare version 1 with the current version').attributes('aria-disabled')).toBe('true')
    await chooseVersion(mounted, 2)
    await buttonNamed(mounted, 'Compare version 2 with the current version').trigger('click')
    await flushPromises()
    expect(mounted.text()).toContain('Version 2 compared with now')

    resolveFirst(REVISION_1)
    await flushPromises()

    expect(mounted.text()).toContain('Version 2 compared with now')
    expect(mounted.text()).not.toContain('Version 1 compared with now')
  })

  it('never puts a bare status number in a sentence when the list or a version cannot be loaded, and can ask again', async () => {
    vi.mocked(listDocumentRevisions).mockReset().mockRejectedValueOnce(new ApiRequestError(502, undefined))
    const mounted = await openDialog()

    expect(mounted.find('[role="alert"]').text()).toBe('Brownie could not be reached. It may not be running; try again in a minute.')
    expect(mounted.text()).not.toContain('502')

    vi.mocked(listDocumentRevisions).mockResolvedValue([REVISION_1, REVISION_2, REVISION_3])
    await buttonNamed(mounted, 'Try again').trigger('click')
    await flushPromises()
    expect(mounted.findAll('.revision-row')).toHaveLength(3)
    expect(mounted.find('[role="alert"]').exists()).toBe(false)

    vi.mocked(getDocumentRevision).mockRejectedValue(new ApiRequestError(502, undefined))
    await chooseVersion(mounted, 1)
    await buttonNamed(mounted, 'Compare version 1 with the current version').trigger('click')
    await flushPromises()
    expect(mounted.find('.version-history__detail [role="alert"]').text()).toContain('Brownie could not be reached. It may not be running')
    expect(mounted.text()).not.toContain('502')
  })

  it('does not offer to try again when the server is older than this page or the session has ended', async () => {
    vi.mocked(listDocumentRevisions).mockReset().mockRejectedValue(new ApiRequestError(401, problem(401, 'UNAUTHENTICATED', 'Sign in.')))
    const mounted = await openDialog()

    expect(mounted.find('[role="alert"]').text()).toBe('Your session has ended. Sign in again to carry on.')
    expect(hasButton(mounted, 'Try again')).toBe(false)
  })

  it('asks in the panel on the right, takes both buttons out of the row, restores against the current version, tells the page, and closes', async () => {
    const mounted = await openDialog()
    vi.mocked(restoreRevision).mockResolvedValue({ revision: RESTORED, keptLockedFieldIds: ['meeting.title'] } satisfies RestoreRevisionResponse)

    await askToRestoreVersion1(mounted)

    const question = mounted.get('.restore-card__question')
    expect(question.text()).toBe('Restore version 1? Your current version stays in the history.')
    expect(document.activeElement).toBe(question.element)
    expect(mounted.get('.restore-card').attributes('role')).toBe('group')
    expect(mounted.get('.restore-card').attributes('aria-labelledby')).toBe(question.attributes('id'))
    expect(mounted.get('.version-history__detail').element.contains(question.element)).toBe(true)
    expect(restoreRevision).not.toHaveBeenCalled()
    // Both of the row's own buttons gave way to the question, so there is one way to say yes, in the panel,
    // and Compare cannot steal the status announcement the restore is about to make.
    expect(row(mounted, 1).findAll('button').map((button) => button.text())).toEqual([])
    expect(mounted.get('.restore-card').findAll('button').map((button) => button.text())).toEqual(['Restore version 1', 'Keep the current version'])

    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()

    expect(restoreRevision).toHaveBeenCalledTimes(1)
    const call = vi.mocked(restoreRevision).mock.calls[0]
    expect(call.slice(0, 4)).toEqual([7, 1, 11, 13])
    expect(call[4]).toMatch(/^[0-9a-f-]{36}$/)
    expect(call).toHaveLength(5)

    expect(mounted.emitted('restored')).toEqual([[{ revision: RESTORED, keptLockedFieldIds: ['meeting.title'] }]])
    expect(mounted.emitted('closed')).toHaveLength(1)
    expect(mounted.find('dialog').attributes('open')).toBeUndefined()
    expect(mounted.findAll('button')).toHaveLength(0)
    expect(document.activeElement).toBe(opener)
  })

  it('treats a restore answer without the kept-field list, from an older server, as keeping none', async () => {
    const mounted = await openDialog()
    vi.mocked(restoreRevision).mockResolvedValue({ revision: RESTORED } as RestoreRevisionResponse)

    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()

    expect(mounted.emitted('restored')).toEqual([[{ revision: RESTORED, keptLockedFieldIds: [] }]])
  })

  it('puts the focus back on the Restore button when the person keeps the current version', async () => {
    const mounted = await openDialog()

    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Keep the current version').trigger('click')
    await flushPromises()

    expect(mounted.find('.restore-card').exists()).toBe(false)
    expect(document.activeElement).toBe(buttonNamed(mounted, 'Restore version 1').element)
    expect(row(mounted, 1).findAll('button').map((button) => button.text())).toEqual(['Compare', 'Restore'])
    expect(restoreRevision).not.toHaveBeenCalled()
    expect(mounted.emitted('closed')).toBeUndefined()
  })

  it('says that locked fill spots keep their values when the question is asked before any comparison names them', async () => {
    const mounted = await openDialog({ currentRevision: LOCATION_LOCKED })

    await askToRestoreVersion1(mounted)
    expect(mounted.get('.restore-card').text()).toContain('Locked fill spots keep their current values.')

    // Compare is not offered while that question is open, so seeing the comparison first means keeping the
    // current version, comparing, then asking to restore again; with the comparison open, the note under its
    // table already names the locked fill spots, so the question does not say it twice.
    await buttonNamed(mounted, 'Keep the current version').trigger('click')
    await flushPromises()
    vi.mocked(getDocumentRevision).mockResolvedValue(REVISION_1)
    await buttonNamed(mounted, 'Compare version 1 with the current version').trigger('click')
    await flushPromises()
    await askToRestoreVersion1(mounted)
    expect(mounted.get('.compare__note').text()).toContain('Meeting location is locked')
    expect(mounted.get('.restore-card').text()).not.toContain('Locked fill spots keep')
  })

  it('does not mention locks in the question when nothing is locked', async () => {
    const mounted = await openDialog()

    await askToRestoreVersion1(mounted)

    expect(mounted.get('.restore-card').text()).not.toContain('Locked')
  })

  it('waits for unsaved changes to be saved before restoring', async () => {
    const mounted = await openDialog({ unsavedWork: true })

    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()

    expect(restoreRevision).not.toHaveBeenCalled()
    expect(mounted.find('[role="alert"]').text()).toBe('Save or discard your changes on the page first, then restore.')

    // Once the page has saved, the refusal no longer holds and restoring goes ahead.
    await mounted.setProps({ unsavedWork: false })
    expect(mounted.find('[role="alert"]').exists()).toBe(false)
    vi.mocked(restoreRevision).mockResolvedValue({ revision: RESTORED, keptLockedFieldIds: [] })
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()
    expect(restoreRevision).toHaveBeenCalledTimes(1)
  })

  it('says nothing was restored when the document moved on, reloads the list, and tells the page to reload', async () => {
    const mounted = await openDialog()
    vi.mocked(restoreRevision).mockRejectedValue(new ApiRequestError(412, problem(412, 'STALE_REVISION', 'Revision 13 is not current.')))

    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()

    expect(mounted.find('[role="alert"]').text()).toBe('This document changed since this list was loaded, so nothing was restored.')
    expect(listDocumentRevisions).toHaveBeenCalledTimes(2)
    expect(mounted.emitted('document-changed')).toHaveLength(1)
    expect(mounted.emitted('restored')).toBeUndefined()
    expect(mounted.emitted('closed')).toBeUndefined()
    // The question stays where it was, and so does the chosen version, so the focus the person had is not lost.
    expect(buttonNamed(mounted, 'Restore version 1').text()).toBe('Restore version 1')
    expect(radioChecked(mounted, 1)).toBe(true)
  })

  it('uses the common older-server sentence when the server has no restore route', async () => {
    const mounted = await openDialog()
    vi.mocked(restoreRevision).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'NOT_FOUND', 'No static resource api/v1/workspaces/7/documents/1/revisions/11/restore.')),
    )

    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()

    expect(mounted.find('[role="alert"]').text()).toBe(
      'The Brownie server that answered is older than this page and does not have a way to restore a version yet. ' +
        'Reloading will not change that: the server needs to be updated and restarted.',
    )
    expect(mounted.emitted('document-changed')).toBeUndefined()
  })

  it('says the document or version is gone when Brownie itself answers not found', async () => {
    const mounted = await openDialog()
    vi.mocked(restoreRevision).mockRejectedValue(new ApiRequestError(404, problem(404, 'NOT_FOUND', 'Document 1 was not found.')))

    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()

    expect(mounted.find('[role="alert"]').text()).toBe('This document or that version is no longer available, so nothing was restored.')
  })

  it.each([
    ['an ended session', new ApiRequestError(401, problem(401, 'UNAUTHENTICATED', 'Sign in.')), 'Your session has ended. Sign in again to carry on.'],
    ['a busy server', new ApiRequestError(503, problem(503, 'SERVICE_UNAVAILABLE', 'Storage is unavailable. Try again in a minute.')), 'Storage is unavailable. Try again in a minute.'],
    ['too many requests', new ApiRequestError(429, problem(429, 'RATE_LIMITED', 'Too many changes. Wait 30 seconds.')), 'Too many changes. Wait 30 seconds.'],
    ['a refusal it cannot explain', new ApiRequestError(400, problem(400, 'MALFORMED_REQUEST', 'Bad body.')), 'Could not restore that version. Try again.'],
  ])('explains %s without closing', async (_what, error, sentence) => {
    const mounted = await openDialog()
    vi.mocked(restoreRevision).mockRejectedValue(error)

    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()

    expect(mounted.find('[role="alert"]').text()).toBe(sentence)
    expect(mounted.emitted('closed')).toBeUndefined()
  })

  it('asks again with the same idempotency key after a lost answer, and with a new one once the document moved on', async () => {
    const mounted = await openDialog()
    vi.mocked(restoreRevision).mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()
    expect(mounted.find('[role="alert"]').text()).toBe('Brownie could not be reached. Check your connection, then try again.')

    vi.mocked(restoreRevision).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()

    const [first, second] = vi.mocked(restoreRevision).mock.calls
    expect(second[4]).toBe(first[4])

    await mounted.setProps({ currentRevision: { ...REVISION_3, id: 15, revisionNumber: 5 } })
    vi.mocked(restoreRevision).mockResolvedValueOnce({ revision: RESTORED, keptLockedFieldIds: [] })
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()

    const third = vi.mocked(restoreRevision).mock.calls[2]
    expect(third[3]).toBe(15)
    expect(third[4]).not.toBe(first[4])
  })

  it('reloads the list when the page saves a newer version while the dialog is open, keeping the chosen version', async () => {
    const mounted = await openDialog()
    await chooseVersion(mounted, 1)
    expect(listDocumentRevisions).toHaveBeenCalledTimes(1)

    const newer = { ...REVISION_3, id: 15, revisionNumber: 5 }
    vi.mocked(listDocumentRevisions).mockResolvedValue([REVISION_1, REVISION_2, REVISION_3, newer])
    await mounted.setProps({ currentRevision: newer })
    await flushPromises()

    expect(listDocumentRevisions).toHaveBeenCalledTimes(2)
    expect(mounted.findAll('.revision-row')).toHaveLength(4)
    expect(radioChecked(mounted, 1)).toBe(true)
    expect(row(mounted, 5).find('.tag').text()).toBe('Current version')
  })

  it('keeps the chosen version while a restore is under way, so the question it was asked on stays', async () => {
    const mounted = await openDialog()
    let finish!: (response: RestoreRevisionResponse) => void
    vi.mocked(restoreRevision).mockReturnValue(new Promise<RestoreRevisionResponse>((resolve) => (finish = resolve)))

    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()

    const radios = mounted.findAll('input[type="radio"]')
    expect(radios.map((radio) => radio.attributes('disabled') !== undefined)).toEqual([true, true, false])
    expect(mounted.find('[aria-live="polite"]').text()).toBe('Restoring version 1…')
    // Compare stays gone for the row being restored, so it cannot overwrite that announcement mid-restore.
    expect(row(mounted, 1).findAll('button')).toEqual([])

    finish({ revision: RESTORED, keptLockedFieldIds: [] })
    await flushPromises()
    expect(mounted.emitted('restored')).toHaveLength(1)
  })

  it('still tells the page about a restore that finished after the dialog was closed', async () => {
    const mounted = await openDialog()
    let finish!: (response: RestoreRevisionResponse) => void
    vi.mocked(restoreRevision).mockReturnValue(new Promise<RestoreRevisionResponse>((resolve) => (finish = resolve)))

    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    expect(buttonNamed(mounted, 'Restoring…').attributes('aria-disabled')).toBe('true')

    await mounted.find('dialog').trigger('keydown', { key: 'Escape' })
    expect(mounted.emitted('closed')).toHaveLength(1)

    finish({ revision: RESTORED, keptLockedFieldIds: [] })
    await flushPromises()

    expect(mounted.emitted('restored')).toEqual([[{ revision: RESTORED, keptLockedFieldIds: [] }]])
    expect(mounted.emitted('closed')).toHaveLength(1)
  })

  it('closes on Escape and on Close, putting the focus back where it was', async () => {
    const mounted = await openDialog()

    await mounted.find('dialog').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(mounted.emitted('closed')).toHaveLength(1)
    expect(mounted.find('dialog').attributes('open')).toBeUndefined()
    expect(document.activeElement).toBe(opener)

    ;(mounted.vm as unknown as Exposed).open()
    await flushPromises()
    expect(listDocumentRevisions).toHaveBeenCalledTimes(2)
    await buttonNamed(mounted, 'Close').trigger('click')
    await flushPromises()
    expect(mounted.emitted('closed')).toHaveLength(2)
    expect(document.activeElement).toBe(opener)
  })

  it('starts afresh each time it is opened, on the version before the current one with nothing compared', async () => {
    const mounted = await openDialog()
    await compareVersion(mounted, 1, REVISION_1)
    await buttonNamed(mounted, 'Show the same too').trigger('click')
    await buttonNamed(mounted, 'Close').trigger('click')
    await flushPromises()

    ;(mounted.vm as unknown as Exposed).open()
    await flushPromises()

    expect(radioChecked(mounted, 2)).toBe(true)
    expect(mounted.find('[role="table"]').exists()).toBe(false)
    vi.mocked(getDocumentRevision).mockResolvedValue(REVISION_1)
    await chooseVersion(mounted, 1)
    await buttonNamed(mounted, 'Compare version 1 with the current version').trigger('click')
    await flushPromises()
    expect(buttonNamed(mounted, 'Show the same too').attributes('aria-pressed')).toBe('false')
  })

  describe('in a browser with modal dialogs', () => {
    const showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '')
    })
    const close = vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open')
      this.dispatchEvent(new Event('close'))
    })

    beforeEach(() => {
      showModal.mockClear()
      close.mockClear()
      Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { value: showModal, configurable: true })
      Object.defineProperty(HTMLDialogElement.prototype, 'close', { value: close, configurable: true })
    })

    afterEach(() => {
      Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal')
      Reflect.deleteProperty(HTMLDialogElement.prototype, 'close')
    })

    it('opens as a real modal and turns the Escape request into its own close', async () => {
      const mounted = await openDialog()
      expect(showModal).toHaveBeenCalledTimes(1)
      expect(mounted.find('dialog').classes()).not.toContain('version-history--fallback')

      const cancel = new Event('cancel', { cancelable: true })
      mounted.find('dialog').element.dispatchEvent(cancel)
      await flushPromises()

      expect(cancel.defaultPrevented).toBe(true)
      expect(close).toHaveBeenCalledTimes(1)
      expect(mounted.emitted('closed')).toHaveLength(1)
      expect(document.activeElement).toBe(opener)
    })

    it('keeps up when the browser closes the dialog by itself', async () => {
      const mounted = await openDialog()

      mounted.find('dialog').element.removeAttribute('open')
      mounted.find('dialog').element.dispatchEvent(new Event('close'))
      await flushPromises()

      expect(mounted.emitted('closed')).toHaveLength(1)
      expect(mounted.findAll('button')).toHaveLength(0)
    })
  })

  describe('the calls chat apps made', () => {
    const GRANT: McpGrantResponse = {
      id: 5,
      kind: 'PERSONAL_TOKEN',
      name: 'Claude Code',
      scopes: ['read', 'fill', 'export'],
      tokenHint: 'ab12',
      createdAt: '2026-03-01T08:00:00Z',
      lastUsedAt: null,
      revokedAt: null,
    }

    function call(overrides: Partial<McpToolCallResponse>): McpToolCallResponse {
      return {
        id: 1,
        grantId: 5,
        tool: 'get_document',
        outcome: 'OK',
        code: null,
        at: '2026-03-02T12:00:00Z',
        ...overrides,
      }
    }

    function callRows(mounted: DialogWrapper) {
      return mounted.findAll('.revision-row--call')
    }

    it('asks for the calls where the deployment serves chat apps', async () => {
      vi.mocked(listMcpToolCalls).mockResolvedValue([call({ tool: 'get_document' })])
      vi.mocked(listMcpGrants).mockResolvedValue([GRANT])
      const mounted = await openDialog()

      expect(listMcpToolCalls).toHaveBeenCalledWith(7, 1)
      expect(listMcpGrants).toHaveBeenCalledWith(7)
      expect(callRows(mounted)).toHaveLength(1)
    })

    it('on a deployment without chat apps it asks for no calls, and the versions still load', async () => {
      vi.mocked(getCapabilities).mockResolvedValue({ ...CAPABILITIES, mcpEnabled: false })
      const mounted = await openDialog()

      expect(listMcpToolCalls).not.toHaveBeenCalled()
      expect(listMcpGrants).not.toHaveBeenCalled()
      expect(mounted.findAll('.revision-row').length).toBeGreaterThan(0)
      expect(callRows(mounted)).toHaveLength(0)
    })

    it('interleaves the calls with the versions by time, each naming its grant, and names a grant it cannot place plainly', async () => {
      vi.mocked(listMcpToolCalls).mockResolvedValue([
        call({ id: 1, tool: 'start_document', at: '2026-03-01T09:30:00Z' }),
        call({ id: 2, tool: 'get_document', at: '2026-03-02T12:00:00Z', grantId: 99 }),
        call({ id: 3, tool: 'export_document', at: '2026-03-03T12:00:00Z' }),
      ])
      vi.mocked(listMcpGrants).mockResolvedValue([GRANT])
      const mounted = await openDialog()

      expect(listMcpToolCalls).toHaveBeenCalledWith(7, 1)
      const sentences = callRows(mounted).map((rowElement) => rowElement.get('.revision-row__call-text').text())
      expect(sentences).toEqual([
        'Claude Code exported it',
        'A chat app read it',
        'Claude Code started this document',
      ])
      // The calls sit in time order with the versions: the newest row is a call made after version 3,
      // another sits between versions 2 and 1, and the call that started the document lands between
      // versions 1 and its own moment, so version 1 stays last.
      const allRows = mounted.findAll('.revision-row')
      const order = allRows.map((rowElement) => rowElement.find('.revision-row__name, .revision-row__call-text').text())
      expect(order).toEqual([
        'Claude Code exported it',
        'Version 3',
        'A chat app read it',
        'Version 2',
        'Claude Code started this document',
        'Version 1',
      ])
      expect(await axe(mounted.element)).toHaveNoViolations()
    })

    it.each([
      ['start_document', 'Claude Code started this document'],
      ['add_text_source', 'Claude Code added a text source'],
      ['propose_fill', 'Claude Code asked Brownie to propose values'],
      ['answer_question', 'Claude Code answered a question'],
      ['confirm_fill', 'Confirmed in Claude Code'],
      ['export_document', 'Claude Code exported it'],
      ['get_document', 'Claude Code read it'],
      ['get_export_link', 'Claude Code asked for a download link'],
      ['get_fill_status', 'Claude Code asked how a proposal was doing'],
      ['list_templates', 'Claude Code listed your templates'],
      ['a_tool_added_later', 'Claude Code used Brownie'],
    ])('says an answered call to %s in one sentence', async (tool, sentence) => {
      vi.mocked(listMcpToolCalls).mockResolvedValue([call({ tool })])
      vi.mocked(listMcpGrants).mockResolvedValue([GRANT])
      const mounted = await openDialog()

      expect(callRows(mounted)[0].get('.revision-row__call-text').text()).toBe(sentence)
    })

    it.each([
      ['PAYLOAD_CHANGED', 'the proposal changed after it was shown'],
      ['PROPOSAL_NOT_OPEN', 'the proposal was already settled'],
      ['PROPOSAL_PENDING', 'a proposal was still waiting'],
      ['UNREVIEWED_FIELDS', 'some proposed values were never confirmed'],
      ['VALIDATION_BLOCKING', 'a check blocks export'],
      ['NOT_FOUND', 'something it named was not there'],
      ['INVALID_ARGUMENT', 'it asked in a way Brownie could not use'],
      ['INSUFFICIENT_SCOPE', 'it was not allowed to do that'],
      ['CONFLICT', 'the document changed in the meantime'],
      ['A_CODE_ADDED_LATER', 'Brownie refused it'],
      [null, 'Brownie refused it'],
    ])("says a refusal with code %s in words", async (code, reason) => {
      vi.mocked(listMcpToolCalls).mockResolvedValue([call({ outcome: 'REFUSED', code })])
      vi.mocked(listMcpGrants).mockResolvedValue([GRANT])
      const mounted = await openDialog()

      expect(callRows(mounted)[0].get('.revision-row__call-text').text()).toBe(
        `Claude Code's request was refused: ${reason}`,
      )
    })

    it('says a failed call as a failure, not a refusal', async () => {
      vi.mocked(listMcpToolCalls).mockResolvedValue([call({ outcome: 'ERROR' })])
      vi.mocked(listMcpGrants).mockResolvedValue([GRANT])
      const mounted = await openDialog()

      expect(callRows(mounted)[0].get('.revision-row__call-text').text()).toBe("Claude Code's request failed")
    })

    it('still lists the versions when the calls cannot be loaded, as on a deployment without chat apps', async () => {
      vi.mocked(listMcpToolCalls).mockRejectedValue(
        new ApiRequestError(404, problem(404, 'NOT_FOUND', 'No static resource api/v1/workspaces/7/documents/1/mcp-calls.')),
      )
      const mounted = await openDialog()

      expect(mounted.findAll('.revision-row')).toHaveLength(3)
      expect(callRows(mounted)).toHaveLength(0)
      expect(mounted.findAll('.revision-row')[0].find('.revision-row__name').text()).toBe('Version 3')
    })
  })

  // axe asks the browser which element sits at a point to decide whether an open dialog is modal. jsdom has
  // no layout to answer with, so every rule would error and count as unchecked rather than as a violation;
  // answering "nothing there" makes axe treat the dialog as ordinary content and check all of it.
  async function scan(): Promise<void> {
    Object.defineProperty(document, 'elementFromPoint', { value: () => null, configurable: true })
    try {
      const results = await axe(document.body)
      expect(results.incomplete.filter((result) => result.error !== undefined).map((result) => result.id)).toEqual([])
      expect(results).toHaveNoViolations()
    } finally {
      Reflect.deleteProperty(document, 'elementFromPoint')
    }
  }

  it('has no automatically detectable accessibility problems just after opening', async () => {
    await openDialog()

    await scan()
  })

  it('has no automatically detectable accessibility problems with a full comparison, a locked note and a refused question open', async () => {
    const mounted = await openDialog({ currentRevision: LOCATION_LOCKED })
    await compareVersion(mounted, 1, REVISION_1)
    await buttonNamed(mounted, 'Show the same too').trigger('click')
    vi.mocked(restoreRevision).mockRejectedValue(new ApiRequestError(412, problem(412, 'STALE_REVISION', 'Revision 13 is not current.')))
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()
    expect(mounted.find('[role="alert"]').exists()).toBe(true)
    expect(mounted.find('.compare__note').exists()).toBe(true)

    await scan()
  })
})
