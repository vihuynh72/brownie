import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import FillManyView from '@/views/FillManyView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    allocateUpload: vi.fn(),
    uploadArtifactContent: vi.fn(),
    completeUpload: vi.fn(),
    readArtifactTable: vi.fn(),
    getTemplateVersion: vi.fn(),
    listTemplates: vi.fn(),
    createBatch: vi.fn(),
    fillBatchStep: vi.fn(),
    getCapabilities: vi.fn(),
  }
})

import {
  allocateUpload,
  ApiRequestError,
  completeUpload,
  createBatch,
  fillBatchStep,
  getCapabilities,
  getTemplateVersion,
  listTemplates,
  readArtifactTable,
  uploadArtifactContent,
  type TableReadingResponse,
} from '@/api/client'
import { resetCapabilitiesCache } from '@/capabilities'

const stub = { template: '<div />' }

const TEMPLATE = {
  id: 11,
  displayName: 'Certificate of participation',
  status: 'ACTIVE' as const,
  currentActiveVersionId: 22,
  createdAt: '2026-01-01T00:00:00Z',
}

const VERSION = {
  id: 22,
  templateId: 11,
  versionNumber: 1,
  sourceArtifactId: 5,
  kind: 'DOCX' as const,
  extractionVersionId: 3,
  status: 'ACTIVATED' as const,
  fields: [
    {
      fieldId: 'member.name',
      type: 'TEXT' as const,
      cardinality: 'SCALAR' as const,
      requiredness: 'REQUIRED' as const,
      bindingKind: 'CONTENT_CONTROL_TAG' as const,
      label: 'Member name',
    },
    {
      fieldId: 'award.date',
      type: 'DATE' as const,
      cardinality: 'SCALAR' as const,
      requiredness: 'OPTIONAL' as const,
      bindingKind: 'CONTENT_CONTROL_TAG' as const,
      label: 'Date awarded',
    },
  ],
  createdAt: '2026-01-01T00:00:00Z',
}

function tableReading(overrides: Partial<TableReadingResponse> = {}): TableReadingResponse {
  return {
    artifactId: 44,
    format: 'CSV',
    readerVersion: 'csv-1',
    sheetNames: [],
    sheet: null,
    hasHeaderRow: true,
    ambiguousDates: false,
    columns: [
      { index: 0, letter: 'A', name: 'Member name', inferredType: 'TEXT', blankCount: 0 },
      { index: 1, letter: 'B', name: 'Award', inferredType: 'TEXT', blankCount: 0 },
      { index: 2, letter: 'C', name: 'Date awarded', inferredType: 'DATE', blankCount: 0 },
      { index: 3, letter: 'D', name: 'Citation notes', inferredType: 'TEXT', blankCount: 0 },
    ],
    rowCount: 3,
    blankRowCount: 0,
    notices: [],
    previewRows: [
      {
        rowNumber: 2,
        blank: false,
        cells: [
          { display: 'Amara', date: null, problem: null },
          { display: 'Star', date: null, problem: null },
          { display: '2025-01-01', date: '2025-01-01', problem: null },
          { display: 'Well done', date: null, problem: null },
        ],
      },
      {
        rowNumber: 3,
        blank: false,
        cells: [
          { display: 'Ben', date: null, problem: null },
          { display: 'Pin', date: null, problem: null },
          { display: 'oops', date: null, problem: null },
          { display: '', date: null, problem: null },
        ],
      },
    ],
    maxRows: 500,
    ...overrides,
  }
}

function signIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
}

function problem(status: number, detail: string, code = 'X', extras: Record<string, unknown> = {}) {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [], ...extras }
}

async function mountView(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'home', component: stub },
      { path: '/templates/:templateId/fill-many', name: 'fill-many', component: FillManyView },
      { path: '/batches/:batchId', name: 'batch', component: stub },
    ],
  })
  router.push('/templates/11/fill-many')
  await router.isReady()
  const wrapper = mount(FillManyView, { attachTo: document.body, global: { plugins: [router] } })
  return { wrapper, router }
}

async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
  await new Promise((resolve) => setTimeout(resolve, 0))
}

/** Walks the page through a successful upload and table read, landing on the mapping step. */
async function uploadTable(wrapper: VueWrapper, reading = tableReading()): Promise<void> {
  vi.mocked(allocateUpload).mockResolvedValue({ id: 44, status: 'UPLOADING' })
  vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 44, status: 'QUARANTINED', detectedMediaType: 'PLAIN_TEXT' })
  vi.mocked(completeUpload).mockResolvedValue({ id: 44, status: 'READY', detectedMediaType: 'PLAIN_TEXT' })
  vi.mocked(readArtifactTable).mockResolvedValue(reading)
  const input = wrapper.get('input[type="file"]')
  const file = new File(['a,b\n1,2'], 'members.csv', { type: 'text/csv' })
  Object.defineProperty(input.element, 'files', { value: [file] })
  await input.trigger('change')
  await flush()
}

describe('FillManyView', () => {
  let wrapper: VueWrapper | null = null

  beforeEach(() => {
    setActivePinia(createPinia())
    signIn()
    document.body.innerHTML = ''
    resetCapabilitiesCache()
    vi.mocked(listTemplates).mockResolvedValue([TEMPLATE])
    vi.mocked(getTemplateVersion).mockResolvedValue(VERSION)
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 10 * 1024 * 1024,
      uploadMediaTypes: [],
      tableFileTypes: [
        { mediaType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', extension: '.xlsx' },
        { mediaType: 'text/plain', extension: '.csv' },
      ],
      batchMaxRows: 500,
      batchComposeOffered: false,
    } as never)
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.restoreAllMocks()
  })

  it('opens on the file step with the template named, and focuses the heading', async () => {
    ;({ wrapper } = await mountView())
    await flush()

    expect(wrapper.get('h1').text()).toContain('Certificate of participation')
    expect(document.activeElement).toBe(wrapper.get('h1').element)
    expect(wrapper.text()).toContain('Choose an Excel workbook (.xlsx) or a CSV file')
  })

  it('refuses a document before any table read, in the document words', async () => {
    ;({ wrapper } = await mountView())
    await flush()
    vi.mocked(allocateUpload).mockResolvedValue({ id: 44, status: 'UPLOADING' })
    vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 44, status: 'QUARANTINED', detectedMediaType: 'DOCX' })
    vi.mocked(completeUpload).mockResolvedValue({ id: 44, status: 'READY', detectedMediaType: 'DOCX' })

    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'form.docx')] })
    await input.trigger('change')
    await flush()

    expect(wrapper.text()).toContain('This is a document, not a spreadsheet. Choose a .xlsx or .csv file.')
    expect(readArtifactTable).not.toHaveBeenCalled()
    // A refused file is its own card with a way to choose again, and the "Checking" line went with it.
    expect(wrapper.get('[role="alert"]').text()).toContain('document, not a spreadsheet')
    expect(wrapper.findAll('button').some((b) => b.text() === 'Choose another file')).toBe(true)
    expect(wrapper.text()).not.toContain('Checking "form.docx"')
  })

  it('refuses a spreadsheet kind the upload will not take, in its own words', async () => {
    ;({ wrapper } = await mountView())
    await flush()
    vi.mocked(allocateUpload).mockResolvedValue({ id: 44, status: 'UPLOADING' })
    vi.mocked(uploadArtifactContent).mockRejectedValue(
      new ApiRequestError(415, problem(415, 'refused', 'UNSUPPORTED_MEDIA_TYPE', { reason: 'SPREADSHEET' })),
    )

    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'old.xls')] })
    await input.trigger('change')
    await flush()

    expect(wrapper.text()).toContain(
      "This kind of spreadsheet can't be read. Save it as an Excel workbook (.xlsx) or as CSV UTF-8",
    )
    expect(wrapper.findAll('button').some((b) => b.text() === 'Choose another file')).toBe(true)
  })

  it('words a TABLE_UNREADABLE answer with its row number', async () => {
    ;({ wrapper } = await mountView())
    await flush()
    vi.mocked(allocateUpload).mockResolvedValue({ id: 44, status: 'UPLOADING' })
    vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 44, status: 'QUARANTINED', detectedMediaType: 'PLAIN_TEXT' })
    vi.mocked(completeUpload).mockResolvedValue({ id: 44, status: 'READY', detectedMediaType: 'PLAIN_TEXT' })
    vi.mocked(readArtifactTable).mockRejectedValue(
      new ApiRequestError(422, problem(422, 'unreadable', 'TABLE_UNREADABLE', { reason: 'CELL_TOO_LONG', rowNumber: 47 })),
    )

    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'members.csv')] })
    await input.trigger('change')
    await flush()

    expect(wrapper.text()).toContain('row 47')
  })

  it('maps columns automatically, shows the first rows, and makes documents', async () => {
    const pair = await mountView()
    wrapper = pair.wrapper
    await flush()

    await uploadTable(wrapper)

    // Auto-match: "Member name" to column A, "Date awarded" to column C.
    const nameSelect = wrapper.get('#map-member\\.name')
    expect((nameSelect.element as HTMLSelectElement).value).toBe('0')
    const dateSelect = wrapper.get('#map-award\\.date')
    expect((dateSelect.element as HTMLSelectElement).value).toBe('2')

    // Preview: first rows as documents, the bad date marked.
    const previewText = wrapper.get('.fill-many__preview').text()
    expect(previewText).toContain('Amara')
    expect(previewText).toContain('Not a date')

    vi.mocked(createBatch).mockResolvedValue({
      id: 99,
      title: 'Certificate of participation, 3 documents',
      templateId: 11,
      templateVersionId: 22,
      artifactId: 44,
      format: 'CSV',
      readerVersion: 'csv-1',
      sheetName: null,
      hasHeaderRow: true,
      dateOrder: 'MDY',
      rowCount: 3,
      createdCount: 0,
      createdAt: '2026-01-01T00:00:00Z',
      completedAt: null,
      rows: [],
    } as never)
    vi.mocked(fillBatchStep).mockResolvedValue({ processed: 3, remaining: 0, rows: [] } as never)

    await wrapper.get('button.button--primary').trigger('click')
    await flush()

    expect(createBatch).toHaveBeenCalledWith(
      7,
      expect.any(String),
      expect.objectContaining({
        templateId: 11,
        templateVersionId: 22,
        artifactId: 44,
        mapping: expect.arrayContaining([
          { fieldId: 'member.name', column: 0, mode: 'VALUE' },
          { fieldId: 'award.date', column: 2, mode: 'VALUE' },
        ]),
      }),
    )
    expect(fillBatchStep).toHaveBeenCalledWith(7, 99)
    expect(pair.router.currentRoute.value.name).toBe('batch')
    expect(pair.router.currentRoute.value.params.batchId).toBe('99')
  })

  it('waits a fill step\'s own retryAfterMs, and stops a step that made no progress', async () => {
    const pair = await mountView()
    wrapper = pair.wrapper
    await flush()
    await uploadTable(wrapper)

    vi.mocked(createBatch).mockResolvedValue({ id: 99, rowCount: 3, rows: [] } as never)
    vi.mocked(fillBatchStep)
      .mockResolvedValueOnce({ processed: 1, remaining: 2, retryAfterMs: 4000, rows: [] } as never)
      .mockResolvedValue({ processed: 0, remaining: 2, rows: [] } as never)

    vi.useFakeTimers()
    try {
      await wrapper.get('button.button--primary').trigger('click')
      await vi.advanceTimersByTimeAsync(0)
      // The answer asked for four seconds, not the two-second busy wait.
      await vi.advanceTimersByTimeAsync(2100)
      expect(fillBatchStep).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(2000)
      // The second step did nothing and named no wait: the loop stops rather than spinning.
      await vi.advanceTimersByTimeAsync(0)
      expect(fillBatchStep).toHaveBeenCalledTimes(2)
      expect(wrapper.text()).toContain('could not make progress on the remaining rows')
      expect(pair.router.currentRoute.value.name).toBe('fill-many')
    } finally {
      vi.useRealTimers()
    }
  })

  it('waits out BATCH_BUSY and fills on the retry, saying so in words', async () => {
    // Mounting and the upload happen on real timers; only the busy wait is advanced by hand.
    const pair = await mountView()
    wrapper = pair.wrapper
    await flush()
    await uploadTable(wrapper)

    vi.mocked(createBatch).mockResolvedValue({ id: 99, rowCount: 3, rows: [] } as never)
    vi.mocked(fillBatchStep)
      .mockRejectedValueOnce(new ApiRequestError(409, problem(409, 'busy', 'BATCH_BUSY')))
      .mockResolvedValue({ processed: 3, remaining: 0, rows: [] } as never)

    vi.useFakeTimers()
    try {
      await wrapper.get('button.button--primary').trigger('click')
      await vi.advanceTimersByTimeAsync(0)
      expect(wrapper.text()).toContain('it will try again in a moment')
      await vi.advanceTimersByTimeAsync(2100)

      expect(fillBatchStep).toHaveBeenCalledTimes(2)
      expect(pair.router.currentRoute.value.name).toBe('batch')
    } finally {
      vi.useRealTimers()
    }
  })

  it('has no automatically-detectable accessibility violations', async () => {
    ;({ wrapper } = await mountView())
    await flush()
    await uploadTable(wrapper)

    expect(await axe(wrapper.element)).toHaveNoViolations()
  }, 15000)
})
