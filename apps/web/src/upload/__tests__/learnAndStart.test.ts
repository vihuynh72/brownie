import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetCapabilitiesCache } from '@/capabilities'
import { documentTitleFor, useTemplatesStore } from '@/stores/templates'
import {
  learnFormAndStartDocument,
  learnFormAsTemplate,
  learnStepWords,
  nameOf,
  uniqueName,
  type LearnStep,
  type StepDetail,
} from '@/upload/learnAndStart'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    getCapabilities: vi.fn(),
    allocateUpload: vi.fn(),
    uploadArtifactContent: vi.fn(),
    completeUpload: vi.fn(),
    makeFillableForm: vi.fn(),
    createTemplateDraft: vi.fn(),
    getTemplateLayout: vi.fn(),
    replaceDraftBindings: vi.fn(),
    activateTemplateVersion: vi.fn(),
    listTemplates: vi.fn(),
    listTrashedTemplates: vi.fn(),
    createDocument: vi.fn(),
  }
})

import {
  ApiRequestError,
  activateTemplateVersion,
  allocateUpload,
  completeUpload,
  createDocument,
  createTemplateDraft,
  getCapabilities,
  getTemplateLayout,
  listTemplates,
  listTrashedTemplates,
  makeFillableForm,
  replaceDraftBindings,
  uploadArtifactContent,
  type ArtifactResponse,
  type FillableFormResponse,
  type FillableFormSpot,
  type TemplateVersionResponse,
} from '@/api/client'

const DOCX_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

function wordFile(name = 'Club minutes.docx', size = 2048): File {
  const file = new File(['docx bytes'], name, { type: DOCX_TYPE })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

function problem(status: number, detail: string, code = 'X', extra: Record<string, unknown> = {}) {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [], ...extra }
}

function version(versionNumber: number, status: 'DRAFT' | 'ACTIVATED' = 'DRAFT'): TemplateVersionResponse {
  return {
    id: 40 + versionNumber, templateId: 42, versionNumber, sourceArtifactId: 6, extractionVersionId: 9, status, fields: [],
    createdAt: '2026-09-29T10:00:00Z', activatedAt: status === 'ACTIVATED' ? '2026-09-29T10:01:00Z' : null,
  }
}

function spot(fieldId: string, overrides: Partial<FillableFormSpot> = {}): FillableFormSpot {
  return {
    fieldId, label: null, type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL',
    binding: { kind: 'CONTENT_CONTROL_TAG', tag: fieldId }, origin: 'FORM', docxControl: 'ORIGINAL', blankText: null,
    namedBy: 'RULES', requiredHint: false, suggestedType: null, foundAs: 'EXISTING_TAGGED_CONTROL',
    ...overrides,
  }
}

/** A Word form with a control of its own, a place Brownie found and filled in a blank for, and a repeated date. */
const WORD_SPOTS: FillableFormSpot[] = [
  spot('meeting.title'),
  spot('company.name', {
    label: 'Company name', origin: 'FOUND_BY_BROWNIE', docxControl: 'INSERTED_BY_BROWNIE', blankText: '________',
    namedBy: 'MODEL', requiredHint: true, suggestedType: 'TEXT', foundAs: 'UNDERSCORES',
  }),
  spot('action.item.due', { type: 'DATE', cardinality: 'REPEATED' }),
]

function wordForm(overrides: Partial<FillableFormResponse> = {}): FillableFormResponse {
  return {
    kind: 'DOCX', sourceArtifactId: 5, templateSourceArtifactId: 6, sourceFormat: 'DOCX', converted: false,
    extraction: { id: 9, status: 'COMPLETE', parserVersion: 'p', keptAsIs: [] },
    spots: WORD_SPOTS,
    notices: [
      { code: 'TRACKED_CHANGES_AND_COMMENTS', count: 2, detail: null },
      { code: 'SPOTS_FOUND', count: 3, detail: null },
    ],
    spotNaming: 'MODEL', rulesOnlyReason: null,
    ...overrides,
  }
}

function artifact(status: ArtifactResponse['status'], detectedMediaType: ArtifactResponse['detectedMediaType'], name: string): ArtifactResponse {
  return { id: 5, status, detectedMediaType, displayFilename: name }
}

/** Every request answers the way a real server does for a Word form it made ready to fill. */
function serverMakesTheFormReady(): void {
  vi.mocked(getCapabilities).mockResolvedValue({
    maxUploadBytes: 10 * 1024 * 1024, uploadMediaTypes: [], assistSourceMediaTypes: [], templateMediaTypes: [], trashRetentionDays: 30,
  })
  vi.mocked(allocateUpload).mockResolvedValue(artifact('UPLOADING', null, 'Club minutes.docx'))
  vi.mocked(uploadArtifactContent).mockResolvedValue(artifact('UPLOADING', 'DOCX', 'Club minutes.docx'))
  vi.mocked(completeUpload).mockResolvedValue(artifact('READY', 'DOCX', 'Club minutes.docx'))
  vi.mocked(makeFillableForm).mockResolvedValue(wordForm())
  vi.mocked(createTemplateDraft).mockResolvedValue({
    template: { id: 42, displayName: 'Club minutes', status: 'DRAFT', currentActiveVersionId: null, createdAt: '2026-09-29T10:00:00Z' },
    draftVersion: version(1),
  })
  vi.mocked(replaceDraftBindings).mockResolvedValue(version(2))
  vi.mocked(getTemplateLayout).mockResolvedValue({ templateId: 42, versionId: 42, parserVersion: 'p', parts: [], unplacedFieldIds: [] })
  vi.mocked(activateTemplateVersion).mockResolvedValue(version(2, 'ACTIVATED'))
  // Before the draft the workspace has no template of that name; once activated, the list holds the new one.
  vi.mocked(listTemplates)
    .mockResolvedValueOnce([])
    .mockResolvedValue([
      { id: 42, displayName: 'Club minutes', status: 'ACTIVE', currentActiveVersionId: 42, createdAt: '2026-09-29T10:00:00Z' },
    ])
  vi.mocked(listTrashedTemplates).mockResolvedValue([])
  vi.mocked(createDocument).mockResolvedValue({
    id: 77, title: 'Club minutes', templateId: 42, templateVersionId: 42, currentRevisionId: 1, createdAt: '2026-09-29T10:02:00Z',
    currentRevision: {
      id: 1, revisionNumber: 1, parentRevisionId: null, fields: {}, contentHash: 'h', editReason: 'x', createdAt: '2026-09-29T10:02:00Z',
    },
  } as Awaited<ReturnType<typeof createDocument>>)
}

/** The server found the file to be `mediaType` once it had the bytes. */
function uploadIs(mediaType: ArtifactResponse['detectedMediaType'], name: string): void {
  vi.mocked(allocateUpload).mockResolvedValue(artifact('UPLOADING', null, name))
  vi.mocked(uploadArtifactContent).mockResolvedValue(artifact('UPLOADING', mediaType, name))
  vi.mocked(completeUpload).mockResolvedValue(artifact('READY', mediaType, name))
}

async function run(file: File = wordFile()) {
  const steps: LearnStep[] = []
  const details: [LearnStep, StepDetail][] = []
  const outcome = await learnFormAndStartDocument(7, file, (step, detail) => {
    steps.push(step)
    details.push([step, detail])
  })
  return { outcome, steps, details }
}

function refusal(outcome: Awaited<ReturnType<typeof learnFormAndStartDocument>>): string {
  if (outcome.ok) throw new Error('Expected the form to be refused')
  return outcome.message
}

/** Lets every request the flow has already been answered carry it on to its next wait. */
async function settle(): Promise<void> {
  for (let turn = 0; turn < 100; turn++) await Promise.resolve()
}

/** Moves the fake clock on, then lets the flow carry on from there. */
async function wait(milliseconds: number): Promise<void> {
  vi.advanceTimersByTime(milliseconds)
  await settle()
}

const TRACKED_NOTE = "Brownie's copy has the tracked changes accepted and the comments left out; your original file is unchanged."

describe('learnFormAndStartDocument', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetCapabilitiesCache()
    vi.clearAllMocks()
    serverMakesTheFormReady()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('makes the form ready, keeps every place as the server found it, and opens a document named after the file and the day', async () => {
    const { outcome, steps } = await run()

    expect(outcome).toEqual({
      ok: true,
      documentId: 77,
      name: 'Club minutes',
      // The page says how many places Brownie found, so the notes do not say it again.
      notes: [TRACKED_NOTE],
    })
    expect(steps).toEqual(['uploading', 'checking', 'preparing-copy', 'learning', 'preparing', 'opening'])
    expect(allocateUpload).toHaveBeenCalledWith(7, 'Club minutes.docx')
    expect(makeFillableForm).toHaveBeenCalledWith(7, 5)
    // The template is made from Brownie's clean copy, not from the upload itself, and keeps what the server noticed.
    expect(createTemplateDraft).toHaveBeenCalledWith(7, 'Club minutes', 6, wordForm().notices)
    // How a place was found is not sent; its name, who put it there and the form's own blank are.
    expect(replaceDraftBindings).toHaveBeenCalledWith(7, 42, 1, [
      { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', binding: { kind: 'CONTENT_CONTROL_TAG', tag: 'meeting.title' } },
      {
        fieldId: 'company.name', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL',
        binding: { kind: 'CONTENT_CONTROL_TAG', tag: 'company.name' },
        label: 'Company name', origin: 'FOUND_BY_BROWNIE', docxControl: 'INSERTED_BY_BROWNIE', blankText: '________',
      },
      { fieldId: 'action.item.due', type: 'DATE', cardinality: 'REPEATED', requiredness: 'OPTIONAL', binding: { kind: 'CONTENT_CONTROL_TAG', tag: 'action.item.due' } },
    ])
    expect(getTemplateLayout).toHaveBeenCalledWith(7, 42, 42)
    expect(replaceDraftBindings).toHaveBeenCalledTimes(1)
    // Places were found, so the form activates as any other does.
    expect(activateTemplateVersion).toHaveBeenCalledWith(7, 42, 2)
    expect(createDocument).toHaveBeenCalledWith(7, expect.any(String), {
      title: documentTitleFor('Club minutes'),
      templateId: 42,
      templateVersionId: 42,
      fields: {},
      initialRevisionReason: 'Created from a form uploaded on Home.',
    })
  })

  /** The sidebar lists what the store holds, so the new template has to be in it by the time the document opens. */
  it('adds the template to My Templates only once it is active, before the document is created', async () => {
    const order: string[] = []
    vi.mocked(activateTemplateVersion).mockImplementation(async () => {
      order.push('activate')
      return version(2, 'ACTIVATED')
    })
    vi.mocked(listTemplates).mockReset()
    vi.mocked(listTemplates).mockImplementation(async () => {
      order.push('list templates')
      return []
    })
    vi.mocked(createDocument).mockImplementation(async () => {
      order.push('create document')
      throw new ApiRequestError(503, undefined)
    })

    await run()

    // The first list is read for the names already taken, before anything is made.
    expect(order).toEqual(['list templates', 'activate', 'list templates', 'create document'])
    expect(useTemplatesStore().status).toBe('loaded')
  })

  /** The copy Brownie made has a name of its own; the person knows the form by the file they chose. */
  it("names the template and the document after the file the person chose, not Brownie's copy of it", async () => {
    uploadIs('ODT', 'Lease agreement.odt')
    vi.mocked(makeFillableForm).mockResolvedValue(wordForm({ sourceFormat: 'ODT', converted: true, templateSourceArtifactId: 12 }))

    const { outcome } = await run(new File(['odt'], 'Lease agreement.odt'))

    expect(createTemplateDraft).toHaveBeenCalledWith(7, 'Lease agreement', 12, expect.any(Array))
    expect(outcome).toMatchObject({ ok: true, name: 'Lease agreement' })
    expect(createDocument).toHaveBeenCalledWith(7, expect.any(String), expect.objectContaining({ title: documentTitleFor('Lease agreement') }))
  })

  /** Uploading the same form again adds a second template; its name tells it apart from the first. */
  it('names a form uploaded again beside one already learned apart from it', async () => {
    vi.mocked(listTemplates).mockReset()
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 40, displayName: 'Club minutes', status: 'ACTIVE', currentActiveVersionId: 40, createdAt: '2026-09-28T10:00:00Z' },
      { id: 41, displayName: 'Club minutes (2)', status: 'ACTIVE', currentActiveVersionId: 41, createdAt: '2026-09-28T11:00:00Z' },
    ])

    const { outcome } = await run()

    expect(createTemplateDraft).toHaveBeenCalledWith(7, 'Club minutes (3)', 6, expect.any(Array))
    expect(outcome).toMatchObject({ ok: true, name: 'Club minutes (3)' })
    expect(uniqueName('Club minutes', [])).toBe('Club minutes')
  })

  /** Only names a person can see are taken: a draft a refused upload left behind is seen nowhere; the Trash Bin is. */
  it('takes the names in My Templates and the Trash Bin, not a draft nobody can see', async () => {
    vi.mocked(listTemplates).mockReset()
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 40, displayName: 'Club minutes', status: 'DRAFT', currentActiveVersionId: null, createdAt: '2026-09-28T10:00:00Z' },
    ])
    const { outcome } = await run()
    expect(outcome).toMatchObject({ ok: true, name: 'Club minutes' })

    vi.mocked(listTrashedTemplates).mockResolvedValue([
      { id: 41, displayName: 'Club minutes', status: 'ACTIVE', currentActiveVersionId: 41, createdAt: '2026-09-28T10:00:00Z', trashedAt: '2026-09-29T09:00:00Z' },
    ])
    const again = await run()
    expect(again.outcome).toMatchObject({ ok: true, name: 'Club minutes (2)' })
  })

  it.each([
    ['DOCX', 'Club minutes.docx', 'Finding where the values go…'],
    ['DOC', 'Club minutes.doc', 'Opening your Word 97-2003 file and finding where the values go…'],
    ['RTF', 'Club minutes.rtf', 'Opening your RTF file and finding where the values go…'],
    ['ODT', 'Club minutes.odt', 'Opening your OpenDocument file and finding where the values go…'],
    ['PAGES', 'Club minutes.pages', 'Opening your Pages file and finding where the values go…'],
    ['PDF', 'Club minutes.pdf', 'Reading your PDF and finding where the values go…'],
  ] as const)('says what kind of file it is opening, by what the server found in the bytes (%s)', async (mediaType, name, words) => {
    uploadIs(mediaType, name)

    const { details } = await run(new File(['bytes'], name))

    const [, detail] = details.find(([step]) => step === 'preparing-copy')!
    expect(detail).toEqual({ mediaType, waiting: false })
    expect(learnStepWords('preparing-copy', detail)).toBe(words)
  })

  /** A PDF is filled as a PDF: its own fields, or boxes on its pages; nothing is drawn as a Word page to check. */
  it('opens a PDF form with its own fields and the boxes Brownie found, and says what it leaves to the person', async () => {
    uploadIs('PDF', 'Membership form.pdf')
    const pdfSpots: FillableFormSpot[] = [
      spot('member.name', {
        label: 'Member name', binding: { kind: 'ACROFORM_FIELD', acroFormField: 'member.name' }, docxControl: null, foundAs: null,
      }),
      spot('joined.on', {
        label: 'Joined on', type: 'DATE', origin: 'FOUND_BY_BROWNIE', docxControl: null, foundAs: null,
        binding: { kind: 'PAGE_BOX', pageBox: { page: 1, x: 72, y: 144, width: 120, height: 14, multiline: false, overflow: 'SHRINK_TO_FIT' } },
      }),
    ]
    vi.mocked(makeFillableForm).mockResolvedValue({
      kind: 'PDF', sourceArtifactId: 5, templateSourceArtifactId: 5, sourceFormat: 'PDF', converted: false,
      extraction: { id: 31, status: 'COMPLETE', parserVersion: 'pdf', keptAsIs: [] },
      spots: pdfSpots,
      notices: [
        { code: 'SPOTS_FOUND', count: 2, detail: null },
        { code: 'PDF_FIELDS_LEFT', count: 3, detail: null },
      ],
      spotNaming: 'RULES', rulesOnlyReason: 'DISABLED',
    })

    const { outcome, steps } = await run(new File(['%PDF'], 'Membership form.pdf', { type: 'application/pdf' }))

    expect(outcome).toEqual({
      ok: true,
      documentId: 77,
      name: 'Membership form',
      notes: ["Brownie will fill 1 of this form's fields. It leaves the check boxes and lists for you to set in your PDF reader."],
    })
    expect(steps).toEqual(['uploading', 'checking', 'preparing-copy', 'learning', 'preparing', 'opening'])
    expect(createTemplateDraft).toHaveBeenCalledWith(7, 'Membership form', 5, [
      { code: 'SPOTS_FOUND', count: 2, detail: null },
      { code: 'PDF_FIELDS_LEFT', count: 3, detail: null },
    ])
    expect(replaceDraftBindings).toHaveBeenCalledWith(7, 42, 1, [
      {
        fieldId: 'member.name', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', label: 'Member name',
        binding: { kind: 'ACROFORM_FIELD', acroFormField: 'member.name' },
      },
      {
        fieldId: 'joined.on', type: 'DATE', cardinality: 'SCALAR', requiredness: 'OPTIONAL', label: 'Joined on', origin: 'FOUND_BY_BROWNIE',
        binding: { kind: 'PAGE_BOX', pageBox: { page: 1, x: 72, y: 144, width: 120, height: 14, multiline: false, overflow: 'SHRINK_TO_FIT' } },
      },
    ])
    expect(getTemplateLayout).not.toHaveBeenCalled()
    expect(activateTemplateVersion).toHaveBeenCalledWith(7, 42, 2)
  })

  /** Nothing found is no reason to stop: the document opens with no places, and the note says how to add one. */
  it('opens a form with no places found, activating it as one with none', async () => {
    vi.mocked(makeFillableForm).mockResolvedValue(wordForm({ spots: [], notices: [{ code: 'NO_SPOTS_FOUND', count: 0, detail: null }] }))
    vi.mocked(activateTemplateVersion).mockResolvedValue(version(1, 'ACTIVATED'))

    const { outcome } = await run()

    expect(outcome).toEqual({
      ok: true,
      documentId: 77,
      name: 'Club minutes',
      notes: ['Brownie did not find any blanks. Choose Add a fill spot, or select a place on the page and choose Fill in here.'],
    })
    expect(replaceDraftBindings).not.toHaveBeenCalled()
    expect(getTemplateLayout).not.toHaveBeenCalled()
    expect(activateTemplateVersion).toHaveBeenCalledWith(7, 42, 1, { allowNoPlaces: true })
    expect(createDocument).toHaveBeenCalledWith(7, expect.any(String), expect.objectContaining({ templateVersionId: 41 }))
  })

  /** Every render slot was taken: the step says it is waiting, waits as long as the server asked (never past 10 s), and asks again. */
  it('waits for a free moment and asks again, twice, when the server is too busy to start', async () => {
    vi.useFakeTimers()
    vi.mocked(makeFillableForm)
      .mockRejectedValueOnce(new ApiRequestError(503, problem(503, 'Too many documents are being prepared right now.', 'RENDERER_BUSY'), 3))
      .mockRejectedValueOnce(new ApiRequestError(503, problem(503, 'Too many documents are being prepared right now.', 'RENDERER_BUSY'), 30))
      .mockResolvedValueOnce(wordForm())

    const running = run()
    await settle()
    expect(makeFillableForm).toHaveBeenCalledTimes(1)
    await wait(2_999)
    expect(makeFillableForm).toHaveBeenCalledTimes(1)
    await wait(1)
    expect(makeFillableForm).toHaveBeenCalledTimes(2)
    // Asked to wait 30 s, it waits its own longest, 10 s.
    await wait(9_999)
    expect(makeFillableForm).toHaveBeenCalledTimes(2)
    await wait(1)
    const { outcome, details } = await running

    expect(outcome.ok).toBe(true)
    expect(makeFillableForm).toHaveBeenCalledTimes(3)
    const said = details.filter(([step]) => step === 'preparing-copy').map(([, detail]) => learnStepWords('preparing-copy', detail))
    expect(said).toEqual([
      'Finding where the values go…',
      'Waiting for a free moment…',
      'Finding where the values go…',
      'Waiting for a free moment…',
      'Finding where the values go…',
    ])
  })

  it("stops after two more tries and gives the server's own words, adding nothing", async () => {
    vi.useFakeTimers()
    vi.mocked(makeFillableForm).mockRejectedValue(
      new ApiRequestError(503, problem(503, 'Too many documents are being prepared right now. Nothing was changed; try again shortly.', 'RENDERER_BUSY')),
    )

    const running = run()
    await settle()
    await wait(10_000)
    await wait(10_000)
    const { outcome } = await running

    expect(refusal(outcome)).toBe(
      'Could not open "Club minutes.docx". Too many documents are being prepared right now. Nothing was changed; try again shortly.',
    )
    expect(makeFillableForm).toHaveBeenCalledTimes(3)
    expect(createTemplateDraft).not.toHaveBeenCalled()
  })

  it.each([
    [
      'plain text',
      'PLAIN_TEXT',
      new ApiRequestError(415, problem(415, 'x', 'NOT_A_WORD_PROCESSING_DOCUMENT')),
      'This file is not a document Brownie can fill in. Brownie can fill Word, RTF, OpenDocument and Pages documents, and PDF forms.',
    ],
    [
      'a format this server does not convert',
      'PAGES',
      new ApiRequestError(415, problem(415, 'x', 'FORMAT_DISABLED', { format: 'PAGES' })),
      'This Brownie does not open Pages files right now. Open the file, save it as a Word document (.docx) and upload that.',
    ],
    [
      'an old Word file that would not open',
      'DOC',
      new ApiRequestError(422, problem(422, 'x', 'FILLABLE_FORM_FAILED', { reason: 'CANNOT_OPEN' })),
      'Brownie could not open this Word 97-2003 file. Open it in Word and save it as a Word document (.docx), then upload that.',
    ],
    [
      'a Pages file that would not open',
      'PAGES',
      new ApiRequestError(422, problem(422, 'x', 'FILLABLE_FORM_FAILED', { reason: 'CANNOT_OPEN' })),
      'Brownie could not open this Pages file. Open it in Pages and save it as a Word document (.docx) (File > Export To > Word), then upload that.',
    ],
    [
      'a damaged file',
      'DOCX',
      new ApiRequestError(422, problem(422, 'x', 'FILLABLE_FORM_FAILED', { reason: 'DAMAGED' })),
      'Brownie could not read this file. It may be damaged: open it, save it again, and upload it again.',
    ],
    [
      'a conversion that took too long',
      'RTF',
      new ApiRequestError(422, problem(422, 'x', 'FILLABLE_FORM_FAILED', { reason: 'TIMED_OUT' })),
      'Opening this RTF file took too long, so Brownie stopped. Try again, or open it, save it as a Word document (.docx) and upload that.',
    ],
    [
      'a converter that could not run',
      'ODT',
      new ApiRequestError(503, problem(503, 'x', 'CONVERTER_UNAVAILABLE'), 30),
      'Brownie cannot open OpenDocument files right now. Try again in a few minutes, or open the file, save it as a Word document (.docx) and upload that.',
    ],
  ] as const)('says why %s cannot be made ready to fill, and adds nothing', async (_case, mediaType, error, sentence) => {
    uploadIs(mediaType, 'Form')
    vi.mocked(makeFillableForm).mockRejectedValue(error)

    const { outcome } = await run(new File(['bytes'], 'Form'))

    expect(refusal(outcome)).toBe(sentence)
    expect(makeFillableForm).toHaveBeenCalledTimes(1)
    expect(createTemplateDraft).not.toHaveBeenCalled()
  })

  it.each([
    [
      'ENCRYPTED',
      'This PDF is locked with a password or protection settings, so Brownie cannot fill it without removing that protection. Save an unlocked copy and upload that.',
    ],
    ['SIGNED', 'This PDF has been signed. Filling it in would break the signature, so Brownie leaves it as it is.'],
    ['XFA', 'This PDF is a kind of form Brownie cannot fill. Open it in Adobe Acrobat Reader, print it to a new PDF, and upload that copy.'],
    ['LAUNCH_ACTION', 'This PDF tries to start other programs when it is opened, so Brownie does not accept it. Print it to a new PDF and upload that copy.'],
    ['EMBEDDED_FILES', 'This PDF has other files attached inside it, so Brownie does not accept it. Save a copy without the attached files and upload that.'],
    ['DOCUMENT_JAVASCRIPT', 'This PDF runs scripts when it is opened, so Brownie does not accept it. Print it to a new PDF and upload that copy.'],
    ['DAMAGED', 'Brownie could not read this PDF. It may be damaged: open it, save it again, and upload it again.'],
    ['TOO_LARGE', 'This PDF has more pages or fields than Brownie can fill in one form. Upload a shorter PDF, such as just the pages to fill in.'],
  ])('says in plain words why a PDF cannot be filled (%s)', async (reason, sentence) => {
    uploadIs('PDF', 'Form.pdf')
    vi.mocked(makeFillableForm).mockRejectedValue(new ApiRequestError(422, problem(422, 'x', 'PDF_FORM_NOT_FILLABLE', { reason })))

    const { outcome } = await run(new File(['%PDF'], 'Form.pdf', { type: 'application/pdf' }))

    expect(refusal(outcome)).toBe(sentence)
    expect(createTemplateDraft).not.toHaveBeenCalled()
  })

  it('gives the same words when a PDF is refused as the template is made from it', async () => {
    uploadIs('PDF', 'Form.pdf')
    vi.mocked(makeFillableForm).mockResolvedValue(wordForm({ kind: 'PDF', sourceFormat: 'PDF', templateSourceArtifactId: 5 }))
    vi.mocked(createTemplateDraft).mockRejectedValue(new ApiRequestError(422, problem(422, 'x', 'PDF_FORM_NOT_FILLABLE', { reason: 'SIGNED' })))

    const { outcome } = await run(new File(['%PDF'], 'Form.pdf', { type: 'application/pdf' }))

    expect(refusal(outcome)).toBe('This PDF has been signed. Filling it in would break the signature, so Brownie leaves it as it is.')
  })

  it('tells a server that cannot open forms yet apart from a refused file', async () => {
    vi.mocked(makeFillableForm).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'No static resource api/v1/workspaces/7/artifacts/5/fillable-form.', 'NOT_FOUND')),
    )

    const { outcome } = await run()

    expect(refusal(outcome)).toMatch(/^Could not open "Club minutes.docx"\. The Brownie server that answered is older than this page and does not have a way to open forms yet\./)
  })

  /** The chooser only suggests: a file with a name this page does not know is still the server's to judge. */
  it('sends a file of a kind it does not know to the server, which judges it by its bytes', async () => {
    uploadIs('PLAIN_TEXT', 'notes.txt')
    vi.mocked(makeFillableForm).mockRejectedValue(new ApiRequestError(415, problem(415, 'x', 'NOT_A_WORD_PROCESSING_DOCUMENT')))

    const { outcome } = await run(new File(['notes'], 'notes.txt', { type: 'text/plain' }))

    expect(allocateUpload).toHaveBeenCalledWith(7, 'notes.txt')
    expect(refusal(outcome)).toBe(
      'This file is not a document Brownie can fill in. Brownie can fill Word, RTF, OpenDocument and Pages documents, and PDF forms.',
    )
  })

  it.each([
    ['SPREADSHEET', 'This is a spreadsheet, not a document. Brownie can fill Word, RTF, OpenDocument and Pages documents, and PDF forms.'],
    ['PASSWORD_PROTECTED', "This file is locked with a password, so Brownie can't open it. Open it, remove the password, save it, and upload it again."],
    [
      undefined,
      'Brownie can fill Word (.docx, .doc), RTF, OpenDocument (.odt) and Pages documents, and PDF forms. It could not recognize this file as any of them.',
    ],
  ])('words a file refused on upload by the reason the server gives (%s)', async (reason, sentence) => {
    vi.mocked(uploadArtifactContent).mockRejectedValue(
      new ApiRequestError(415, problem(415, 'Refused.', 'UNSUPPORTED_MEDIA_TYPE', reason ? { reason } : {})),
    )

    const { outcome } = await run()

    expect(refusal(outcome)).toBe(sentence)
    expect(completeUpload).not.toHaveBeenCalled()
  })

  it('says how to export a Pages document a browser could only send as an empty package, and sends nothing', async () => {
    const { outcome, steps } = await run(new File([], 'Lease.pages'))

    expect(refusal(outcome)).toBe(
      "This Pages document is saved as a package, which a browser can't upload. In Pages, choose File > Export To > Word " +
        'and upload that file, or choose File > Advanced > Change File Type > Single File.',
    )
    expect(steps).toEqual([])
    expect(allocateUpload).not.toHaveBeenCalled()
  })

  it('refuses a file over the upload limit before sending it, naming the limit', async () => {
    const { outcome } = await run(wordFile('Big form.docx', 11 * 1024 * 1024))

    expect(refusal(outcome)).toBe('This file is larger than the 10 MB upload limit, so it was not uploaded.')
    expect(allocateUpload).not.toHaveBeenCalled()
  })

  it('still uploads when the limit cannot be learned, and says what the server said when it refuses the size', async () => {
    vi.mocked(getCapabilities).mockRejectedValue(new TypeError('Failed to fetch'))
    vi.mocked(uploadArtifactContent).mockRejectedValue(new ApiRequestError(413, problem(413, 'The upload exceeds 10 MB.')))

    const { outcome } = await run(wordFile('Big form.docx', 11 * 1024 * 1024))

    expect(refusal(outcome)).toBe('Could not upload "Big form.docx". The upload exceeds 10 MB.')
  })

  it.each([
    ['MALWARE_DETECTED', 'Brownie did not accept "Club minutes.docx": the malware scan flagged it.'],
    ['RIGHTS_PROTECTED', "This file is protected by your organization's rights management, so Brownie can't open it."],
  ])('says why the check refused the file (%s), and goes no further', async (rejectionReason, sentence) => {
    vi.mocked(completeUpload).mockResolvedValue({ ...artifact('REJECTED', 'DOCX', 'Club minutes.docx'), rejectionReason })

    const { outcome } = await run()

    expect(refusal(outcome)).toBe(sentence)
    expect(makeFillableForm).not.toHaveBeenCalled()
  })

  it('leaves out a place outside the main text of a Word form, and says which by its name', async () => {
    vi.mocked(getTemplateLayout).mockResolvedValue({
      templateId: 42, versionId: 42, parserVersion: 'p', parts: [], unplacedFieldIds: ['company.name'],
    })
    vi.mocked(replaceDraftBindings).mockResolvedValueOnce(version(2)).mockResolvedValueOnce(version(3))
    vi.mocked(activateTemplateVersion).mockResolvedValue(version(3, 'ACTIVATED'))

    const { outcome } = await run()

    expect(replaceDraftBindings).toHaveBeenLastCalledWith(7, 42, 2, [
      expect.objectContaining({ fieldId: 'meeting.title' }),
      expect.objectContaining({ fieldId: 'action.item.due' }),
    ])
    expect(activateTemplateVersion).toHaveBeenCalledWith(7, 42, 3)
    expect(outcome).toMatchObject({ ok: true })
    expect(outcome.ok && outcome.notes.at(-1)).toBe(
      'Brownie left out "Company name": it is outside the main text of the form, such as in a header or footer, and Brownie fills only the main text.',
    )
  })

  it('still opens a Word form whose every place is outside its main text, with none', async () => {
    vi.mocked(getTemplateLayout).mockResolvedValue({
      templateId: 42, versionId: 42, parserVersion: 'p', parts: [], unplacedFieldIds: ['meeting.title', 'company.name', 'action.item.due'],
    })
    vi.mocked(replaceDraftBindings).mockResolvedValueOnce(version(2)).mockResolvedValueOnce(version(3))
    vi.mocked(activateTemplateVersion).mockResolvedValue(version(3, 'ACTIVATED'))

    const { outcome } = await run()

    expect(replaceDraftBindings).toHaveBeenLastCalledWith(7, 42, 2, [])
    expect(activateTemplateVersion).toHaveBeenCalledWith(7, 42, 3, { allowNoPlaces: true })
    expect(outcome.ok && outcome.notes.at(-1)).toBe(
      'Brownie left out "Meeting title", "Company name" and "Action item due": they are outside the main text of the form, such as in a header or footer, and Brownie fills only the main text.',
    )
  })

  /** A server older than the page it draws still opens the form; activation is then the only judge. */
  it('goes on without the page when the server cannot draw it', async () => {
    vi.mocked(getTemplateLayout).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'No static resource api/v1/workspaces/7/templates/42/versions/42/layout.', 'NOT_FOUND')),
    )

    const { outcome } = await run()

    expect(outcome.ok).toBe(true)
    expect(replaceDraftBindings).toHaveBeenCalledTimes(1)
  })

  /** A repeated row the filler cannot find would stop the form; filled as single values, the same places work. */
  it('activates a table-laid-out form with every value single when the server refuses its repeated row', async () => {
    vi.mocked(activateTemplateVersion)
      .mockRejectedValueOnce(
        new ApiRequestError(422, problem(422, 'No prototype paragraph found for repeated field action.item.due.', 'TEMPLATE_FILL_BINDING_NOT_FOUND')),
      )
      .mockResolvedValueOnce(version(3, 'ACTIVATED'))
    vi.mocked(replaceDraftBindings).mockResolvedValueOnce(version(2)).mockResolvedValueOnce(version(3))

    const { outcome } = await run()

    expect(outcome).toMatchObject({ ok: true, documentId: 77, name: 'Club minutes' })
    expect(replaceDraftBindings).toHaveBeenLastCalledWith(7, 42, 2, [
      expect.objectContaining({ fieldId: 'meeting.title', cardinality: 'SCALAR' }),
      expect.objectContaining({ fieldId: 'company.name', cardinality: 'SCALAR', label: 'Company name', origin: 'FOUND_BY_BROWNIE' }),
      expect.objectContaining({ fieldId: 'action.item.due', cardinality: 'SCALAR' }),
    ])
    expect(activateTemplateVersion).toHaveBeenLastCalledWith(7, 42, 3)
    expect(createDocument).toHaveBeenCalledWith(7, expect.any(String), expect.objectContaining({ templateVersionId: 43 }))
  })

  it('does not try again with single values when nothing was repeated to begin with', async () => {
    vi.mocked(makeFillableForm).mockResolvedValue(wordForm({ spots: [WORD_SPOTS[0]!] }))
    vi.mocked(activateTemplateVersion).mockRejectedValue(
      new ApiRequestError(422, problem(422, 'No content control tagged "meeting.title" was found.', 'TEMPLATE_FILL_BINDING_NOT_FOUND')),
    )

    const { outcome } = await run()

    expect(refusal(outcome)).toBe(
      'Brownie could not finish learning the form in "Club minutes.docx", so nothing was added to My Templates. No content control tagged "meeting.title" was found.',
    )
    expect(replaceDraftBindings).toHaveBeenCalledTimes(1)
  })

  it("repeats the server's own words when it is too busy to get the template ready, and says nothing was added", async () => {
    vi.mocked(activateTemplateVersion).mockRejectedValue(
      new ApiRequestError(503, problem(503, 'The document renderer is busy. Try again in a minute.', 'RENDERER_BUSY')),
    )

    const { outcome } = await run()

    expect(refusal(outcome)).toBe(
      'Brownie could not finish learning the form in "Club minutes.docx", so nothing was added to My Templates. The document renderer is busy. Try again in a minute.',
    )
    // Read once, for the names already taken; My Templates is never refreshed for a form that was not learned.
    expect(listTemplates).toHaveBeenCalledTimes(1)
    expect(createDocument).not.toHaveBeenCalled()
  })

  it('says the template is in My Templates when only the document could not be created', async () => {
    vi.mocked(createDocument).mockRejectedValue(new ApiRequestError(500, problem(500, 'Unexpected.', 'INTERNAL_ERROR')))

    const { outcome } = await run()

    expect(refusal(outcome)).toBe(
      'Brownie learned "Club minutes" and added it to My Templates, but could not start a document from it. ' +
        'Something went wrong inside Brownie. If it happens again, give whoever runs this Brownie this reference: c. ' +
        'Choose it under My Templates to try again.',
    )
  })

  it.each([
    [
      'a server older than this page',
      new ApiRequestError(404, problem(404, 'No static resource api/v1/workspaces/7/templates.', 'NOT_FOUND')),
      'older than this page and does not have a way to learn forms',
    ],
    ['an ended session', new ApiRequestError(401, undefined), 'Your session has ended. Sign in again to carry on.'],
    ['a server that cannot be reached', new TypeError('Failed to fetch'), 'Brownie could not be reached.'],
  ])('says what went wrong while learning the form when it meets %s', async (_case, error, words) => {
    vi.mocked(createTemplateDraft).mockRejectedValue(error)

    const { outcome } = await run()

    expect(refusal(outcome)).toMatch(/^Brownie could not learn the form in "Club minutes.docx"\. /)
    expect(refusal(outcome)).toContain(words)
  })

  it('never shows a bare status code for an answer with no explanation', async () => {
    vi.mocked(allocateUpload).mockRejectedValue(new ApiRequestError(502, undefined))

    const { outcome } = await run()

    expect(refusal(outcome)).toBe('Could not upload "Club minutes.docx". Brownie could not be reached. It may not be running; try again in a minute.')
    expect(refusal(outcome)).not.toContain('502')
  })
})

/** The + beside My Templates: the same flow as Home, ending with the template rather than a document. */
describe('learnFormAsTemplate', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetCapabilitiesCache()
    vi.clearAllMocks()
    // A list queued for a test that stopped before reading it would otherwise answer this one.
    vi.mocked(listTemplates).mockReset()
    serverMakesTheFormReady()
  })

  it('adds the form to My Templates as Home does, and starts no document', async () => {
    const steps: LearnStep[] = []

    const outcome = await learnFormAsTemplate(7, wordFile(), (step) => steps.push(step))

    expect(outcome).toEqual({ ok: true, templateId: 42, versionId: 42, name: 'Club minutes', leftOutNote: null })
    expect(steps).toEqual(['uploading', 'checking', 'preparing-copy', 'learning', 'preparing'])
    expect(createTemplateDraft).toHaveBeenCalledWith(7, 'Club minutes', 6, wordForm().notices)
    expect(activateTemplateVersion).toHaveBeenCalledWith(7, 42, 2)
    expect(createDocument).not.toHaveBeenCalled()
    // My Templates shows it as soon as it is active.
    expect(useTemplatesStore().usable.map((template) => template.displayName)).toEqual(['Club minutes'])
  })

  it('says which places it had to leave out', async () => {
    vi.mocked(getTemplateLayout).mockResolvedValue({
      templateId: 42, versionId: 42, parserVersion: 'p', parts: [], unplacedFieldIds: ['company.name'],
    })
    vi.mocked(replaceDraftBindings).mockResolvedValueOnce(version(2)).mockResolvedValueOnce(version(3))
    vi.mocked(activateTemplateVersion).mockResolvedValue(version(3, 'ACTIVATED'))

    const outcome = await learnFormAsTemplate(7, wordFile())

    expect(outcome).toMatchObject({
      ok: true,
      versionId: 43,
      leftOutNote:
        'Brownie left out "Company name": it is outside the main text of the form, such as in a header or footer, and Brownie fills only the main text.',
    })
  })

  it.each([
    ['a spreadsheet', () => vi.mocked(uploadArtifactContent).mockRejectedValue(new ApiRequestError(415, problem(415, 'x', 'UNSUPPORTED_MEDIA_TYPE', { reason: 'SPREADSHEET' })))],
    ['a file it cannot open', () => vi.mocked(makeFillableForm).mockRejectedValue(new ApiRequestError(415, problem(415, 'x', 'NOT_A_WORD_PROCESSING_DOCUMENT')))],
    ['a form it cannot finish learning', () => vi.mocked(activateTemplateVersion).mockRejectedValue(new ApiRequestError(503, undefined))],
  ])('refuses %s in the same words as Home', async (_case, refuse) => {
    refuse()
    const fromHome = await learnFormAndStartDocument(7, wordFile())

    const outcome = await learnFormAsTemplate(7, wordFile())

    expect(outcome.ok).toBe(false)
    expect(outcome).toEqual({ ok: false, message: refusal(fromHome) })
    expect(createDocument).not.toHaveBeenCalled()
  })
})

describe('learnStepWords', () => {
  it.each([
    ['uploading', 'Uploading…'],
    ['checking', 'Checking the file…'],
    ['learning', 'Learning the form…'],
    ['preparing', 'Getting the template ready…'],
    ['opening', 'Opening your document…'],
  ] as const)('says %s plainly', (step, words) => {
    expect(learnStepWords(step, { mediaType: 'DOCX', waiting: false })).toBe(words)
  })

  it('names no format it does not know, and says when it waits', () => {
    expect(learnStepWords('preparing-copy', { mediaType: null, waiting: false })).toBe('Finding where the values go…')
    expect(learnStepWords('preparing-copy', { mediaType: 'DOTX', waiting: false })).toBe('Finding where the values go…')
    expect(learnStepWords('preparing-copy', { mediaType: 'PAGES', waiting: true })).toBe('Waiting for a free moment…')
  })
})

describe('nameOf', () => {
  it.each([
    ['Club minutes.docx', 'Club minutes'],
    ['Budget.2026.docx', 'Budget.2026'],
    ['  Spaced  .docx', 'Spaced'],
    ['.docx', 'Uploaded form'],
    ['No extension', 'No extension'],
  ])('names %s as %s', (fileName, name) => {
    expect(nameOf(fileName)).toBe(name)
  })
})
