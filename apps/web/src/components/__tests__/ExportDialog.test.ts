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
      await self?.setProps({ currentRevisionId: serverRevision })
      return true
    })
  const wrapper: Dialog = mount(ExportDialog, {
    props: {
      workspaceId: 7,
      documentId: 42,
      documentTitle: 'Spring budget: minutes',
      currentRevisionId: options.currentRevisionId ?? 9,
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

    // "Done", so only the button at the top is named Close.
    expect(wrapper.findAll('button').filter((button) => button.text() === 'Close')).toHaveLength(1)
    await buttonNamed(wrapper, 'Done')!.trigger('click')
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
    // The format on screen is the one approved, so it matches the file offered below it.
    expect(checkedFormat(wrapper)).toBe('DOCX')
    expect(linkNamed(wrapper, 'Download Word file (.docx)')?.attributes('href')).toBe(artifactDownloadUrl(7, 13))
    expect(wrapper.findComponent(DriveStub).props('receipt')).toMatchObject({ id: 5 })
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
    expect(buttonNamed(wrapper, 'Approve and export')).toBeUndefined()
    expect(wrapper.find('input[name="export-format"]').exists()).toBe(false)

    await buttonNamed(wrapper, 'Go to Meeting title')!.trigger('click')
    expect(wrapper.emitted('go-to-field')).toEqual([['meeting.title']])
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
    expect(button.text()).toBe('Approve and export')
    expect(text(wrapper)).toContain('PDF exported.')
    expect(linkNamed(wrapper, 'Download PDF')?.attributes('href')).toBe(artifactDownloadUrl(7, 14))
    expect(linkNamed(wrapper, 'Download PDF')?.attributes('download')).toBe('')
    expect(linkNamed(wrapper, 'Download Word file (.docx)')).toBeUndefined()
    // The download is what comes next now, so it is the main button, and exporting again steps back.
    expect(linkNamed(wrapper, 'Download PDF')?.classes()).toContain('button--primary')
    expect(button.classes()).toContain('button--secondary')
    expect(button.classes()).not.toContain('button--primary')
    // Said once, in the live region, so it is read once and a search for it finds one line.
    expect(wrapper.find('[aria-live="polite"]').text()).toBe('PDF exported.')
    expect(wrapper.findAll('p').filter((line) => line.text().includes('exported'))).toHaveLength(1)
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
    expect(wrapper.findAll('.export-dialog__links a').map((link) => link.text().trim())).toEqual(links)
  })

  it('clears the approval and files when the format changes', async () => {
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10 })
    await openDialog(wrapper)
    expect(linkNamed(wrapper, 'Download PDF')).toBeTruthy()

    await wrapper.find('input[value="DOCX"]').setValue(true)

    expect(wrapper.findAll('a')).toHaveLength(0)
    expect(text(wrapper)).not.toContain('Both files exported.')
    expect(wrapper.findComponent(DriveStub).props('receipt')).toBeNull()
    expect(approveExport).not.toHaveBeenCalled()
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
    expect(wrapper.findAll('.export-dialog__links a').map((link) => link.text().trim())).toEqual(['Download PDF'])
    expect(await axe(wrapper.element)).toHaveNoViolations()
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
    expect(wrapper.findComponent(DriveStub).props('receipt')).toBeNull()
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
      googleConnectorAccess: [],
      googleActions: [],
    })
    vi.mocked(listActions).mockResolvedValue([])
    vi.mocked(listConnections).mockResolvedValue([])
    onRecord()
    const { wrapper } = await mountDialog({ currentRevisionId: 10, realPanels: true })
    await openDialog(wrapper)

    expect(text(wrapper)).not.toContain('Google')
    expect(wrapper.find('.export-dialog__elsewhere').element.children).toHaveLength(0)
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
