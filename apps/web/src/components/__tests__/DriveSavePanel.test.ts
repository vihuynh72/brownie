import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import DriveSavePanel from '@/components/DriveSavePanel.vue'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    getCapabilities: vi.fn(),
    listConnections: vi.fn(),
    listActions: vi.fn(),
    getAction: vi.fn(),
    proposeDriveSave: vi.fn(),
    proposeDocAppend: vi.fn(),
    approveAction: vi.fn(),
    reconcileAction: vi.fn(),
    cancelAction: vi.fn(),
    acknowledgeAction: vi.fn(),
    startGoogleConsent: vi.fn(),
  }
})
vi.mock('@/navigation', () => ({ navigateTo: vi.fn(), releaseIfStillHere: vi.fn() }))

import {
  ApiRequestError,
  acknowledgeAction,
  approveAction,
  getAction,
  getCapabilities,
  listActions,
  listConnections,
  proposeDocAppend,
  proposeDriveSave,
  reconcileAction,
  startGoogleConsent,
  type ActionResponse,
  type CapabilitiesResponse,
  type ConnectionResponse,
  type ExportReceiptResponse,
} from '@/api/client'
import { navigateTo } from '@/navigation'
import { resetCapabilitiesCache } from '@/capabilities'

const stub = { template: '<div />' }
const mounted: { unmount(): void }[] = []

function receipt(overrides: Partial<ExportReceiptResponse> = {}): ExportReceiptResponse {
  return {
    id: 12,
    documentId: 42,
    revisionId: 9,
    templateVersionId: 2,
    exportApprovalId: 4,
    validationManifestId: 3,
    docxArtifactId: 13,
    docxSha256: 'd'.repeat(64),
    pdfArtifactId: 14,
    pdfSha256: 'e'.repeat(64),
    format: 'BOTH',
    isCompletePair: true,
    exportedAt: '2026-09-28T09:55:00Z',
    ...overrides,
  }
}

async function mountPanel(options: { receipt?: ExportReceiptResponse | null; unsavedWork?: boolean } = {}) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: stub },
      { path: '/connections', component: stub },
    ],
  })
  router.push('/')
  await router.isReady()
  const wrapper = mount(DriveSavePanel, {
    props: {
      workspaceId: 7,
      documentId: 42,
      receipt: options.receipt === undefined ? receipt() : options.receipt,
      unsavedWork: options.unsavedWork ?? false,
    },
    attachTo: document.body,
    global: { plugins: [router] },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountPanel>>

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function problem(status: number, code: string, reason?: string) {
  return {
    status,
    title: 't',
    code,
    detail: 'The server said something else.',
    correlationId: 'c',
    fields: [],
    recoveryActions: [],
    ...(reason === undefined ? {} : { reason }),
  }
}

function capabilities(googleActions?: ActionResponse['type'][]): CapabilitiesResponse {
  return {
    maxUploadBytes: 10485760,
    uploadMediaTypes: [],
    assistSourceMediaTypes: [],
    templateMediaTypes: [],
    trashRetentionDays: 30,
    googleConnectorAccess: googleActions && googleActions.length > 0 ? ['DRIVE_SAVING'] : [],
    ...(googleActions === undefined ? {} : { googleActions }),
  }
}

function saving(overrides: Partial<ConnectionResponse> = {}): ConnectionResponse {
  return {
    id: 5,
    provider: 'GOOGLE',
    access: 'DRIVE_SAVING',
    state: 'ACTIVE',
    accountEmail: 'me@example.org',
    grantedScopes: ['https://www.googleapis.com/auth/drive.file'],
    reconnectReason: null,
    connectedAt: '2026-09-20T10:00:00Z',
    tokenIssuedAt: '2026-09-20T10:00:00Z',
    disconnectedAt: null,
    providerRevocation: null,
    grants: [],
    ...overrides,
  }
}

function payload(conversion = false) {
  return {
    schema: 'brownie.action/1',
    type: conversion ? 'DRIVE_SAVE_AS_GOOGLE_DOC' : 'DRIVE_SAVE_FILE',
    nonce: 'n',
    proposedBy: 3,
    workspace: 7,
    document: { id: 42, revision: 9, title: 'Spring Budget Planning minutes' },
    account: { connection: 5, email: 'me@example.org' },
    target: { place: 'MY_DRIVE_TOP', sharing: 'NOBODY' },
    content: {
      exportReceipt: 12,
      artifact: 13,
      format: 'DOCX',
      fileName: conversion ? 'Spring Budget Planning minutes' : 'Spring Budget Planning minutes.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      bytes: 24_700,
      sha256: 'a'.repeat(64),
      md5: 'b'.repeat(32),
    },
    effect: { creates: 'NEW_FILE', conversion: conversion ? 'GOOGLE_DOC' : 'NONE' },
  }
}

const HASH = 'c'.repeat(64)
/** How the panel names a save: its file and when it was prepared, in the viewer's own clock. */
const AT = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', second: '2-digit' }).format(
  new Date('2026-09-28T10:00:00Z'),
)
const FILE = `Spring Budget Planning minutes.docx, prepared at ${AT}`

function action(overrides: Partial<ActionResponse> = {}): ActionResponse {
  const conversion = overrides.type === 'DRIVE_SAVE_AS_GOOGLE_DOC'
  return {
    id: 31,
    type: 'DRIVE_SAVE_FILE',
    documentId: 42,
    state: 'AWAITING_APPROVAL',
    payload: payload(conversion),
    payloadHash: HASH,
    createdAt: '2026-09-28T10:00:00Z',
    expiresAt: '2026-09-28T10:30:00Z',
    approvedAt: null,
    approvalExpiresAt: null,
    sent: false,
    verification: null,
    conversionCheck: null,
    failure: null,
    outcomeAcknowledged: false,
    externalLink: null,
    finishedAt: null,
    ...overrides,
  }
}

function text(wrapper: Wrapper): string {
  return wrapper.text().replace(/\s+/g, ' ')
}

function buttonNamed(wrapper: Wrapper, name: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text().replace(/\s+/g, ' ').trim() === name)
  if (!found) {
    throw new Error(`No button named "${name}" among: ${wrapper.findAll('button').map((b) => b.text()).join(' | ')}`)
  }
  return found
}

function choices(wrapper: Wrapper): string[] {
  return wrapper.findAll('input[type="radio"]').map((input) => (input.element as HTMLInputElement).value)
}

beforeEach(() => {
  resetCapabilitiesCache()
  vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC']))
  vi.mocked(listConnections).mockResolvedValue([saving()])
  vi.mocked(listActions).mockResolvedValue([])
})

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.clearAllMocks()
})

describe('where saving is offered', () => {
  it('offers exactly the files the export offers to download, and a Google Doc from the Word file', async () => {
    const both = await mountPanel()
    expect(choices(both)).toEqual(['WORD_FILE', 'PDF_FILE', 'GOOGLE_DOC'])

    const pdfOnly = await mountPanel({ receipt: receipt({ format: 'PDF' }) })
    expect(choices(pdfOnly)).toEqual(['PDF_FILE'])

    // The PDF could not be made, so the export offers the Word file instead, and so does saving.
    const pdfMissing = await mountPanel({ receipt: receipt({ format: 'PDF', pdfArtifactId: null }) })
    expect(choices(pdfMissing)).toEqual(['WORD_FILE', 'GOOGLE_DOC'])

    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE']))
    resetCapabilitiesCache()
    const noConversion = await mountPanel()
    expect(choices(noConversion)).toEqual(['WORD_FILE', 'PDF_FILE'])
  })

  it('shows what approving would do, and saves nothing until it is approved, with the hash of what was shown', async () => {
    vi.mocked(proposeDriveSave).mockResolvedValue(action())
    const wrapper = await mountPanel()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(proposeDriveSave).toHaveBeenCalledWith(7, 42, 'WORD_FILE')
    expect(approveAction).not.toHaveBeenCalled()
    expect(text(wrapper)).toContain('A new Word file named "Spring Budget Planning minutes.docx", 24.1 KB: exactly the file you exported.')
    expect(text(wrapper)).toContain('At the top of My Drive in the Google Drive of me@example.org, shared with no one.')
    expect(window.document.activeElement?.id).toBe('drive-save-31')

    vi.mocked(approveAction).mockResolvedValue(
      action({ state: 'SUCCEEDED', sent: true, verification: 'MATCHED', externalLink: 'https://drive.google.com/file/d/abc/view' }),
    )
    await buttonNamed(wrapper, `Save to Google Drive ${FILE}`).trigger('click')
    await flushPromises()

    expect(approveAction).toHaveBeenCalledTimes(1)
    expect(approveAction).toHaveBeenCalledWith(7, 31, HASH)
    expect(text(wrapper)).toContain('Saved "Spring Budget Planning minutes.docx" to your Google Drive.')
    const link = wrapper.find('a[target="_blank"]')
    expect(link.attributes('href')).toBe('https://drive.google.com/file/d/abc/view')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    expect(wrapper.findAll('button').map((button) => button.text())).not.toContain('Try saving again')
  })

  it("says what a converted Google Doc's check found", async () => {
    vi.mocked(listActions).mockResolvedValue([
      action({
        type: 'DRIVE_SAVE_AS_GOOGLE_DOC',
        state: 'SUCCEEDED',
        sent: true,
        verification: 'CONVERSION_DIFFERS',
        conversionCheck: { total: 4, found: 3 },
        externalLink: 'https://docs.google.com/document/d/abc/edit',
      }),
    ])
    const wrapper = await mountPanel()

    expect(text(wrapper)).toContain('Saved "Spring Budget Planning minutes" to your Google Drive as a Google Doc.')
    expect(text(wrapper)).toContain("Brownie found 3 of 4 filled-in values in the Google Doc's text")
    expect(text(wrapper)).toContain('Layout, tables, lists and formatting were not compared.')
    expect(wrapper.find('a[target="_blank"]').text()).toContain('Open in Google Docs')
  })

  it('never offers for approval a save it cannot state in full', async () => {
    vi.mocked(listActions).mockResolvedValue([
      action({ payload: { ...payload(), target: { place: 'MY_DRIVE_TOP', sharing: 'ANYONE_WITH_LINK' } } }),
    ])
    const wrapper = await mountPanel()

    expect(text(wrapper)).toContain('Brownie cannot show everything this save would do, so it is not offered for approval here.')
    expect(wrapper.findAll('button').some((button) => button.text().startsWith('Save to Google Drive'))).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text().startsWith('Cancel'))).toBe(true)
  })

  it('has no accessibility violations with a save shown', async () => {
    vi.mocked(listActions).mockResolvedValue([action(), action({ id: 30, state: 'OUTCOME_UNKNOWN', sent: true })])
    const wrapper = await mountPanel()
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})

describe('an approval whose answer is lost or refused', () => {
  it('reads the save again when the answer did not arrive, and never approves or prepares it again by itself', async () => {
    vi.mocked(listActions).mockResolvedValue([action()])
    vi.mocked(approveAction).mockRejectedValue(new TypeError('Failed to fetch'))
    vi.mocked(getAction).mockResolvedValue(action({ state: 'OUTCOME_UNKNOWN', sent: true, approvedAt: '2026-09-28T10:05:00Z' }))
    const wrapper = await mountPanel()

    await buttonNamed(wrapper, `Save to Google Drive ${FILE}`).trigger('click')
    await flushPromises()

    expect(approveAction).toHaveBeenCalledTimes(1)
    expect(getAction).toHaveBeenCalledWith(7, 31)
    expect(proposeDriveSave).not.toHaveBeenCalled()
    expect(text(wrapper)).toContain("Brownie's answer to your approval did not arrive. What Brownie knows now is shown below.")
    expect(text(wrapper)).toContain('Brownie did not send it again.')
    expect(wrapper.findAll('button').some((button) => button.text().startsWith('Save to Google Drive'))).toBe(false)
  })

  it('says whether the file was saved is not known when the save cannot be read again either', async () => {
    vi.mocked(listActions).mockResolvedValue([action()])
    vi.mocked(approveAction).mockRejectedValue(new ApiRequestError(502, undefined))
    vi.mocked(getAction).mockRejectedValue(new TypeError('Failed to fetch'))
    const wrapper = await mountPanel()

    await buttonNamed(wrapper, `Save to Google Drive ${FILE}`).trigger('click')
    await flushPromises()

    expect(text(wrapper)).toContain('Whether the file was saved is not known here yet')
    expect(text(wrapper)).toContain('do not prepare the same save again until then')

    vi.mocked(getAction).mockResolvedValue(action({ state: 'SUCCEEDED', sent: true }))
    await buttonNamed(wrapper, `Check again where saving ${FILE} stands`).trigger('click')
    await flushPromises()
    expect(approveAction).toHaveBeenCalledTimes(1)
    expect(text(wrapper)).toContain('Saved "Spring Budget Planning minutes.docx" to your Google Drive.')
  })

  it('says nothing was saved for a refusal that sent nothing, and offers to connect again', async () => {
    vi.mocked(listActions).mockResolvedValue([action()])
    vi.mocked(approveAction).mockRejectedValue(
      new ApiRequestError(409, problem(409, 'CONNECTION_RECONNECT_REQUIRED', 'TOKEN_REJECTED')),
    )
    vi.mocked(getAction).mockResolvedValue(action())
    const wrapper = await mountPanel()
    vi.mocked(listConnections).mockResolvedValue([saving({ state: 'RECONNECT_REQUIRED', reconnectReason: 'TOKEN_REJECTED' })])

    await buttonNamed(wrapper, `Save to Google Drive ${FILE}`).trigger('click')
    await flushPromises()

    expect(text(wrapper)).toContain("Nothing was sent this time. Google stopped accepting Brownie's access.")
    expect(wrapper.find('#drive-save-connect').text()).toBe('Connect Google Drive for saving again')
  })
})

describe('a save whose outcome is unknown', () => {
  it('asks Google what happened, and records that the person looked for themselves', async () => {
    const unknown = action({ state: 'OUTCOME_UNKNOWN', sent: true, approvedAt: '2026-09-28T10:05:00Z' })
    vi.mocked(listActions).mockResolvedValue([unknown])
    vi.mocked(reconcileAction).mockResolvedValue(unknown)
    vi.mocked(acknowledgeAction).mockResolvedValue({ ...unknown, outcomeAcknowledged: true })
    const wrapper = await mountPanel()

    await buttonNamed(wrapper, `Ask Google what happened to ${FILE}`).trigger('click')
    await flushPromises()
    expect(reconcileAction).toHaveBeenCalledWith(7, 31)

    await buttonNamed(wrapper, `I looked in my Google Drive for ${FILE}`).trigger('click')
    await flushPromises()
    expect(acknowledgeAction).toHaveBeenCalledWith(7, 31)
    expect(text(wrapper)).toContain('You said you looked in your Google Drive')
    expect(approveAction).not.toHaveBeenCalled()
  })

  it('keeps an older unresolved save on the list however many came after it', async () => {
    const recent = [36, 35, 34, 33, 32].map((id) => action({ id, state: 'CANCELLED' }))
    vi.mocked(listActions).mockResolvedValue([...recent, action({ id: 20, state: 'OUTCOME_UNKNOWN', sent: true }), action({ id: 19, state: 'SUCCEEDED' })])
    const wrapper = await mountPanel()

    expect(wrapper.find('#drive-save-20').exists()).toBe(true)
    expect(wrapper.find('#drive-save-19').exists()).toBe(false)
  })
})

describe('what the review of this panel found', () => {
  it('keeps an older save on the list once the person settled it, with its outcome and the focus on it', async () => {
    const recent = [36, 35, 34, 33, 32].map((id) => action({ id, state: 'CANCELLED' }))
    const old = action({ id: 20, state: 'OUTCOME_UNKNOWN', sent: true })
    vi.mocked(listActions).mockResolvedValue([...recent, old])
    vi.mocked(reconcileAction).mockResolvedValue(
      action({ id: 20, state: 'SUCCEEDED', sent: true, verification: 'MATCHED', externalLink: 'https://drive.google.com/file/d/abc/view' }),
    )
    const wrapper = await mountPanel()

    await buttonNamed(wrapper, `Ask Google what happened to ${FILE}`).trigger('click')
    await flushPromises()

    expect(wrapper.find('#drive-save-20').text()).toBe('Saved "Spring Budget Planning minutes.docx" to your Google Drive.')
    expect(window.document.activeElement?.id).toBe('drive-save-20')
    expect(wrapper.find('a[href="https://drive.google.com/file/d/abc/view"]').exists()).toBe(true)
  })

  it('still lists earlier saves, with the way to check them, when there is no export of this version', async () => {
    vi.mocked(listActions).mockResolvedValue([action({ state: 'OUTCOME_UNKNOWN', sent: true })])
    const wrapper = await mountPanel({ receipt: null })

    expect(wrapper.find('form').exists()).toBe(false)
    expect(text(wrapper)).toContain('Validate, approve and export this version of the document to save it to Google Drive.')
    expect(buttonNamed(wrapper, `Ask Google what happened to ${FILE}`).exists()).toBe(true)
  })

  it('never lets a listing that set off before an approval put back the older state of that save', async () => {
    vi.mocked(listActions).mockResolvedValue([action()])
    vi.mocked(listConnections).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const wrapper = await mountPanel()

    let answerListing: (listed: ActionResponse[]) => void = () => {}
    vi.mocked(listActions).mockReturnValueOnce(new Promise((resolve) => (answerListing = resolve)))
    vi.mocked(listConnections).mockResolvedValue([saving()])
    await buttonNamed(wrapper, 'Check again for saving to Google Drive').trigger('click')
    vi.mocked(approveAction).mockResolvedValue(action({ state: 'SUCCEEDED', sent: true, verification: 'MATCHED' }))
    await buttonNamed(wrapper, `Save to Google Drive ${FILE}`).trigger('click')
    await flushPromises()
    expect(text(wrapper)).toContain('Saved "Spring Budget Planning minutes.docx" to your Google Drive.')

    answerListing([action()])
    await flushPromises()
    expect(text(wrapper)).toContain('Saved "Spring Budget Planning minutes.docx" to your Google Drive.')
    expect(text(wrapper)).not.toContain('Ready to save')
  })

  it('keeps the listed saves when only the connection could not be checked, and offers to check again', async () => {
    vi.mocked(listActions).mockResolvedValue([action({ state: 'OUTCOME_UNKNOWN', sent: true })])
    vi.mocked(listConnections).mockRejectedValue(new TypeError('Failed to fetch'))
    const wrapper = await mountPanel()

    expect(text(wrapper)).toContain('Brownie could not be reached.')
    expect(buttonNamed(wrapper, `Ask Google what happened to ${FILE}`).exists()).toBe(true)
    expect(wrapper.find('form').exists()).toBe(false)

    vi.mocked(listConnections).mockResolvedValue([saving()])
    await buttonNamed(wrapper, 'Check again for saving to Google Drive').trigger('click')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
    expect(window.document.activeElement?.id).toBe('drive-save-heading')
  })

  it('shows earlier saves, and says why nothing more, when what this Brownie offers could not be read', async () => {
    vi.mocked(getCapabilities).mockRejectedValue(new TypeError('Failed to fetch'))
    vi.mocked(listActions).mockResolvedValue([action({ state: 'OUTCOME_UNKNOWN', sent: true })])
    const wrapper = await mountPanel()

    expect(text(wrapper)).toContain('Brownie could not check whether saving to Google Drive is offered here')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(buttonNamed(wrapper, `Ask Google what happened to ${FILE}`).exists()).toBe(true)
  })

  it('never says nothing was saved when the save is gone from Brownie, nor when the session ended says anything else', async () => {
    vi.mocked(listActions).mockResolvedValue([action()])
    vi.mocked(approveAction).mockRejectedValueOnce(new ApiRequestError(404, problem(404, 'NOT_FOUND')))
    vi.mocked(getAction).mockRejectedValueOnce(new ApiRequestError(404, problem(404, 'NOT_FOUND')))
    const wrapper = await mountPanel()

    await buttonNamed(wrapper, `Save to Google Drive ${FILE}`).trigger('click')
    await flushPromises()
    expect(text(wrapper)).toContain('This save is no longer in Brownie, so what became of it cannot be shown.')
    expect(text(wrapper)).not.toContain('Nothing was')

    // Not found, and still there when read again: something it needs is gone, and the save says where it stands.
    vi.mocked(approveAction).mockRejectedValueOnce(new ApiRequestError(404, problem(404, 'NOT_FOUND')))
    vi.mocked(getAction).mockResolvedValueOnce(action())
    await buttonNamed(wrapper, `Save to Google Drive ${FILE}`).trigger('click')
    await flushPromises()
    expect(text(wrapper)).toContain('Something this save needs is no longer in Brownie. Where it stands now is shown below.')
    expect(text(wrapper)).not.toContain('Nothing was sent')

    vi.mocked(approveAction).mockRejectedValueOnce(new ApiRequestError(401, undefined))
    vi.mocked(getAction).mockResolvedValue(action())
    await buttonNamed(wrapper, `Save to Google Drive ${FILE}`).trigger('click')
    await flushPromises()
    expect(text(wrapper)).toContain('Nothing was sent this time. Your session has ended.')
  })

  it('offers no approval that could only be refused, once saving is switched off', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities([]))
    vi.mocked(listActions).mockResolvedValue([action(), action({ id: 30, state: 'APPROVED', approvedAt: '2026-09-28T10:05:00Z' })])
    const wrapper = await mountPanel()

    const names = wrapper.findAll('button').map((button) => button.text())
    expect(names.some((name) => name.startsWith('Save to Google Drive') || name.startsWith('Try saving again'))).toBe(false)
    expect(names.filter((name) => name.startsWith('Cancel'))).toHaveLength(2)
  })

  it('says when approving would save a second copy of a file already saved', async () => {
    vi.mocked(listActions).mockResolvedValue([
      action({ id: 32 }),
      action({ id: 31, state: 'SUCCEEDED', sent: true, verification: 'MATCHED', finishedAt: '2026-09-28T10:01:00Z' }),
    ])
    const wrapper = await mountPanel()

    const waiting = wrapper.find('#drive-save-32').element.parentElement!
    expect(waiting.textContent?.replace(/\s+/g, ' ')).toContain('You already saved this exact file to Google Drive at')
    expect(waiting.textContent).toContain('Approving this one saves another copy.')
  })

  it('says a lost answer to preparing may still have prepared the save, and lists again', async () => {
    vi.mocked(proposeDriveSave).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const wrapper = await mountPanel()
    const listed = vi.mocked(listActions).mock.calls.length
    vi.mocked(listActions).mockResolvedValue([action()])

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(text(wrapper)).toContain("Brownie's answer did not arrive, so this save may have been prepared all the same")
    expect(text(wrapper)).not.toContain('Nothing was prepared')
    expect(vi.mocked(listActions).mock.calls.length).toBe(listed + 1)
    expect(text(wrapper)).toContain('Ready to save "Spring Budget Planning minutes.docx"')

    vi.mocked(proposeDriveSave).mockRejectedValueOnce(new ApiRequestError(409, problem(409, 'ACTION_NOT_PROPOSABLE', 'EXPORT_STALE')))
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(text(wrapper)).toContain('Nothing was prepared. The document changed after it was last exported.')
  })

  it('warns of a copy Google made that did not read back as approved, and not of one deleted since or never sent', async () => {
    vi.mocked(listActions).mockResolvedValue([
      action({ id: 32 }),
      action({ id: 31, state: 'FAILED', failure: 'READBACK_MISMATCH', sent: true }),
    ])
    const mismatched = await mountPanel()
    expect(mismatched.find('#drive-save-32').element.parentElement!.textContent).toContain('An earlier try may already have saved this exact file.')

    vi.mocked(listActions).mockResolvedValue([
      action({ id: 32 }),
      action({ id: 31, state: 'SUCCEEDED', sent: true, verification: 'REMOVED_AFTERWARDS', finishedAt: '2026-09-28T10:01:00Z' }),
      action({ id: 30, state: 'OUTCOME_UNKNOWN', sent: false, outcomeAcknowledged: true }),
    ])
    const gone = await mountPanel()
    const waiting = gone.find('#drive-save-32').element.parentElement!.textContent ?? ''
    expect(waiting).not.toContain('already saved')
    expect(waiting).not.toContain('may already have saved')
    expect(gone.find('#drive-save-31').text()).toContain('and has since been deleted there')
    expect(gone.find('#drive-save-30').text()).toContain('Nothing was sent for')
  })

  it('says a stopped try that never sent anything sent nothing, and offers to close it', async () => {
    vi.mocked(listActions).mockResolvedValue([action({ state: 'EXECUTING', sent: false, attemptStopped: true })])
    const wrapper = await mountPanel()
    expect(text(wrapper)).toContain('That try stopped before anything was sent to Google.')
    expect(text(wrapper)).not.toContain('before Brownie heard how it ended')
    expect(buttonNamed(wrapper, `Close this try for ${FILE}`).exists()).toBe(true)
  })

  it('says when the list could not be read again after a lost answer, and reports an error Brownie answered with', async () => {
    vi.mocked(proposeDriveSave).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const wrapper = await mountPanel()
    vi.mocked(listActions).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(text(wrapper)).toContain("Brownie's answer did not arrive, so this save may have been prepared all the same.")
    expect(text(wrapper)).toContain('The list could not be read again: check again before preparing it a second time.')
    expect(text(wrapper)).not.toContain('Anything prepared is on the list below')

    vi.mocked(listActions).mockResolvedValue([])
    await buttonNamed(wrapper, 'Check again for saving to Google Drive').trigger('click')
    await flushPromises()
    vi.mocked(proposeDriveSave).mockRejectedValueOnce(new ApiRequestError(500, problem(500, 'INTERNAL_ERROR')))
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(text(wrapper)).toContain('Brownie answered with an error, so this save may or may not have been prepared.')
    expect(text(wrapper)).toContain('Anything prepared is on the list below.')
  })

  it('puts the focus on the button that connects again when preparing found the connection gone', async () => {
    vi.mocked(proposeDriveSave).mockRejectedValue(new ApiRequestError(404, problem(404, 'CONNECTION_NOT_FOUND')))
    const wrapper = await mountPanel()
    vi.mocked(listConnections).mockResolvedValue([])

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(text(wrapper)).toContain('Nothing was prepared. Google Drive for saving is not connected. Connect it first.')
    expect(window.document.activeElement?.id).toBe('drive-save-connect')
  })
})

describe('adding this version to a Google Doc a save made', () => {
  function converted(): ActionResponse {
    return action({
      id: 31,
      type: 'DRIVE_SAVE_AS_GOOGLE_DOC',
      state: 'SUCCEEDED',
      sent: true,
      verification: 'CONVERSION_CHECKED',
      conversionCheck: { total: 2, found: 2 },
      externalLink: 'https://docs.google.com/document/d/abc/edit',
    })
  }

  function appendPayload(overrides: Record<string, unknown> = {}) {
    return {
      schema: 'brownie.action/1',
      type: 'GOOGLE_DOC_APPEND',
      nonce: 'n',
      proposedBy: 3,
      workspace: 7,
      document: { id: 42, revision: 9, title: 'Spring Budget Planning minutes' },
      account: { connection: 5, email: 'me@example.org' },
      target: {
        kind: 'SAVED_GOOGLE_DOC',
        savedBy: 31,
        title: 'Spring Budget Planning minutes',
        revision: 'rev-1',
        textSha256: 'e'.repeat(64),
        textLength: 40,
        shared: true,
        place: 'END_OF_FIRST_TAB',
      },
      content: { exportReceipt: 12, text: '\nSpring Budget Planning, revised\nMarch 6, 2026' },
      effect: { appends: 'TEXT', onlyIfUnchanged: true },
      ...overrides,
    }
  }

  function appended(overrides: Partial<ActionResponse> = {}): ActionResponse {
    return action({ id: 50, type: 'GOOGLE_DOC_APPEND', payload: appendPayload(), ...overrides })
  }

  it('offers it on a Doc a save made, shows the exact text and where it goes, and adds it only on approval', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC', 'GOOGLE_DOC_APPEND']))
    vi.mocked(listActions).mockResolvedValue([converted()])
    vi.mocked(proposeDocAppend).mockResolvedValue(appended())
    const wrapper = await mountPanel()

    await buttonNamed(wrapper, `Add this version's text to this Google Doc Spring Budget Planning minutes, prepared at ${AT}`).trigger('click')
    await flushPromises()

    expect(proposeDocAppend).toHaveBeenCalledWith(7, 42, 31)
    expect(approveAction).not.toHaveBeenCalled()
    const card = wrapper.find('#drive-save-50').element.parentElement!
    expect(card.querySelector('pre')?.textContent).toBe('Spring Budget Planning, revised\nMarch 6, 2026')
    expect(card.textContent?.replace(/\s+/g, ' ')).toContain('This Google Doc is shared: everyone it is shared with will see the added text.')
    expect(card.textContent?.replace(/\s+/g, ' ')).toContain('Google adds it only if nobody has changed the Doc since this was prepared.')
    expect(window.document.activeElement?.id).toBe('drive-save-50')

    vi.mocked(approveAction).mockResolvedValue(
      appended({ state: 'SUCCEEDED', sent: true, verification: 'MATCHED', externalLink: 'https://docs.google.com/document/d/abc/edit' }),
    )
    await buttonNamed(wrapper, `Add to the Google Doc the text for Spring Budget Planning minutes, prepared at ${AT}`).trigger('click')
    await flushPromises()
    expect(approveAction).toHaveBeenCalledWith(7, 50, HASH)
    expect(wrapper.find('#drive-save-50').text()).toContain("the text is at the end of its first tab, and the rest of that tab's text is as it was")
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('is not offered without an export of this version, nor where adding is not offered', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC', 'GOOGLE_DOC_APPEND']))
    vi.mocked(listActions).mockResolvedValue([converted()])
    const noExport = await mountPanel({ receipt: null })
    expect(noExport.findAll('button').some((button) => button.text().startsWith("Add this version's text"))).toBe(false)

    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC']))
    const notOffered = await mountPanel()
    expect(notOffered.findAll('button').some((button) => button.text().startsWith("Add this version's text"))).toBe(false)
  })

  it('never offers for approval an addition it cannot state in full', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC', 'GOOGLE_DOC_APPEND']))
    vi.mocked(listActions).mockResolvedValue([
      appended({ payload: appendPayload({ effect: { appends: 'TEXT', onlyIfUnchanged: false } }) }),
    ])
    const wrapper = await mountPanel()
    expect(text(wrapper)).toContain('Brownie cannot show everything this addition would do')
    expect(wrapper.findAll('button').some((button) => button.text().startsWith('Add to the Google Doc'))).toBe(false)
  })
  it('names the Doc by the save that made it, counts characters as a person does, and passes axe while waiting', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC', 'GOOGLE_DOC_APPEND']))
    const withEmoji = appendPayload({
      content: { exportReceipt: 12, text: '\nBudget \u{1F4B0} approved' },
    })
    vi.mocked(listActions).mockResolvedValue([
      appended({ payload: withEmoji }),
      { ...converted(), finishedAt: '2026-09-28T10:01:00Z' },
    ])
    const wrapper = await mountPanel()

    const card = wrapper.find('#drive-save-50').element.parentElement!
    const said = card.textContent?.replace(/\s+/g, ' ') ?? ''
    expect(said).toContain('This text, 17 characters, as new paragraphs.')
    expect(said).toContain("They take the style of the Doc's last paragraph")
    expect(said).toContain('At the end of the first tab of "Spring Budget Planning minutes", the Google Doc Brownie saved at')
    expect(card.querySelector('pre')?.getAttribute('role')).toBe('region')
    expect(card.querySelector('pre')?.getAttribute('aria-label')).toBe(
      `The text to add to "Spring Budget Planning minutes", prepared at ${AT}`,
    )
    expect(said).toContain("Brownie sends it, then reads the Doc back to check the text is at the end of its first tab and the rest of that tab's text is as it was.")
    expect(said).toContain("Ready to add the document's text, as exported when this was prepared, to the end of")
    expect(card.querySelector('a')?.getAttribute('href')).toBe('https://docs.google.com/document/d/abc/edit')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('speaks of the addition, not a save, when its approval or preparation goes wrong', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC', 'GOOGLE_DOC_APPEND']))
    vi.mocked(listActions).mockResolvedValue([appended(), converted()])
    vi.mocked(approveAction).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    vi.mocked(getAction).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const wrapper = await mountPanel()

    await buttonNamed(wrapper, `Add to the Google Doc the text for Spring Budget Planning minutes, prepared at ${AT}`).trigger('click')
    await flushPromises()
    expect(text(wrapper)).toContain('Whether the text was added is not known here yet')
    expect(text(wrapper)).toContain('do not prepare the same addition again until then')
    expect(text(wrapper)).toContain('Brownie could not be asked where this addition stands.')
    expect(text(wrapper)).not.toContain('the file was saved')

    vi.mocked(proposeDocAppend).mockRejectedValueOnce(
      new ApiRequestError(409, problem(409, 'ACTION_NOT_PROPOSABLE', 'HIDDEN_CHARACTERS')),
    )
    await buttonNamed(wrapper, `Add this version's text to this Google Doc Spring Budget Planning minutes, prepared at ${AT}`).trigger('click')
    await flushPromises()
    expect(text(wrapper)).toContain("Nothing was prepared. This version's text holds characters that reorder it or cannot be seen")
    expect(text(wrapper)).not.toContain("document's title")
  })

  it('warns before this version\'s text goes into the same Doc a second time', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC', 'GOOGLE_DOC_APPEND']))
    vi.mocked(listActions).mockResolvedValue([
      appended({ id: 51 }),
      appended({ state: 'SUCCEEDED', sent: true, verification: 'MATCHED', finishedAt: '2026-09-28T10:03:00Z' }),
      converted(),
    ])
    const wrapper = await mountPanel()

    const save = wrapper.find('#drive-save-31').element.parentElement!.textContent?.replace(/\s+/g, ' ') ?? ''
    expect(save).toContain("This version's text was already added to this Google Doc at")
    expect(save).toContain('Adding it again puts it there a second time.')
    const waiting = wrapper.find('#drive-save-51').element.parentElement!.textContent?.replace(/\s+/g, ' ') ?? ''
    expect(waiting).toContain('This text was already added to this Google Doc at')

    const other = await mountPanel({ receipt: receipt({ revisionId: 10 }) })
    const newer = other.find('#drive-save-31').element.parentElement!.textContent ?? ''
    expect(newer).not.toContain('already added')
  })

  it('says why an addition waiting for approval can only be cancelled once adding is switched off', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC']))
    vi.mocked(listActions).mockResolvedValue([appended(), converted()])
    const wrapper = await mountPanel()

    expect(text(wrapper)).toContain('This Brownie no longer adds text to Google Docs, so this can only be cancelled.')
    expect(buttonNamed(wrapper, `Cancel adding the text for Spring Budget Planning minutes, prepared at ${AT}`).exists()).toBe(true)
  })

  it('lets the person say they looked when an attempt stopped and can no longer be asked about', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC', 'GOOGLE_DOC_APPEND']))
    vi.mocked(listActions).mockResolvedValue([appended({ state: 'EXECUTING', sent: true, attemptStopped: true }), converted()])
    vi.mocked(acknowledgeAction).mockResolvedValue(appended({ state: 'OUTCOME_UNKNOWN', sent: true, outcomeAcknowledged: true }))
    const wrapper = await mountPanel()

    expect(text(wrapper)).toContain('That try stopped before Brownie heard how it ended.')
    await buttonNamed(wrapper, `I looked at the Google Doc for the text for Spring Budget Planning minutes, prepared at ${AT}`).trigger('click')
    await flushPromises()
    expect(acknowledgeAction).toHaveBeenCalledWith(7, 50)
    expect(text(wrapper)).toContain('You said you looked at "Spring Budget Planning minutes".')

    vi.mocked(listActions).mockResolvedValue([appended({ state: 'EXECUTING', sent: true, attemptStopped: false }), converted()])
    const live = await mountPanel()
    expect(live.findAll('button').some((button) => button.text().startsWith('I looked'))).toBe(false)
  })
})

describe('connecting for saving', () => {
  it('asks for the saving connection, back to this document, and waits for unsaved changes', async () => {
    vi.mocked(listConnections).mockResolvedValue([])
    const waiting = await mountPanel({ unsavedWork: true })
    expect(text(waiting)).toContain('Saving to Google Drive is not connected.')
    expect(text(waiting)).toContain('only for saving a file you approve and for adding text you approve to a Google Doc it saved')
    await buttonNamed(waiting, 'Connect Google Drive for saving').trigger('click')
    await flushPromises()
    expect(startGoogleConsent).not.toHaveBeenCalled()
    expect(text(waiting)).toContain('This document has changes that are not saved yet.')

    vi.mocked(startGoogleConsent).mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?x=1' })
    const wrapper = await mountPanel()
    await buttonNamed(wrapper, 'Connect Google Drive for saving').trigger('click')
    await flushPromises()
    expect(startGoogleConsent).toHaveBeenCalledWith(7, 'DRIVE_SAVING', '/documents/42')
    expect(navigateTo).toHaveBeenCalledWith('https://accounts.google.com/o/oauth2/v2/auth?x=1')
  })
})

describe('where saving is not offered', () => {
  it('shows nothing, and asks nothing, on a server that does not say which changes it makes', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities())
    const wrapper = await mountPanel()
    expect(wrapper.find('section').exists()).toBe(false)
    expect(listActions).not.toHaveBeenCalled()
    expect(listConnections).not.toHaveBeenCalled()
  })

  it('still shows earlier saves, with the way to check one, once saving is switched off', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities([]))
    vi.mocked(listActions).mockResolvedValue([action({ state: 'OUTCOME_UNKNOWN', sent: true })])
    const wrapper = await mountPanel()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(listConnections).not.toHaveBeenCalled()
    expect(buttonNamed(wrapper, `Ask Google what happened to ${FILE}`).exists()).toBe(true)
  })

  it('shows nothing, and says nothing, when earlier saves cannot be listed where saving is off', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities([]))
    vi.mocked(listActions).mockRejectedValue(new ApiRequestError(403, problem(403, 'FORBIDDEN')))
    const wrapper = await mountPanel()
    expect(wrapper.find('section').exists()).toBe(false)
  })
})
