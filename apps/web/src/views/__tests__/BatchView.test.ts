import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import BatchView from '@/views/BatchView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    getBatch: vi.fn(),
    getTemplateVersion: vi.fn(),
    getCapabilities: vi.fn(),
    fillBatchStep: vi.fn(),
    acceptBatchStep: vi.fn(),
    checkBatchStep: vi.fn(),
    composeBatchStep: vi.fn(),
    createBatchExport: vi.fn(),
    advanceBatchExport: vi.fn(),
    getBatchExport: vi.fn(),
    deleteBatch: vi.fn(),
    getWorkspaceUsage: vi.fn(),
  }
})

import {
  acceptBatchStep,
  advanceBatchExport,
  ApiRequestError,
  checkBatchStep,
  createBatchExport,
  deleteBatch,
  fillBatchStep,
  getBatch,
  getBatchExport,
  getCapabilities,
  getTemplateVersion,
  type BatchResponse,
  type BatchRowResponse,
} from '@/api/client'
import { resetCapabilitiesCache } from '@/capabilities'

const stub = { template: '<div />' }

function row(overrides: Partial<BatchRowResponse>): BatchRowResponse {
  return {
    rowNumber: 2,
    state: 'CREATED',
    documentId: 101,
    initialRevisionId: 50,
    documentTitle: 'Certificate, Amara',
    documentPresent: true,
    currentRevisionId: 50,
    accepted: false,
    aiComposed: false,
    suggestionWaiting: false,
    problems: [],
    composeState: 'NOT_NEEDED',
    check: { state: 'NOT_CHECKED', blocking: 0, warnings: 0, notes: 0, firstBlocking: null },
    preCheckBlocking: 0,
    openQuestions: 0,
    lastExport: null,
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function batch(overrides: Partial<BatchResponse>): BatchResponse {
  return {
    id: 99,
    title: 'Certificates, 3 documents',
    templateId: 11,
    templateVersionId: 22,
    sourceArtifactId: 44,
    tableFormat: 'CSV',
    sheetName: null,
    hasHeaderRow: true,
    dateOrder: 'MDY',
    mapping: [],
    titleColumn: null,
    rowCount: 3,
    createdAt: '2026-01-01T00:00:00Z',
    rows: [
      row({ rowNumber: 2, documentId: 101 }),
      row({ rowNumber: 3, documentId: 102 }),
      row({ rowNumber: 4, documentId: 103 }),
    ],
    ...overrides,
  }
}

function signIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
}

function problem(status: number, detail: string, code = 'X') {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [] }
}

async function mountView(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'home', component: stub },
      { path: '/batches/:batchId', name: 'batch', component: BatchView },
      { path: '/documents/:documentId', name: 'workspace', component: stub },
    ],
  })
  router.push('/batches/99')
  await router.isReady()
  const wrapper = mount(BatchView, { attachTo: document.body, global: { plugins: [router] } })
  return { wrapper, router }
}

async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
  await new Promise((resolve) => setTimeout(resolve, 0))
  await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('BatchView', () => {
  let wrapper: VueWrapper | null = null

  beforeEach(() => {
    setActivePinia(createPinia())
    signIn()
    document.body.innerHTML = ''
    resetCapabilitiesCache()
    vi.mocked(getBatch).mockResolvedValue(batch({}))
    vi.mocked(getTemplateVersion).mockResolvedValue({
      id: 22,
      kind: 'DOCX',
      fields: [{ fieldId: 'member.name', label: 'Member name' }],
    } as never)
    vi.mocked(getCapabilities).mockResolvedValue({ batchComposeOffered: false } as never)
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.restoreAllMocks()
  })

  it('opens with the batch as its heading, the counts and every row', async () => {
    ;({ wrapper } = await mountView())
    await flush()

    expect(wrapper.get('h1').text()).toBe('Certificates, 3 documents')
    expect(wrapper.text()).toContain('3 of 3 documents made')
    const rows = wrapper.findAll('.batch__table tbody tr')
    expect(rows).toHaveLength(3)
    // The link is the document's own title, not a repeated "Open".
    expect(rows[0]!.find('a').text()).toBe('Certificate, Amara')
    expect(rows[0]!.text()).toContain('Filled')
  })

  it('names a blocking finding by the field\'s own label', async () => {
    vi.mocked(getBatch).mockResolvedValue(
      batch({
        rows: [
          row({
            rowNumber: 2,
            documentId: 101,
            check: {
              state: 'CHECKED',
              blocking: 1,
              warnings: 0,
              notes: 0,
              firstBlocking: { code: 'MISSING_REQUIRED_FIELD', fieldId: 'member.name' },
            },
          }),
        ],
      }),
    )
    ;({ wrapper } = await mountView())
    await flush()

    expect(wrapper.text()).toContain('Blocks export: Member name is required and empty')
    expect(wrapper.text()).not.toContain('MISSING_REQUIRED_FIELD')
    expect(wrapper.text()).not.toContain('member.name')
  })

  it('continues making documents with fill steps and asks the batch again after each', async () => {
    vi.mocked(getBatch).mockResolvedValue(batch({ rows: [row({ rowNumber: 2, state: 'PENDING', documentId: null }), row({ rowNumber: 3 }), row({ rowNumber: 4 })] }))
    ;({ wrapper } = await mountView())
    await flush()

    const button = wrapper.findAll('button').find((b) => b.text().includes('Continue making documents'))
    expect(button?.text()).toContain('(1 left)')

    vi.mocked(fillBatchStep).mockResolvedValue({ processed: 1, remaining: 0, rows: [] } as never)
    const callsBefore = vi.mocked(getBatch).mock.calls.length
    await button!.trigger('click')
    await flush()

    expect(fillBatchStep).toHaveBeenCalledWith(7, 99)
    expect(vi.mocked(getBatch).mock.calls.length).toBeGreaterThan(callsBefore)
  })

  it('accepts rows that are ready and lists the ones left out in words', async () => {
    ;({ wrapper } = await mountView())
    await flush()

    vi.mocked(acceptBatchStep).mockResolvedValue({
      processed: 1,
      remaining: 0,
      rows: [
        { rowNumber: 2, state: 'CREATED', documentId: 101, result: 'ACCEPTED', code: null },
        { rowNumber: 3, state: 'CREATED', documentId: 102, result: 'SKIPPED', code: 'SUGGESTION_WAITING' },
      ],
    } as never)
    await wrapper.findAll('button').find((b) => b.text().includes('Accept rows that are ready'))!.trigger('click')
    await flush()

    expect(acceptBatchStep).toHaveBeenCalledWith(7, 99)
    expect(wrapper.text()).toContain('1 row could not be accepted yet')
    expect(wrapper.text()).toContain('suggestion is waiting')
  })

  it('checks all documents, looping until nothing is left', async () => {
    ;({ wrapper } = await mountView())
    await flush()

    vi.mocked(checkBatchStep)
      .mockResolvedValueOnce({ processed: 2, remaining: 1, rows: [] } as never)
      .mockResolvedValue({ processed: 1, remaining: 0, rows: [] } as never)
    await wrapper.findAll('button').find((b) => b.text() === 'Check all')!.trigger('click')
    await flush()

    expect(checkBatchStep).toHaveBeenCalledTimes(2)
  })

  it('keeps working after a step error and resumes on the next press', async () => {
    ;({ wrapper } = await mountView())
    await flush()

    vi.mocked(checkBatchStep)
      .mockRejectedValueOnce(new ApiRequestError(500, problem(500, 'boom', 'X')))
      .mockResolvedValue({ processed: 3, remaining: 0, rows: [] } as never)

    const check = wrapper.findAll('button').find((b) => b.text() === 'Check all')!
    await check.trigger('click')
    await flush()
    expect(wrapper.text()).toContain('stopped checking the documents')

    await check.trigger('click')
    await flush()
    expect(checkBatchStep).toHaveBeenCalledTimes(2)
  })

  it('waits out BATCH_BUSY and asks again, saying so', async () => {
    ;({ wrapper } = await mountView())
    await flush()

    vi.mocked(checkBatchStep)
      .mockRejectedValueOnce(new ApiRequestError(409, problem(409, 'busy', 'BATCH_BUSY')))
      .mockResolvedValue({ processed: 3, remaining: 0, rows: [] } as never)

    vi.useFakeTimers()
    try {
      await wrapper.findAll('button').find((b) => b.text() === 'Check all')!.trigger('click')
      await vi.advanceTimersByTimeAsync(0)
      expect(wrapper.text()).toContain('it will try again in a moment')
      await vi.advanceTimersByTimeAsync(2100)
      expect(checkBatchStep).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('waits a step\'s own retryAfterMs before asking again', async () => {
    ;({ wrapper } = await mountView())
    await flush()

    vi.mocked(checkBatchStep)
      .mockResolvedValueOnce({ processed: 1, remaining: 2, retryAfterMs: 4000, rows: [] } as never)
      .mockResolvedValue({ processed: 2, remaining: 0, rows: [] } as never)

    vi.useFakeTimers()
    try {
      await wrapper.findAll('button').find((b) => b.text() === 'Check all')!.trigger('click')
      await vi.advanceTimersByTimeAsync(0)
      // Two seconds (the busy wait) is not enough: the answer asked for four.
      await vi.advanceTimersByTimeAsync(2100)
      expect(checkBatchStep).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(2000)
      expect(checkBatchStep).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops a step loop that made no progress and named no wait, instead of spinning', async () => {
    ;({ wrapper } = await mountView())
    await flush()

    vi.mocked(checkBatchStep).mockResolvedValue({ processed: 0, remaining: 2, rows: [] } as never)
    await wrapper.findAll('button').find((b) => b.text() === 'Check all')!.trigger('click')
    await flush()

    expect(checkBatchStep).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('could not make progress on the remaining rows')
  })

  it('exports through the dialog and offers the ZIP for download with the left-out rows listed', async () => {
    vi.mocked(getBatch).mockResolvedValue(
      batch({
        rows: [
          row({ rowNumber: 2, documentId: 101, accepted: true }),
          row({ rowNumber: 3, documentId: 102, accepted: true }),
          row({ rowNumber: 4, documentId: 103, suggestionWaiting: true }),
        ],
      }),
    )
    ;({ wrapper } = await mountView())
    await flush()

    await wrapper.findAll('button').find((b) => b.text() === 'Export…')!.trigger('click')
    await flush()

    const dialog = document.body.querySelector('.spot-dialog, [role="dialog"], dialog')
    expect(dialog?.textContent).toContain('Brownie checks each document first')
    expect(dialog?.textContent).toContain('Approve and export 2 documents')

    vi.mocked(createBatchExport).mockResolvedValue({
      id: 55,
      format: 'DOCX',
      state: 'RUNNING',
      createdAt: '2026-01-01T00:00:00Z',
      completedAt: null,
      items: [
        { rowNumber: 2, documentId: 101, outcome: 'PENDING', detail: null },
        { rowNumber: 3, documentId: 102, outcome: 'PENDING', detail: null },
        { rowNumber: 4, documentId: 103, outcome: 'SUGGESTION_WAITING', detail: null },
      ],
    } as never)
    vi.mocked(advanceBatchExport).mockResolvedValue({
      processed: 2,
      remaining: 0,
      state: 'COMPLETE',
      items: [
        { rowNumber: 2, documentId: 101, outcome: 'EXPORTED', detail: null },
        { rowNumber: 3, documentId: 102, outcome: 'EXPORTED', detail: null },
        { rowNumber: 4, documentId: 103, outcome: 'SUGGESTION_WAITING', detail: null },
      ],
    } as never)
    vi.mocked(getBatchExport).mockResolvedValue({
      id: 55,
      format: 'DOCX',
      state: 'COMPLETE',
      items: [
        { rowNumber: 2, documentId: 101, outcome: 'EXPORTED', detail: null },
        { rowNumber: 3, documentId: 102, outcome: 'EXPORTED', detail: null },
        { rowNumber: 4, documentId: 103, outcome: 'SUGGESTION_WAITING', detail: null },
      ],
    } as never)

    const approve = Array.from(document.body.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Approve and export'),
    )!
    approve.click()
    await flush()
    await flush()

    expect(createBatchExport).toHaveBeenCalledWith(7, 99, expect.any(String), 'DOCX')
    expect(advanceBatchExport).toHaveBeenCalledWith(7, 99, 55)
    const download = wrapper.find('a[href$="/download"]')
    expect(download.exists()).toBe(true)
    expect(download.attributes('href')).toBe('/api/v1/workspaces/7/batches/99/exports/55/download')
    expect(download.text()).toContain('2 documents')
    expect(wrapper.text()).toContain('Row 4')
    expect(wrapper.text()).toContain('suggestion is waiting')
  })

  it('deletes the batch after one confirmation and goes home', async () => {
    const pair = await mountView()
    wrapper = pair.wrapper
    await flush()

    await wrapper.findAll('button').find((b) => b.text() === 'Delete batch')!.trigger('click')
    await flush()
    expect(wrapper.text()).toContain('the spreadsheet you uploaded for it')
    expect(wrapper.text()).toContain('documents stay in your documents')

    vi.mocked(deleteBatch).mockResolvedValue(undefined as never)
    const confirm = wrapper.findAll('button').find((b) => b.text() === 'Delete batch' && b.classes().includes('button--danger'))!
    await confirm.trigger('click')
    await flush()

    expect(deleteBatch).toHaveBeenCalledWith(7, 99)
    expect(pair.router.currentRoute.value.name).toBe('home')
    expect(pair.router.currentRoute.value.query.batchDeleted).toBe('Certificates, 3 documents')
  })

  it('has no automatically-detectable accessibility violations', async () => {
    ;({ wrapper } = await mountView())
    await flush()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  }, 15000)
})
