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
  }
})

import { ApiRequestError, getDocumentRevision, listDocumentRevisions, restoreRevision } from '@/api/client'

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function problem(status: number, code: string, detail: string): ApiError {
  return { status, title: code, detail, code, correlationId: 'c', fields: [], recoveryActions: [] }
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
  'action.item.task': 'Action item task',
  'action.item.owner': 'Action item owner',
}

function fieldLabel(fieldId: string): string {
  return LABELS[fieldId] ?? fieldId
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
  const found = mounted.findAll('.revision-row').find((candidate) => candidate.find('.revision-row__name').text().startsWith(`Version ${versionNumber}`))
  if (!found) throw new Error(`No row for version ${versionNumber}`)
  return found
}

/** The text a person reads, with the template's line breaks and indentation folded into single spaces. */
function words(element: { text(): string }): string {
  return element.text().replace(/\s+/g, ' ')
}

function buttonNamed(mounted: DialogWrapper, name: string) {
  const found = mounted.findAll('button').find((button) => (button.attributes('aria-label') ?? button.text()) === name)
  if (!found) throw new Error(`No button named "${name}"`)
  return found
}

async function askToRestoreVersion1(mounted: DialogWrapper): Promise<void> {
  await buttonNamed(mounted, 'Restore version 1').trigger('click')
  await flushPromises()
}

describe('VersionHistoryDialog', () => {
  beforeEach(() => {
    vi.mocked(listDocumentRevisions).mockReset().mockResolvedValue([REVISION_1, REVISION_2, REVISION_3])
    vi.mocked(getDocumentRevision).mockReset()
    vi.mocked(restoreRevision).mockReset()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    opener.remove()
  })

  it('renders nothing inside the dialog until it is opened', () => {
    const mounted = mountDialog()

    expect(mounted.find('dialog').attributes('open')).toBeUndefined()
    expect(mounted.findAll('button')).toHaveLength(0)
    expect(listDocumentRevisions).not.toHaveBeenCalled()
  })

  it('lists every version newest first, names each by its number, and offers no restore of the current one', async () => {
    const mounted = await openDialog()

    expect(mounted.find('dialog').attributes('open')).toBeDefined()
    expect(mounted.find('dialog').attributes('aria-labelledby')).toBe('version-history-heading')
    expect(mounted.find('#version-history-heading').text()).toBe('Version history')
    expect(listDocumentRevisions).toHaveBeenCalledWith(7, 1)

    const rows = mounted.findAll('.revision-row')
    expect(rows.map((candidate) => candidate.find('.revision-row__name').text())).toEqual([
      'Version 3 Current version',
      'Version 2',
      'Version 1',
    ])
    expect(rows[1].text()).toContain('Edited the location.')
    expect(rows[1].find('time').attributes('datetime')).toBe('2026-03-02T09:00:00Z')
    expect(rows[1].find('time').text()).toBe(new Date('2026-03-02T09:00:00Z').toLocaleString())

    // Every row's buttons read the same out of context, so each accessible name says which version it acts on,
    // starting with the words the button shows.
    expect(rows[2].findAll('button').map((button) => [button.text(), button.attributes('aria-label')])).toEqual([
      ['Compare', 'Compare version 1 with the current version'],
      ['Restore', 'Restore version 1'],
    ])
    expect(rows[0].find('button[aria-label="Restore version 3"]').exists()).toBe(false)
    expect(rows[0].find('button[aria-label="Compare version 3 with the current version"]').exists()).toBe(true)
    // The close button is the first thing the keyboard reaches, and it is where focus lands on opening.
    expect(document.activeElement?.textContent?.trim()).toBe('Close')
  })

  it('compares a version with now field by field, marking what changed in words as well as colour', async () => {
    const mounted = await openDialog()
    vi.mocked(getDocumentRevision).mockResolvedValue(REVISION_1)

    await buttonNamed(mounted, 'Compare version 1 with the current version').trigger('click')
    await flushPromises()

    expect(getDocumentRevision).toHaveBeenCalledWith(7, 1, 11)
    const panel = row(mounted, 1).find('.compare-panel')
    expect(panel.find('h3').text()).toBe('Version 1 compared with now')

    const compareRows = panel.findAll('.compare-row:not(.compare-row--head)')
    expect(compareRows.map((candidate) => candidate.find('.compare-row__name').text())).toEqual([
      'Meeting location',
      'Meeting notes',
      'Meeting title',
    ])
    const [location, notes, title] = compareRows
    expect(location.classes()).toContain('compare-row--changed')
    expect(location.text()).toContain('Changed')
    expect(words(location)).toContain('Version 1: Room A')
    expect(words(location)).toContain('Now: Room C')
    // A field the older version never held reads as empty, not as a dash a screen reader would spell out.
    expect(notes.classes()).toContain('compare-row--changed')
    expect(notes.find('.compare-row__empty').text()).toBe('Empty')
    expect(title.classes()).not.toContain('compare-row--changed')
    expect(title.text()).not.toContain('Changed')
    expect(mounted.find('[aria-live="polite"]').text()).toBe('Showing version 1 compared with now.')
  })

  it('says which changed fields a restore would leave alone because they are locked', async () => {
    const locked: DocumentRevisionResponse = {
      ...REVISION_3,
      fields: {
        ...REVISION_3.fields,
        'meeting.location': {
          ...REVISION_3.fields['meeting.location'],
          fieldState: { authorship: 'USER_AUTHORED', evidenceSupport: 'MISSING', validation: 'NOT_RUN', review: 'ACCEPTED', lock: 'EXPLICITLY_LOCKED' },
        },
        // One locked row keeps every repeated field as it is, because the rows only make sense together.
        'action.item.task': {
          type: 'TEXT',
          cardinality: 'REPEATED',
          values: ['Book room'],
          itemFieldStates: [{ authorship: 'USER_AUTHORED', evidenceSupport: 'MISSING', validation: 'NOT_RUN', review: 'ACCEPTED', lock: 'EXPLICITLY_LOCKED' }],
        },
        'action.item.owner': { type: 'TEXT', cardinality: 'REPEATED', values: ['Ana'], itemFieldStates: [null] },
      },
    }
    const mounted = await openDialog({ currentRevision: locked })
    vi.mocked(getDocumentRevision).mockResolvedValue(REVISION_1)

    await buttonNamed(mounted, 'Compare version 1 with the current version').trigger('click')
    await flushPromises()

    const note = 'Locked, so a restore keeps its current value'
    const byName = (name: string) =>
      mounted.findAll('.compare-row:not(.compare-row--head)').find((candidate) => candidate.find('.compare-row__name').text() === name)
    expect(byName('Meeting location')?.text()).toContain(note)
    expect(byName('Action item task')?.text()).toContain(note)
    expect(byName('Action item owner')?.text()).toContain(note)
    expect(byName('Meeting notes')?.text()).not.toContain(note)
    expect(byName('Meeting title')?.text()).not.toContain(note)
  })

  it('ignores a slower compare response once a newer one has already been shown', async () => {
    const mounted = await openDialog()
    let resolveFirst!: (revision: DocumentRevisionResponse) => void
    vi.mocked(getDocumentRevision)
      .mockReturnValueOnce(new Promise<DocumentRevisionResponse>((resolve) => (resolveFirst = resolve)))
      .mockResolvedValueOnce(REVISION_2)

    await buttonNamed(mounted, 'Compare version 1 with the current version').trigger('click')
    await buttonNamed(mounted, 'Compare version 2 with the current version').trigger('click')
    await flushPromises()
    expect(mounted.text()).toContain('Version 2 compared with now')

    resolveFirst(REVISION_1)
    await flushPromises()

    expect(mounted.text()).toContain('Version 2 compared with now')
    expect(mounted.text()).not.toContain('Version 1 compared with now')
    expect(row(mounted, 1).find('.compare-panel').exists()).toBe(false)
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
    await buttonNamed(mounted, 'Compare version 1 with the current version').trigger('click')
    await flushPromises()
    expect(mounted.find('.compare-panel [role="alert"]').text()).toContain('Brownie could not be reached. It may not be running')
    expect(mounted.text()).not.toContain('502')
  })

  it('confirms in the row, restores against the current version, tells the page, and closes', async () => {
    const mounted = await openDialog()
    vi.mocked(restoreRevision).mockResolvedValue({ revision: RESTORED, keptLockedFieldIds: ['meeting.title'] } satisfies RestoreRevisionResponse)

    await askToRestoreVersion1(mounted)

    const question = row(mounted, 1).find('.revision-row__question')
    expect(question.text()).toBe('Restore version 1? Your current version stays in the history.')
    expect(document.activeElement).toBe(question.element)
    expect(row(mounted, 1).find('[role="group"]').attributes('aria-labelledby')).toBe(question.attributes('id'))
    expect(restoreRevision).not.toHaveBeenCalled()

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

    expect(mounted.find('.revision-row__question').exists()).toBe(false)
    expect(document.activeElement).toBe(buttonNamed(mounted, 'Restore version 1').element)
    expect(restoreRevision).not.toHaveBeenCalled()
    expect(mounted.emitted('closed')).toBeUndefined()
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
    // The confirmation stays where it was, so the focus the person had is not lost.
    expect(buttonNamed(mounted, 'Restore version 1').text()).toBe('Restore version 1')
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

  it('reloads the list when the page saves a newer version while the dialog is open', async () => {
    const mounted = await openDialog()
    expect(listDocumentRevisions).toHaveBeenCalledTimes(1)

    await mounted.setProps({ currentRevision: { ...REVISION_3, id: 15, revisionNumber: 5 } })
    await flushPromises()

    expect(listDocumentRevisions).toHaveBeenCalledTimes(2)
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

  it('has no automatically detectable accessibility problems with a comparison and a confirmation open', async () => {
    const mounted = await openDialog()
    vi.mocked(getDocumentRevision).mockResolvedValue(REVISION_2)
    await buttonNamed(mounted, 'Compare version 2 with the current version').trigger('click')
    await flushPromises()
    vi.mocked(restoreRevision).mockRejectedValue(new ApiRequestError(412, problem(412, 'STALE_REVISION', 'Revision 13 is not current.')))
    await askToRestoreVersion1(mounted)
    await buttonNamed(mounted, 'Restore version 1').trigger('click')
    await flushPromises()
    expect(mounted.find('[role="alert"]').exists()).toBe(true)

    // axe asks the browser which element sits at a point to decide whether an open dialog is modal. jsdom has
    // no layout to answer with, so every rule would error and count as unchecked rather than as a violation;
    // answering "nothing there" makes axe treat the dialog as ordinary content and check all of it.
    Object.defineProperty(document, 'elementFromPoint', { value: () => null, configurable: true })
    try {
      const results = await axe(document.body)
      expect(results.incomplete.filter((result) => result.error !== undefined).map((result) => result.id)).toEqual([])
      expect(results).toHaveNoViolations()
    } finally {
      Reflect.deleteProperty(document, 'elementFromPoint')
    }
  })
})
