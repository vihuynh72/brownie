import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import ExportDialog from '@/components/workspace/ExportDialog.vue'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    validateDocument: vi.fn(),
    getLatestValidation: vi.fn(),
    approveExport: vi.fn(),
    getLatestExportApproval: vi.fn(),
    exportDocument: vi.fn(),
    getLatestExportReceipt: vi.fn(),
    getCapabilities: vi.fn(),
    listConnections: vi.fn(),
    listActions: vi.fn(),
  }
})
vi.mock('@/navigation', () => ({ navigateTo: vi.fn(), releaseIfStillHere: vi.fn() }))

import {
  ApiRequestError,
  approveExport,
  artifactDownloadUrl,
  exportDocument,
  getCapabilities,
  getLatestExportApproval,
  getLatestExportReceipt,
  getLatestValidation,
  listActions,
  listConnections,
  validateDocument,
  type ExportApprovalResponse,
  type ExportReceiptResponse,
  type ValidationManifestResponse,
} from '@/api/client'
import { resetCapabilitiesCache } from '@/capabilities'

/** Stand-ins for the Google panels, which have tests of their own: these only record what the dialog hands them. */
const DriveStub = defineComponent({
  name: 'DriveSavePanel',
  props: ['workspaceId', 'documentId', 'receipt', 'unsavedWork'],
  template: '<div class="drive-stub" />',
})
const CalendarStub = defineComponent({
  name: 'CalendarEventPanel',
  props: ['workspaceId', 'documentId', 'documentTitle', 'suggestedDate', 'unsavedWork'],
  template: '<div class="calendar-stub" />',
})

const LABELS: Record<string, string> = { 'meeting.title': 'Meeting title', 'meeting.date': 'Meeting date', 'action.item.due': 'Action item due' }

function manifest(overrides: Partial<ValidationManifestResponse> = {}): ValidationManifestResponse {
  return {
    id: 3,
    documentId: 42,
    revisionId: 10,
    templateId: 1,
    templateVersionId: 2,
    docxArtifactId: 11,
    docxSha256: 'a'.repeat(64),
    pdfArtifactId: 12,
    pdfSha256: 'b'.repeat(64),
    findings: [],
    hasUnresolvedBlocking: false,
    createdAt: '2026-09-29T09:00:00Z',
    ...overrides,
  }
}

function approval(overrides: Partial<ExportApprovalResponse> = {}): ExportApprovalResponse {
  return {
    id: 4,
    documentId: 42,
    revisionId: 10,
    templateVersionId: 2,
    validationManifestId: 3,
    format: 'BOTH',
    approvedAt: '2026-09-29T09:01:00Z',
    ...overrides,
  }
}

function receipt(overrides: Partial<ExportReceiptResponse> = {}): ExportReceiptResponse {
  return {
    id: 5,
    documentId: 42,
    revisionId: 10,
    templateVersionId: 2,
    exportApprovalId: 4,
    validationManifestId: 3,
    docxArtifactId: 13,
    docxSha256: 'c'.repeat(64),
    pdfArtifactId: 14,
    pdfSha256: 'd'.repeat(64),
    format: 'BOTH',
    isCompletePair: true,
    exportedAt: '2026-09-29T09:02:00Z',
    ...overrides,
  }
}

function problem(status: number, code: string, detail: string) {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [] }
}

function notFound(): ApiRequestError {
  return new ApiRequestError(404, problem(404, 'NOT_FOUND', 'Nothing on record.'))
}

const BLOCKING_FINDINGS: ValidationManifestResponse['findings'] = [
  { code: 'REQUIRED_FIELD_EMPTY', severity: 'BLOCKING', fieldId: 'meeting.title', message: 'This field is required.' },
  { code: 'WEAK_EVIDENCE', severity: 'WARNING', fieldId: 'meeting.date', message: 'The date has no cited source.' },
  { code: 'LAYOUT_COMPARISON_UNAVAILABLE', severity: 'INFORMATIONAL', fieldId: null, message: 'Layout comparison was not run.' },
]

type Dialog = VueWrapper<InstanceType<typeof ExportDialog>>

const mounted: Dialog[] = []
/** The revision the server holds now; the page's reload hands it to the dialog, as the workspace does. */
let serverRevision = 9

interface MountOptions {
  currentRevisionId?: number
  /** Has the page show a number for its revision, which in these tests is the revision's id. */
  numbered?: boolean
  fillCounts?: { total: number; filled: number; accepted: number; locked: number }
  unsavedWork?: boolean
  saveBeforeExport?: () => Promise<boolean>
  reloadDocument?: () => Promise<boolean>
  editable?: string[]
  realPanels?: boolean
  pdfOnly?: boolean
}

async function mountDialog(options: MountOptions = {}) {
  const opener = document.createElement('button')
  opener.type = 'button'
  opener.textContent = 'Export'
  document.body.appendChild(opener)
  opener.focus()

  const router = createRouter({ history: createWebHistory(), routes: [{ path: '/:any(.*)*', component: { template: '<div />' } }] })
  router.push('/')
  await router.isReady()

  let self: Dialog | null = null
  const reloadDocument =
    options.reloadDocument ??
    vi.fn(async () => {
      await self?.setProps({ currentRevisionId: serverRevision, ...(options.numbered ? { currentRevisionNumber: serverRevision } : {}) })
      return true
    })
  const wrapper: Dialog = mount(ExportDialog, {
    props: {
      workspaceId: 7,
      documentId: 42,
      documentTitle: 'Spring budget: minutes',
      currentRevisionId: options.currentRevisionId ?? 9,
      currentRevisionNumber: options.numbered ? (options.currentRevisionId ?? 9) : undefined,
      fillCounts: options.fillCounts,
      unsavedWork: options.unsavedWork ?? false,
      saveBeforeExport: options.saveBeforeExport ?? vi.fn(async () => true),
      reloadDocument,
      fieldLabel: (fieldId: string) => LABELS[fieldId] ?? fieldId,
      editableFieldIds: new Set(options.editable ?? ['meeting.title']),
      suggestedEventDate: '2026-10-05',
      pdfOnly: options.pdfOnly ?? false,
    },
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: options.realPanels ? {} : { DriveSavePanel: DriveStub, CalendarEventPanel: CalendarStub },
    },
  })
  self = wrapper
  mounted.push(wrapper)
  return { wrapper, opener, reloadDocument }
}

async function openDialog(wrapper: Dialog): Promise<void> {
  wrapper.vm.open()
  await flushPromises()
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function text(wrapper: Dialog): string {
  return wrapper.text().replace(/\s+/g, ' ')
}

function buttonNamed(wrapper: Dialog, name: string) {
  return wrapper.findAll('button').find((button) => button.text().replace(/\s+/g, ' ').trim() === name)
}

function linkNamed(wrapper: Dialog, name: string) {
  return wrapper.findAll('a').find((link) => link.text().replace(/\s+/g, ' ').trim() === name)
}

/** The progress rows at the top: where each stands and what it says. */
function steps(wrapper: Dialog): { state: string; label: string }[] {
  return wrapper.findAll('.export-dialog__steps .step').map((row) => ({
    state: /step--(\w+)/.exec(row.classes().join(' '))?.[1] ?? '',
    label: row.get('.step__label').text(),
  }))
}

function subtitle(wrapper: Dialog): string {
  return wrapper.get('.dialog__subtitle').text().replace(/\s+/g, ' ')
}

const clockFormat = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' })

function checkedFormat(wrapper: Dialog): string | undefined {
  return wrapper.findAll<HTMLInputElement>('input[name="export-format"]').find((input) => input.element.checked)?.element.value
}

/** A version with a check, approval and export already on record, so a test can start from the files. */
function onRecord(made: ExportReceiptResponse = receipt(), approved: ExportApprovalResponse = approval({ format: made.format })) {
  vi.mocked(getLatestValidation).mockResolvedValue(manifest())
  vi.mocked(getLatestExportApproval).mockResolvedValue(approved)
  vi.mocked(getLatestExportReceipt).mockResolvedValue(made)
}

beforeEach(() => {
  serverRevision = 9
  resetCapabilitiesCache()
  vi.mocked(getLatestValidation).mockRejectedValue(notFound())
  vi.mocked(getLatestExportApproval).mockRejectedValue(notFound())
  vi.mocked(getLatestExportReceipt).mockRejectedValue(notFound())
  vi.mocked(validateDocument).mockImplementation(async () => {
    serverRevision = 10
    return manifest()
  })
})

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  Reflect.deleteProperty(window.navigator, 'canShare')
  Reflect.deleteProperty(window.navigator, 'share')
})

describe('opening', () => {
  it('shows nothing while closed, so no control inside it can be found on the page', async () => {
    const { wrapper } = await mountDialog()
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.find('dialog').attributes('open')).toBeUndefined()
    expect(getLatestValidation).not.toHaveBeenCalled()
  })

  it('keeps its foot in view under a body that scrolls, and says there is more below while there is', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    const body = wrapper.get('.export-dialog__body')
    const more = wrapper.get('.export-dialog__more')
    // The foot is outside the part that scrolls, after it.
    expect(wrapper.get('.export-dialog__footer').element.previousElementSibling).toBe(body.element)
    expect(more.attributes('aria-hidden')).toBe('true')

    const sizes = { scrollHeight: 900, clientHeight: 500, scrollTop: 0 }
    for (const [key, value] of Object.entries(sizes)) Object.defineProperty(body.element, key, { value, configurable: true, writable: true })
    await body.trigger('scroll')
    expect(body.classes()).toContain('export-dialog__body--more-below')
    expect(more.classes()).toContain('export-dialog__more--shown')

    // "More below" scrolls the body on.
    const scrollBy = vi.fn()
    ;(body.element as HTMLElement).scrollBy = scrollBy
    await more.trigger('click')
    expect(scrollBy).toHaveBeenCalledWith(expect.objectContaining({ top: 400 }))

    Object.defineProperty(body.element, 'scrollTop', { value: 400, configurable: true })
    await body.trigger('scroll')
    expect(body.classes()).not.toContain('export-dialog__body--more-below')
    expect(more.classes()).not.toContain('export-dialog__more--shown')

    // "Cancel", so only the button at the top is named Close.
    expect(wrapper.findAll('button').filter((button) => button.text() === 'Close')).toHaveLength(1)
    await buttonNamed(wrapper, 'Cancel')!.trigger('click')
    expect(wrapper.emitted('closed')).toHaveLength(1)
  })

  it('saves unsaved edits first, then checks the revision that save made', async () => {
    const saving = deferred<boolean>()
    const saveBeforeExport = vi.fn(() => saving.promise)
    const { wrapper } = await mountDialog({ unsavedWork: true, saveBeforeExport })
    await openDialog(wrapper)

    expect(saveBeforeExport).toHaveBeenCalledTimes(1)
    expect(text(wrapper)).toContain('Saving your changes first…')
    expect(getLatestValidation).not.toHaveBeenCalled()
    expect(validateDocument).not.toHaveBeenCalled()

    // The page's save appended revision 12, and that is the revision checked; checking appends 13.
    vi.mocked(validateDocument).mockImplementation(async () => {
      serverRevision = 13
      return manifest({ revisionId: 13 })
    })
    await wrapper.setProps({ currentRevisionId: 12, unsavedWork: false })
    saving.resolve(true)
    await flushPromises()

    expect(getLatestValidation).toHaveBeenCalledWith(7, 42, 12)
    expect(validateDocument).toHaveBeenCalledWith(7, 42, 12, expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(saveBeforeExport.mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(getLatestValidation).mock.invocationCallOrder[0]!)
    expect(text(wrapper)).not.toContain('Saving your changes first…')
    expect(text(wrapper)).toContain('Ready to export.')
  })

  it('stops and says why when the page could not save', async () => {
    const { wrapper } = await mountDialog({ unsavedWork: true, saveBeforeExport: vi.fn(async () => false) })
    await openDialog(wrapper)

    const alert = wrapper.find('[role="alert"]')
    expect(alert.text().replace(/\s+/g, ' ')).toBe(
      'Your latest changes are not saved, so this version cannot be exported yet. Close this and check the message on the page.',
    )
    expect(getLatestValidation).not.toHaveBeenCalled()
    expect(validateDocument).not.toHaveBeenCalled()
    expect(buttonNamed(wrapper, 'Approve and export')).toBeUndefined()
  })

  it('picks up the check, approval and export on record for this version instead of checking again', async () => {
    onRecord(receipt({ format: 'DOCX', pdfArtifactId: null, pdfSha256: null, isCompletePair: false }))
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    expect(getLatestValidation).toHaveBeenCalledWith(7, 42, 10)
    expect(validateDocument).not.toHaveBeenCalled()
    expect(approveExport).not.toHaveBeenCalled()
    expect(exportDocument).not.toHaveBeenCalled()
    expect(wrapper.find('[aria-live="polite"]').text()).toBe('DOCX exported.')
    expect(wrapper.find('input[name="export-format"]').exists()).toBe(false)
    expect(linkNamed(wrapper, 'Download Word file (.docx)')?.attributes('href')).toBe(artifactDownloadUrl(7, 13))
    expect(wrapper.findComponent(DriveStub).props('receipt')).toMatchObject({ id: 5 })

    // Going back to the choice shows the format that was approved, so it matches the file that was offered.
    await buttonNamed(wrapper, 'Export again')!.trigger('click')
    expect(checkedFormat(wrapper)).toBe('DOCX')
  })

  it('offers only the PDF for a PDF form, whose export has no Word file', async () => {
    onRecord(receipt({ format: 'PDF', docxArtifactId: null, docxSha256: null, isCompletePair: false }))
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    expect(linkNamed(wrapper, 'Download PDF')?.attributes('href')).toBe(artifactDownloadUrl(7, 14))
    expect(linkNamed(wrapper, 'Download Word file (.docx)')).toBeUndefined()
  })

  it('ignores an approval of another check and an export of another approval', async () => {
    vi.mocked(getLatestValidation).mockResolvedValue(manifest())
    vi.mocked(getLatestExportApproval).mockResolvedValue(approval({ validationManifestId: 99 }))
    vi.mocked(getLatestExportReceipt).mockResolvedValue(receipt())
    const first = await mountDialog({ currentRevisionId: 10 })
    await openDialog(first.wrapper)
    expect(text(first.wrapper)).toContain('Ready to export.')
    expect(getLatestExportReceipt).not.toHaveBeenCalled()
    expect(first.wrapper.findAll('a')).toHaveLength(0)

    vi.mocked(getLatestExportApproval).mockResolvedValue(approval())
    vi.mocked(getLatestExportReceipt).mockResolvedValue(receipt({ exportApprovalId: 98 }))
    const second = await mountDialog({ currentRevisionId: 10 })
    await openDialog(second.wrapper)
    expect(second.wrapper.findAll('a')).toHaveLength(0)
    expect(text(second.wrapper)).not.toContain('exported')
  })

  it('checks the version when none is on record, and has the page load the revision checking made', async () => {
    const checking = deferred<ValidationManifestResponse>()
    vi.mocked(validateDocument).mockImplementation(() => {
      serverRevision = 10
      return checking.promise
    })
    const { wrapper, reloadDocument } = await mountDialog()
    await openDialog(wrapper)

    expect(validateDocument).toHaveBeenCalledWith(7, 42, 9, expect.any(String))
    expect(text(wrapper)).toContain('Checking this version…')

    checking.resolve(manifest())
    await flushPromises()

    expect(reloadDocument).toHaveBeenCalledTimes(1)
    expect(wrapper.props('currentRevisionId')).toBe(10)
    // The revision checking appended is the one the result names, so the result stays.
    expect(text(wrapper)).toContain('Ready to export.')
    expect(text(wrapper)).toContain('Brownie checked this version. It found nothing to fix.')
    expect(text(wrapper)).not.toContain('This document changed')
  })

  it('keeps what it found for the next opening while the document stays the same', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    await buttonNamed(wrapper, 'Close')!.trigger('click')
    await flushPromises()
    await openDialog(wrapper)

    expect(validateDocument).toHaveBeenCalledTimes(1)
    expect(getLatestValidation).toHaveBeenCalledTimes(1)
    expect(text(wrapper)).toContain('Ready to export.')
  })
})

describe('what the check found', () => {
  it('lists every problem in words, with a way to each field the page can show, and offers no export', async () => {
    vi.mocked(validateDocument).mockResolvedValue(manifest({ hasUnresolvedBlocking: true, findings: BLOCKING_FINDINGS }))
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    expect(wrapper.find('[role="alert"]').text().replace(/\s+/g, ' ')).toBe(
      'This version cannot be exported yet. Fix what is listed below, then check again.',
    )
    const rows = wrapper.findAll('.export-dialog__finding').map((row) => row.text().replace(/\s+/g, ' '))
    expect(rows).toEqual([
      'Blocks export Meeting title This field is required. Go to Meeting title',
      'Warning Meeting date The date has no cited source.',
      'Note Layout comparison was not run.',
    ])
    expect(text(wrapper)).toContain('It found 1 thing that blocks export, 1 warning and 1 note.')
    expect(text(wrapper)).not.toMatch(/BLOCKING|WARNING|INFORMATIONAL/)
    expect(wrapper.find('input[name="export-format"]').exists()).toBe(false)

    await buttonNamed(wrapper, 'Go to Meeting title')!.trigger('click')
    expect(wrapper.emitted('go-to-field')).toEqual([['meeting.title']])
  })

  it('keeps the approval in sight but unusable while a problem blocks export, and says why', async () => {
    vi.mocked(validateDocument).mockResolvedValue(manifest({ hasUnresolvedBlocking: true, findings: BLOCKING_FINDINGS }))
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    const approve = buttonNamed(wrapper, 'Approve and export')!
    expect(approve.attributes('aria-disabled')).toBe('true')
    expect(document.getElementById(approve.attributes('aria-describedby')!)?.textContent?.trim()).toBe('A check that blocks export cannot be skipped.')
    await approve.trigger('click')
    await flushPromises()
    expect(approveExport).not.toHaveBeenCalled()

    // The way out is the Close at the top; a second button for it would crowd the foot.
    expect(buttonNamed(wrapper, 'Cancel')).toBeUndefined()
    expect(buttonNamed(wrapper, 'Done')).toBeUndefined()
    expect(buttonNamed(wrapper, 'Check again')?.classes()).toContain('button--secondary')
  })

  it('tags each finding by kind with a word and an icon, and offers a way to the fill spot only for what asks something', async () => {
    vi.mocked(validateDocument).mockResolvedValue(
      manifest({
        hasUnresolvedBlocking: true,
        findings: [
          { code: 'A', severity: 'BLOCKING', fieldId: 'meeting.title', message: 'is empty.' },
          { code: 'B', severity: 'WARNING', fieldId: 'meeting.title', message: 'has a weak source.' },
          { code: 'C', severity: 'INFORMATIONAL', fieldId: 'meeting.title', message: 'is locked, so Brownie leaves it.' },
        ],
      }),
    )
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    const tags = wrapper.findAll('.export-dialog__finding .tag')
    expect(tags.map((tag) => tag.text())).toEqual(['Blocks export', 'Warning', 'Note'])
    expect(tags.map((tag) => tag.classes().filter((name) => name.startsWith('tag--')))).toEqual([['tag--error'], ['tag--honey'], []])
    expect(tags.every((tag) => tag.find('svg').exists())).toBe(true)
    // A note only says what Brownie did, so there is nowhere to go for it.
    const rows = wrapper.findAll('.export-dialog__finding')
    expect(rows.map((row) => row.findAll('button').length)).toEqual([1, 1, 0])
    expect(rows[0]!.text().replace(/\s+/g, ' ')).toBe('Blocks export Meeting title is empty. Go to Meeting title')
  })

  it('lists warnings and notes without stopping the export, and without calling the version clear', async () => {
    vi.mocked(validateDocument).mockResolvedValue(manifest({ findings: [BLOCKING_FINDINGS[1]!, BLOCKING_FINDINGS[2]!] }))
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    expect(text(wrapper)).toContain('Brownie checked this version. It found 1 warning and 1 note.')
    expect(wrapper.findAll('.export-dialog__finding')).toHaveLength(2)
    expect(wrapper.find('.export-dialog__clear').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(buttonNamed(wrapper, 'Approve and export')?.attributes('aria-disabled')).toBe('false')
    expect(wrapper.findAll('input[name="export-format"]')).toHaveLength(3)
  })

  it('says a kind of finding it does not know as a note', async () => {
    vi.mocked(validateDocument).mockResolvedValue(
      manifest({ findings: [{ code: 'X', severity: 'SOMETHING_NEW' as never, fieldId: null, message: 'Something new was seen.' }] }),
    )
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    expect(wrapper.get('.export-dialog__finding .tag').text()).toBe('Note')
    expect(buttonNamed(wrapper, 'Approve and export')?.attributes('aria-disabled')).toBe('false')
  })

  it('checks again when asked, keeping the button where the focus is', async () => {
    serverRevision = 10
    vi.mocked(validateDocument).mockResolvedValueOnce(manifest({ hasUnresolvedBlocking: true, findings: BLOCKING_FINDINGS }))
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    const again = deferred<ValidationManifestResponse>()
    vi.mocked(validateDocument).mockReturnValueOnce(again.promise)
    const button = buttonNamed(wrapper, 'Check again')!
    ;(button.element as HTMLButtonElement).focus()
    await button.trigger('click')
    await flushPromises()

    expect(validateDocument).toHaveBeenLastCalledWith(7, 42, 10, expect.any(String))
    expect(button.attributes('aria-disabled')).toBe('true')
    expect(document.activeElement).toBe(button.element)
    // A second press while checking is ignored rather than sent.
    await button.trigger('click')
    expect(validateDocument).toHaveBeenCalledTimes(2)

    serverRevision = 11
    again.resolve(manifest({ id: 6, revisionId: 11, findings: [BLOCKING_FINDINGS[1]!] }))
    await flushPromises()
    expect(text(wrapper)).toContain('Ready to export.')
    expect(text(wrapper)).toContain('It found 1 warning.')
    expect(buttonNamed(wrapper, 'Approve and export')).toBeTruthy()
  })

  it('offers export from a check an older server answered without findings or a blocking flag', async () => {
    const bare = manifest() as Partial<ValidationManifestResponse>
    delete bare.findings
    delete bare.hasUnresolvedBlocking
    vi.mocked(validateDocument).mockResolvedValue(bare as ValidationManifestResponse)
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    expect(text(wrapper)).toContain('Ready to export.')
    expect(text(wrapper)).toContain('It found nothing to fix.')
    expect(buttonNamed(wrapper, 'Approve and export')).toBeTruthy()
  })
})

describe('approving and exporting', () => {
  it('approves the chosen format, then exports straight away', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    expect(checkedFormat(wrapper)).toBe('BOTH')
    await wrapper.find('input[value="PDF"]').setValue(true)

    vi.mocked(approveExport).mockResolvedValue(approval({ format: 'PDF' }))
    const exporting = deferred<ExportReceiptResponse>()
    vi.mocked(exportDocument).mockReturnValue(exporting.promise)
    const button = buttonNamed(wrapper, 'Approve and export')!
    // Before the export it is the main button.
    expect(button.classes()).toContain('button--primary')
    ;(button.element as HTMLButtonElement).focus()
    await button.trigger('click')
    await flushPromises()

    expect(approveExport).toHaveBeenCalledWith(7, 42, 3, 'PDF')
    expect(exportDocument).toHaveBeenCalledWith(7, 42)
    expect(vi.mocked(approveExport).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(exportDocument).mock.invocationCallOrder[0]!)
    expect(button.text()).toBe('Exporting…')
    expect(button.attributes('aria-disabled')).toBe('true')
    expect(wrapper.find('[aria-live="polite"]').text()).toBe('Approving and exporting this version…')
    await button.trigger('click')
    expect(approveExport).toHaveBeenCalledTimes(1)

    exporting.resolve(receipt({ format: 'PDF' }))
    await flushPromises()
    // The choices and the button that made the files give way to the files.
    expect(buttonNamed(wrapper, 'Approve and export')).toBeUndefined()
    expect(wrapper.find('input[name="export-format"]').exists()).toBe(false)
    expect(text(wrapper)).toContain('PDF exported.')
    expect(linkNamed(wrapper, 'Download PDF')?.attributes('href')).toBe(artifactDownloadUrl(7, 14))
    expect(linkNamed(wrapper, 'Download PDF')?.attributes('download')).toBe('')
    expect(linkNamed(wrapper, 'Download Word file (.docx)')).toBeUndefined()
    // The download is what comes next now, so it is the main button and takes the focus the approval held.
    expect(linkNamed(wrapper, 'Download PDF')?.classes()).toContain('button--primary')
    expect(document.activeElement).toBe(linkNamed(wrapper, 'Download PDF')!.element)
    // Said once, in the live region, so it is read once and a search for it finds one line.
    expect(wrapper.find('[aria-live="polite"]').text()).toBe('PDF exported.')
    expect(wrapper.findAll('p').filter((line) => line.text() === 'PDF exported.')).toHaveLength(1)
  })

  it.each([
    ['DOCX', receipt({ format: 'DOCX', pdfArtifactId: null, pdfSha256: null }), 'DOCX exported.', ['Download Word file (.docx)']],
    ['PDF', receipt({ format: 'PDF' }), 'PDF exported.', ['Download PDF']],
    [
      'PDF that could not be made',
      receipt({ format: 'PDF', pdfArtifactId: null, pdfSha256: null, isCompletePair: false }),
      'The PDF could not be made for this version, so the DOCX is offered instead.',
      ['Download Word file (.docx)'],
    ],
    ['both', receipt(), 'Both files exported.', ['Download Word file (.docx)', 'Download PDF']],
    [
      'both without the PDF',
      receipt({ pdfArtifactId: null, pdfSha256: null, isCompletePair: false }),
      'The DOCX was exported; the PDF could not be made for this version.',
      ['Download Word file (.docx)'],
    ],
  ])('offers the files an export of %s made', async (_name, made, sentence, links) => {
    onRecord(made)
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    expect(text(wrapper)).toContain(sentence)
    expect(wrapper.findAll('.export-file a').map((link) => link.text().trim())).toEqual(links)
  })

  it('drops an export that was under way when another format is chosen', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    vi.mocked(approveExport).mockResolvedValue(approval())
    const exporting = deferred<ExportReceiptResponse>()
    vi.mocked(exportDocument).mockReturnValue(exporting.promise)
    await buttonNamed(wrapper, 'Approve and export')!.trigger('click')
    await flushPromises()
    expect(buttonNamed(wrapper, 'Exporting…')).toBeTruthy()

    await wrapper.find('input[value="DOCX"]').setValue(true)
    exporting.resolve(receipt())
    await flushPromises()

    // What was made was for the choice before this one, so it is not shown as if it were this one's.
    expect(wrapper.findAll('a')).toHaveLength(0)
    expect(text(wrapper)).not.toContain('Both files exported.')
    expect(buttonNamed(wrapper, 'Approve and export')).toBeTruthy()
    expect(checkedFormat(wrapper)).toBe('DOCX')
  })

  it('goes back from the files to the choice of format, with the focus on it', async () => {
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)
    const again = buttonNamed(wrapper, 'Export again')!
    ;(again.element as HTMLButtonElement).focus()

    await again.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('a')).toHaveLength(0)
    expect(wrapper.find('.export-dialog__files').exists()).toBe(false)
    expect(buttonNamed(wrapper, 'Export again')).toBeUndefined()
    expect(checkedFormat(wrapper)).toBe('BOTH')
    expect(document.activeElement).toBe(wrapper.get<HTMLInputElement>('input[name="export-format"]:checked').element)
    expect(approveExport).not.toHaveBeenCalled()

    // Another format is a new approval and a new export.
    await wrapper.find('input[value="PDF"]').setValue(true)
    vi.mocked(approveExport).mockResolvedValue(approval({ format: 'PDF' }))
    vi.mocked(exportDocument).mockResolvedValue(receipt({ format: 'PDF' }))
    await buttonNamed(wrapper, 'Approve and export')!.trigger('click')
    await flushPromises()
    expect(approveExport).toHaveBeenCalledWith(7, 42, 3, 'PDF')
    expect(text(wrapper)).toContain('PDF exported.')
  })

  it('clears everything and asks for a new check when the document changed since it was checked', async () => {
    const { wrapper, reloadDocument } = await mountDialog()
    await openDialog(wrapper)
    vi.mocked(approveExport).mockResolvedValue(approval())
    vi.mocked(exportDocument).mockRejectedValue(new ApiRequestError(412, problem(412, 'STALE_EXPORT_APPROVAL', 'Stale.')))
    serverRevision = 13

    const button = buttonNamed(wrapper, 'Approve and export')!
    ;(button.element as HTMLButtonElement).focus()
    await button.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('This document changed since it was checked. Check it again.')
    expect(text(wrapper)).not.toContain('Ready to export.')
    expect(buttonNamed(wrapper, 'Approve and export')).toBeUndefined()
    // The page loads the revision the server has, so checking again is not refused the same way.
    expect(reloadDocument).toHaveBeenCalledTimes(2)
    const again = buttonNamed(wrapper, 'Check again')!
    expect(document.activeElement).toBe(again.element)

    await again.trigger('click')
    await flushPromises()
    expect(validateDocument).toHaveBeenLastCalledWith(7, 42, 13, expect.any(String))
  })

  it('says an approval is gone and keeps the check when the server no longer has it', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    vi.mocked(approveExport).mockRejectedValue(new ApiRequestError(404, problem(404, 'NOT_FOUND', 'No validation manifest 3.')))

    await buttonNamed(wrapper, 'Approve and export')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('That approval is no longer available. Check again.')
    expect(text(wrapper)).not.toContain('manifest 3')
    expect(text(wrapper)).toContain('Ready to export.')
    expect(buttonNamed(wrapper, 'Check again')).toBeTruthy()
  })

  it("shows the server's own reason for a refusal it explains", async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    vi.mocked(approveExport).mockRejectedValue(
      new ApiRequestError(422, problem(422, 'EXPORT_BLOCKED', 'This version has problems that block export.')),
    )

    await buttonNamed(wrapper, 'Approve and export')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('This version has problems that block export.')
  })

  it('says so when approving is refused for too many requests, and keeps the check to try again from', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    vi.mocked(approveExport).mockRejectedValue(
      new ApiRequestError(429, problem(429, 'RATE_LIMITED', 'Too many requests. Try again in 30 seconds.')),
    )

    await buttonNamed(wrapper, 'Approve and export')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('Too many requests. Try again in 30 seconds.')
    expect(text(wrapper)).not.toContain('429')
    expect(text(wrapper)).toContain('Ready to export.')
    expect(buttonNamed(wrapper, 'Approve and export')).toBeTruthy()
    expect(exportDocument).not.toHaveBeenCalled()
  })

  it.each([
    [
      'a server older than this page',
      new ApiRequestError(404, problem(404, 'NOT_FOUND', 'No static resource api/v1/workspaces/7/documents/42/validate.')),
      'The Brownie server that answered is older than this page and does not have document checks yet. Reloading will not change that: the server needs to be updated and restarted.',
    ],
    ['an ended session', new ApiRequestError(401, problem(401, 'UNAUTHENTICATED', 'x')), 'Your session has ended. Sign in again to carry on.'],
    ['a busy server', new ApiRequestError(503, problem(503, 'UNAVAILABLE', 'The renderer is busy. Try again in a minute.')), 'The renderer is busy. Try again in a minute.'],
    ['no connection', new TypeError('Failed to fetch'), 'Brownie could not be reached. Check your connection, then try again.'],
    ['a trashed document', new ApiRequestError(404, problem(404, 'NOT_FOUND', 'No document 42 in this workspace.')), 'This document is no longer here. It may have been moved to the trash.'],
  ])('says what went wrong when checking meets %s, and offers to check again', async (_name, failure, sentence) => {
    vi.mocked(validateDocument).mockRejectedValue(failure)
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    expect(wrapper.find('[role="alert"]').text().replace(/\s+/g, ' ')).toBe(sentence)
    expect(buttonNamed(wrapper, 'Check again')).toBeTruthy()
    expect(wrapper.find('dialog').attributes('open')).toBeDefined()
  })

  it('says the export route is missing on an older server in its own words', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    vi.mocked(approveExport).mockResolvedValue(approval())
    vi.mocked(exportDocument).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'NOT_FOUND', 'No endpoint POST /api/v1/workspaces/7/documents/42/export.')),
    )

    await buttonNamed(wrapper, 'Approve and export')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('does not have exports yet')
  })
})

describe('a PDF form', () => {
  it('offers only a PDF, says why, and approves and exports that', async () => {
    vi.mocked(validateDocument).mockResolvedValue(manifest())
    const { wrapper } = await mountDialog({ pdfOnly: true })
    await openDialog(wrapper)

    expect(text(wrapper)).toContain('Format: PDF')
    expect(text(wrapper)).toContain('This is a PDF form, so Brownie fills it and exports it as a PDF. It does not turn it into a Word file.')
    expect(wrapper.findAll('input[name="export-format"]')).toHaveLength(0)
    expect(await axe(wrapper.element)).toHaveNoViolations()

    vi.mocked(approveExport).mockResolvedValue(approval({ format: 'PDF' }))
    vi.mocked(exportDocument).mockResolvedValue(receipt({ format: 'PDF', docxArtifactId: null, docxSha256: null, isCompletePair: false }))
    await buttonNamed(wrapper, 'Approve and export')!.trigger('click')
    await flushPromises()

    expect(approveExport).toHaveBeenCalledWith(7, 42, 3, 'PDF')
    expect(wrapper.findAll('.export-file a').map((link) => link.text().trim())).toEqual(['Download PDF'])
    expect(await axe(wrapper.element)).toHaveNoViolations()

    // A PDF form has one format, so going back from the file lands on the button that makes it again.
    const again = buttonNamed(wrapper, 'Export again')!
    ;(again.element as HTMLButtonElement).focus()
    await again.trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(buttonNamed(wrapper, 'Approve and export')!.element)
  })

  it('keeps offering the three formats for a Word form', async () => {
    vi.mocked(validateDocument).mockResolvedValue(manifest())
    const { wrapper } = await mountDialog({ pdfOnly: false })
    await openDialog(wrapper)

    expect(wrapper.findAll<HTMLInputElement>('input[name="export-format"]').map((input) => input.element.value)).toEqual(['DOCX', 'PDF', 'BOTH'])
    expect(text(wrapper)).not.toContain('This is a PDF form')
  })
})

describe('when the document changes under it', () => {
  it('clears what it found for the old revision and asks for a new check', async () => {
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)
    expect(linkNamed(wrapper, 'Download PDF')).toBeTruthy()

    await wrapper.setProps({ currentRevisionId: 11 })

    expect(wrapper.find('[role="alert"]').text()).toBe('This document changed. Check it again.')
    expect(text(wrapper)).not.toContain('Ready to export.')
    expect(wrapper.findAll('a')).toHaveLength(0)
    // The copies elsewhere were of the old export, so they go with it.
    expect(wrapper.findComponent(DriveStub).exists()).toBe(false)
  })
})

describe('where it stands', () => {
  it('says the save and the check are done once the check is in, in words as well as marks', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    expect(steps(wrapper)).toEqual([
      { state: 'done', label: 'Saved your changes' },
      { state: 'done', label: 'Checked this version' },
    ])
    // The mark alone is not announced, so a step that is done also says so in words nobody sees.
    expect(wrapper.findAll('.export-dialog__steps .step')[0]!.text()).toBe('Done: Saved your changes')
  })

  it('shows the save under way, then the check', async () => {
    const saving = deferred<boolean>()
    const { wrapper } = await mountDialog({ unsavedWork: true, saveBeforeExport: vi.fn(() => saving.promise) })
    await openDialog(wrapper)

    expect(steps(wrapper)).toEqual([
      { state: 'active', label: 'Saving your changes' },
      { state: 'pending', label: 'Check this version' },
    ])
    expect(wrapper.findAll('.export-dialog__steps .step')[0]!.text()).toBe('In progress: Saving your changes')

    const checking = deferred<ValidationManifestResponse>()
    vi.mocked(validateDocument).mockImplementation(() => {
      serverRevision = 10
      return checking.promise
    })
    await wrapper.setProps({ unsavedWork: false })
    saving.resolve(true)
    await flushPromises()
    expect(steps(wrapper)).toEqual([
      { state: 'done', label: 'Saved your changes' },
      { state: 'active', label: 'Checking this version' },
    ])

    checking.resolve(manifest())
    await flushPromises()
    expect(steps(wrapper).map((row) => row.state)).toEqual(['done', 'done'])
  })

  it('shows what failed in the step it failed in, with an icon as well as a colour', async () => {
    const refused = await mountDialog({ unsavedWork: true, saveBeforeExport: vi.fn(async () => false) })
    await openDialog(refused.wrapper)
    expect(steps(refused.wrapper)).toEqual([
      { state: 'failed', label: 'Your changes are not saved' },
      { state: 'pending', label: 'Check this version' },
    ])
    expect(refused.wrapper.find('.step--failed .step__marker svg').exists()).toBe(true)

    vi.mocked(validateDocument).mockRejectedValue(new TypeError('Failed to fetch'))
    const failed = await mountDialog()
    await openDialog(failed.wrapper)
    expect(steps(failed.wrapper)).toEqual([
      { state: 'done', label: 'Saved your changes' },
      { state: 'failed', label: 'Could not check this version' },
    ])
    expect(failed.wrapper.find('[role="alert"]').text()).toBe('Brownie could not be reached. Check your connection, then try again.')
  })

  it('names the version the page shows once the check is in: in the subtitle, the step and the note', async () => {
    const { wrapper } = await mountDialog({ numbered: true })
    await openDialog(wrapper)

    expect(subtitle(wrapper)).toBe('Spring budget: minutes • Version 10')
    expect(steps(wrapper)[1]).toEqual({ state: 'done', label: 'Checked version 10' })
    expect(text(wrapper)).toContain('You approve exactly version 10 in this format. If anything changes after this, Brownie checks and asks again.')
  })

  it('says "this version" where the page gave no number', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    expect(subtitle(wrapper)).toBe('Spring budget: minutes')
    expect(steps(wrapper)[1]!.label).toBe('Checked this version')
    expect(text(wrapper)).toContain('You approve exactly this version in this format.')
  })

  it('does not name a version for a check of one the page has not loaded yet', async () => {
    const reload = deferred<boolean>()
    const { wrapper } = await mountDialog({ numbered: true, reloadDocument: vi.fn(() => reload.promise) })
    await openDialog(wrapper)

    // The check made revision 10, but the page still shows 9, and the only number it can give is 9's.
    expect(subtitle(wrapper)).toBe('Spring budget: minutes')
    expect(steps(wrapper)[1]!.label).toBe('Checked this version')

    await wrapper.setProps({ currentRevisionId: 10, currentRevisionNumber: 10 })
    reload.resolve(true)
    await flushPromises()
    expect(subtitle(wrapper)).toBe('Spring budget: minutes • Version 10')
    expect(steps(wrapper)[1]!.label).toBe('Checked version 10')
  })

  it('says when it was exported once there are files', async () => {
    const when = new Date()
    onRecord(receipt({ exportedAt: when.toISOString() }))
    const { wrapper } = await mountDialog({ currentRevisionId: 10, numbered: true })
    await openDialog(wrapper)

    expect(subtitle(wrapper)).toBe(`Version 10 • approved and exported today at ${clockFormat.format(when).replace(/\s+/g, ' ')}`)
  })

  it('names the day of an export from an earlier one, and still reads well without a version', async () => {
    onRecord(receipt({ exportedAt: '2020-03-04T12:00:00Z' }))
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    // The day is named in the person's own format, so only that it is an earlier day, not today, is held to.
    expect(subtitle(wrapper)).toMatch(/^Approved and exported .*2020.* at \d{1,2}:\d{2}/)
    expect(subtitle(wrapper)).not.toContain('today')
  })
})

describe('when nothing needs fixing', () => {
  it('says so on its own panel, with what the page counted beside it', async () => {
    const { wrapper } = await mountDialog({ fillCounts: { total: 9, filled: 9, accepted: 9, locked: 1 } })
    await openDialog(wrapper)

    const panel = wrapper.get('.export-dialog__clear')
    expect(panel.get('.export-dialog__clear-title').text()).toBe('Brownie checked this version. It found nothing to fix.')
    expect(panel.findAll('.tag').map((tag) => tag.text())).toEqual(['9 of 9 fill spots filled', '9 accepted', '1 locked'])
    expect(wrapper.find('.export-dialog__findings').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('leaves out what is not there: no counts from the page, and nothing locked', async () => {
    const bare = await mountDialog()
    await openDialog(bare.wrapper)
    expect(bare.wrapper.find('.export-dialog__counts').exists()).toBe(false)
    expect(bare.wrapper.get('.export-dialog__clear').text()).toBe('Brownie checked this version. It found nothing to fix.')

    const single = await mountDialog({ fillCounts: { total: 1, filled: 1, accepted: 0, locked: 0 } })
    await openDialog(single.wrapper)
    expect(single.wrapper.findAll('.export-dialog__counts .tag').map((tag) => tag.text())).toEqual(['1 of 1 fill spot filled', '0 accepted'])
  })

  it('offers the three formats as cards that say what each is good for', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    expect(wrapper.findAll('.export-format').map((card) => [card.get('.export-format__title').text(), card.get('.export-format__hint').text()])).toEqual([
      ['Word (.docx)', 'Edit it again in Word'],
      ['PDF', 'Looks the same everywhere'],
      ['Word and PDF', 'Both files from this version'],
    ])
    expect(checkedFormat(wrapper)).toBe('BOTH')
    // The choice is the radio's name and what it is good for is its description, so it is said in that order.
    const radio = wrapper.get('input[value="PDF"]')
    expect(document.getElementById(radio.attributes('aria-labelledby')!)?.textContent).toBe('PDF')
    expect(document.getElementById(radio.attributes('aria-describedby')!)?.textContent).toBe('Looks the same everywhere')
    expect(wrapper.findAll('.export-format .doc-tile').every((tile) => tile.attributes('aria-hidden') === 'true')).toBe(true)
  })

  it('offers Cancel and the approval, with Check again to the side', async () => {
    const { wrapper, opener } = await mountDialog()
    await openDialog(wrapper)

    expect(wrapper.findAll('.dialog__foot button').map((button) => button.text())).toEqual(['Check again', 'Cancel', 'Approve and export'])
    expect(buttonNamed(wrapper, 'Check again')!.classes()).toContain('button--quiet')
    expect(buttonNamed(wrapper, 'Approve and export')!.classes()).toContain('button--primary')
    expect(buttonNamed(wrapper, 'Approve and export')!.attributes('aria-disabled')).toBe('false')

    await buttonNamed(wrapper, 'Cancel')!.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('closed')).toHaveLength(1)
    expect(approveExport).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(opener)
  })

  it('keeps the live line out of sight until there are files, and shows it as the result after', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    const outcome = wrapper.get('.export-dialog__outcome')
    expect(outcome.classes()).toContain('visually-hidden')
    expect(outcome.get('[aria-live="polite"]').text()).toBe('Ready to export.')

    vi.mocked(approveExport).mockResolvedValue(approval())
    vi.mocked(exportDocument).mockResolvedValue(receipt())
    await buttonNamed(wrapper, 'Approve and export')!.trigger('click')
    await flushPromises()
    expect(outcome.classes()).not.toContain('visually-hidden')
    expect(outcome.get('[aria-live="polite"]').text()).toBe('Both files exported.')
    expect(outcome.find('svg').exists()).toBe(true)
  })

  it('shows the copies elsewhere only once there is an export', async () => {
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)

    expect(wrapper.find('.export-dialog__elsewhere').exists()).toBe(false)
    expect(wrapper.findComponent(DriveStub).exists()).toBe(false)
    expect(wrapper.findComponent(CalendarStub).exists()).toBe(false)
  })
})

describe('once exported', () => {
  it('lists each file with what it is and a way to download it, the first as the main one', async () => {
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    const rows = wrapper.findAll('.export-file')
    expect(rows.map((row) => [row.get('.export-file__name').text(), row.get('.export-file__meta').text(), row.get('a').text().trim()])).toEqual([
      ['Spring budget minutes.docx', 'Word', 'Download Word file (.docx)'],
      ['Spring budget minutes.pdf', 'PDF', 'Download PDF'],
    ])
    const kinds = rows.map((row) => row.get('a').classes().filter((name) => ['button--primary', 'button--secondary'].includes(name)))
    expect(kinds).toEqual([['button--primary'], ['button--secondary']])
    expect(wrapper.findAll('.export-file .doc-tile').every((tile) => tile.attributes('aria-hidden') === 'true')).toBe(true)
  })

  it('puts the files in place of the steps and of what the check found', async () => {
    vi.mocked(validateDocument).mockResolvedValue(manifest({ findings: [BLOCKING_FINDINGS[1]!] }))
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    expect(wrapper.find('.export-dialog__findings').exists()).toBe(true)

    vi.mocked(approveExport).mockResolvedValue(approval())
    vi.mocked(exportDocument).mockResolvedValue(receipt())
    await buttonNamed(wrapper, 'Approve and export')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.export-dialog__steps').exists()).toBe(false)
    expect(wrapper.find('.export-dialog__findings').exists()).toBe(false)
    expect(wrapper.find('.export-dialog__clear').exists()).toBe(false)
    expect(wrapper.find('.export-dialog__note').exists()).toBe(false)
    expect(wrapper.get('.export-dialog__status').text()).toBe('Both files exported.')
  })

  it('offers Done and Export again, and nothing that would check again', async () => {
    onRecord()
    const { wrapper, opener } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    expect(wrapper.findAll('.dialog__foot button').map((button) => button.text())).toEqual(['Export again', 'Done'])
    await buttonNamed(wrapper, 'Done')!.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('closed')).toHaveLength(1)
    expect(document.activeElement).toBe(opener)
  })

  it('warns rather than celebrates when a file it was asked for could not be made', async () => {
    onRecord(receipt({ pdfArtifactId: null, pdfSha256: null, isCompletePair: false }))
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    expect(wrapper.get('.export-dialog__status').text()).toBe('The DOCX was exported; the PDF could not be made for this version.')
    expect(wrapper.find('.export-dialog__outcome-icon--partial').exists()).toBe(true)
  })

  it('puts the copies elsewhere under a heading of their own, with the export for the Drive panel', async () => {
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    expect(wrapper.get('.export-dialog__divider').text()).toBe('Save a copy elsewhere')
    expect(wrapper.get('.export-dialog__divider').element.tagName).toBe('H3')
    const panels = wrapper.get('.export-dialog__panels')
    expect(panels.findComponent(DriveStub).props('receipt')).toMatchObject({ id: 5 })
    expect(panels.findComponent(CalendarStub).exists()).toBe(true)
  })
})

describe('closing', () => {
  it('closes from its Close button and returns focus to the button that opened it', async () => {
    const { wrapper, opener } = await mountDialog()
    await openDialog(wrapper)
    expect(document.activeElement?.id).toBe('export-dialog-heading')
    expect(wrapper.find('dialog').attributes('aria-labelledby')).toBe('export-dialog-heading')

    await buttonNamed(wrapper, 'Close')!.trigger('click')
    await flushPromises()

    expect(wrapper.emitted('closed')).toHaveLength(1)
    expect(wrapper.find('dialog').attributes('open')).toBeUndefined()
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(document.activeElement).toBe(opener)
  })

  it('leaves focus where the page puts it when the page closes the dialog to show a field', async () => {
    vi.mocked(validateDocument).mockResolvedValue(manifest({ hasUnresolvedBlocking: true, findings: BLOCKING_FINDINGS }))
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    const field = document.createElement('input')
    field.id = 'edit-meeting.title'
    document.body.appendChild(field)

    await buttonNamed(wrapper, 'Go to Meeting title')!.trigger('click')
    wrapper.vm.close()
    field.focus()
    await flushPromises()

    expect(wrapper.emitted('closed')).toHaveLength(1)
    expect(document.activeElement).toBe(field)
  })

  it('closes on Escape and returns focus to the button that opened it', async () => {
    const { wrapper, opener } = await mountDialog()
    await openDialog(wrapper)

    await wrapper.find('dialog').trigger('keydown', { key: 'Escape' })
    await flushPromises()

    expect(wrapper.emitted('closed')).toHaveLength(1)
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(document.activeElement).toBe(opener)
  })
})

describe('sharing', () => {
  function offerSharing(accepts: (file: File) => boolean = () => true) {
    const canShare = vi.fn((data?: ShareData) => (data?.files ?? []).every(accepts))
    const share = vi.fn(async (_data?: ShareData) => undefined)
    Object.defineProperty(window.navigator, 'canShare', { value: canShare, configurable: true })
    Object.defineProperty(window.navigator, 'share', { value: share, configurable: true })
    const fetched = vi.fn(async (_url: string, _init?: RequestInit) => ({ ok: true, status: 200, blob: async () => new Blob(['x']) }))
    vi.stubGlobal('fetch', fetched)
    return { canShare, share, fetched }
  }

  it('offers no Share button where the browser cannot share files', async () => {
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    expect(linkNamed(wrapper, 'Download PDF')).toBeTruthy()
    expect(buttonNamed(wrapper, 'Share…')).toBeUndefined()
  })

  it("hands the exported files to the device's own share sheet", async () => {
    const { share, fetched } = offerSharing()
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    await buttonNamed(wrapper, 'Share…')!.trigger('click')
    await flushPromises()

    expect(fetched).toHaveBeenCalledWith(artifactDownloadUrl(7, 13), { credentials: 'include' })
    expect(fetched).toHaveBeenCalledWith(artifactDownloadUrl(7, 14), { credentials: 'include' })
    expect(share).toHaveBeenCalledTimes(1)
    const shared = share.mock.calls[0]![0]!
    expect(shared.title).toBe('Spring budget: minutes')
    expect(shared.files!.map((file) => [file.name, file.type])).toEqual([
      ['Spring budget minutes.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
      ['Spring budget minutes.pdf', 'application/pdf'],
    ])
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('shares only the files the device accepts', async () => {
    const { share } = offerSharing((file) => file.type === 'application/pdf')
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    await buttonNamed(wrapper, 'Share…')!.trigger('click')
    await flushPromises()

    expect(share.mock.calls[0]![0]!.files!.map((file) => file.name)).toEqual(['Spring budget minutes.pdf'])
  })

  it('says nothing when the person closes the share sheet, and says so when sharing fails', async () => {
    const { share } = offerSharing()
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    share.mockRejectedValueOnce(new DOMException('Share canceled', 'AbortError'))
    await buttonNamed(wrapper, 'Share…')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    share.mockRejectedValueOnce(new DOMException('Not allowed', 'NotAllowedError'))
    await buttonNamed(wrapper, 'Share…')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toBe(
      'Sharing did not work on this device. Download the file and share it from there.',
    )
  })
})

describe('Google', () => {
  it('hands the export and the document to the Drive and Calendar panels', async () => {
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)

    expect(wrapper.findComponent(DriveStub).props()).toMatchObject({ workspaceId: 7, documentId: 42, unsavedWork: false, receipt: { id: 5 } })
    expect(wrapper.findComponent(CalendarStub).props()).toMatchObject({
      workspaceId: 7,
      documentId: 42,
      documentTitle: 'Spring budget: minutes',
      suggestedDate: '2026-10-05',
      unsavedWork: false,
    })
  })

  it('shows neither panel where Google is not offered', async () => {
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 10485760,
      uploadMediaTypes: [],
      assistSourceMediaTypes: [],
      templateMediaTypes: [],
      trashRetentionDays: 30,
      mcpEnabled: false,
      googleConnectorAccess: [],
      googleActions: [],
    })
    vi.mocked(listActions).mockResolvedValue([])
    vi.mocked(listConnections).mockResolvedValue([])
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10, realPanels: true })
    await openDialog(wrapper)

    expect(text(wrapper)).not.toContain('Google')
    // Nothing is inside the box that holds them, which is what hides the heading above it.
    expect(wrapper.find('.export-dialog__panels').element.children).toHaveLength(0)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})

describe('accessibility', () => {
  it('has no violations with problems listed', async () => {
    vi.mocked(validateDocument).mockResolvedValue(manifest({ hasUnresolvedBlocking: true, findings: BLOCKING_FINDINGS }))
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no violations when it is ready to export, with the version and the counts in place', async () => {
    const { wrapper } = await mountDialog({ numbered: true, fillCounts: { total: 9, filled: 9, accepted: 9, locked: 1 } })
    await openDialog(wrapper)
    expect(wrapper.findAll('.export-format')).toHaveLength(3)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no violations while it checks', async () => {
    vi.mocked(validateDocument).mockReturnValue(deferred<ValidationManifestResponse>().promise)
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    expect(steps(wrapper)[1]!.state).toBe('active')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no violations when the page could not save', async () => {
    const { wrapper } = await mountDialog({ unsavedWork: true, saveBeforeExport: vi.fn(async () => false) })
    await openDialog(wrapper)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no violations when the check failed', async () => {
    vi.mocked(validateDocument).mockRejectedValue(new TypeError('Failed to fetch'))
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no violations with warnings listed and the export still offered', async () => {
    vi.mocked(validateDocument).mockResolvedValue(manifest({ findings: [BLOCKING_FINDINGS[1]!, BLOCKING_FINDINGS[2]!] }))
    const { wrapper } = await mountDialog()
    await openDialog(wrapper)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no violations with the files ready to download and share', async () => {
    Object.defineProperty(window.navigator, 'canShare', { value: () => true, configurable: true })
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, blob: async () => new Blob(['x']) })))
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)
    expect(buttonNamed(wrapper, 'Share…')).toBeTruthy()
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
