import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetCapabilitiesCache } from '@/capabilities'
import { documentTitleFor, useTemplatesStore } from '@/stores/templates'
import { uniqueName, learnFormAndStartDocument, nameOf, type LearnStep } from '@/upload/learnAndStart'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    getCapabilities: vi.fn(),
    allocateUpload: vi.fn(),
    uploadArtifactContent: vi.fn(),
    completeUpload: vi.fn(),
    extractArtifact: vi.fn(),
    createTemplateDraft: vi.fn(),
    getDraftCandidateBindings: vi.fn(),
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
  extractArtifact,
  getCapabilities,
  getDraftCandidateBindings,
  getTemplateLayout,
  listTemplates,
  listTrashedTemplates,
  replaceDraftBindings,
  uploadArtifactContent,
  type CandidateBindingReportResponse,
  type TemplateVersionResponse,
} from '@/api/client'

const DOCX_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

function wordFile(name = 'Club minutes.docx', size = 2048): File {
  const file = new File(['docx bytes'], name, { type: DOCX_TYPE })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

function problem(status: number, detail: string, code = 'X') {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [] }
}

function version(versionNumber: number, status: 'DRAFT' | 'ACTIVATED' = 'DRAFT'): TemplateVersionResponse {
  return {
    id: 40 + versionNumber, templateId: 42, versionNumber, sourceArtifactId: 5, extractionVersionId: 9, status, fields: [],
    createdAt: '2026-09-29T10:00:00Z', activatedAt: status === 'ACTIVATED' ? '2026-09-29T10:01:00Z' : null,
  }
}

const TWO_FIELDS: CandidateBindingReportResponse = {
  candidates: [
    { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', contentControlTag: 'meeting.title' },
    { fieldId: 'action.item.due', type: 'DATE', cardinality: 'REPEATED', contentControlTag: 'action.item.due' },
  ],
  ambiguousContentControlTags: [],
}

/** Every request answers the way a real server does for a tagged Word form. */
function serverLearnsTheForm(): void {
  vi.mocked(getCapabilities).mockResolvedValue({
    maxUploadBytes: 10 * 1024 * 1024, uploadMediaTypes: [], assistSourceMediaTypes: [], templateMediaTypes: [], trashRetentionDays: 30,
  })
  vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'Club minutes.docx' })
  vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 5, status: 'UPLOADING', detectedMediaType: 'DOCX', displayFilename: 'Club minutes.docx' })
  vi.mocked(completeUpload).mockResolvedValue({ id: 5, status: 'READY', detectedMediaType: 'DOCX', displayFilename: 'Club minutes.docx' })
  vi.mocked(extractArtifact).mockResolvedValue({ status: 'COMPLETE' })
  vi.mocked(createTemplateDraft).mockResolvedValue({
    template: { id: 42, displayName: 'Club minutes', status: 'DRAFT', currentActiveVersionId: null, createdAt: '2026-09-29T10:00:00Z' },
    draftVersion: version(1),
  })
  vi.mocked(getDraftCandidateBindings).mockResolvedValue(TWO_FIELDS)
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

async function run(file: File = wordFile()) {
  const steps: LearnStep[] = []
  const outcome = await learnFormAndStartDocument(7, file, (step) => steps.push(step))
  return { outcome, steps }
}

function refusal(outcome: Awaited<ReturnType<typeof learnFormAndStartDocument>>): string {
  if (outcome.ok) throw new Error('Expected the form to be refused')
  return outcome.message
}

describe('learnFormAndStartDocument', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetCapabilitiesCache()
    vi.clearAllMocks()
    serverLearnsTheForm()
  })

  it("learns every content control the server suggests, as it suggests it, and opens a document named after the file and the day", async () => {
    const { outcome, steps } = await run()

    expect(outcome).toEqual({ ok: true, documentId: 77, name: 'Club minutes', note: null })
    expect(steps).toEqual(['uploading', 'checking', 'learning', 'preparing', 'opening'])
    expect(allocateUpload).toHaveBeenCalledWith(7, 'Club minutes.docx')
    expect(createTemplateDraft).toHaveBeenCalledWith(7, 'Club minutes', 5)
    expect(replaceDraftBindings).toHaveBeenCalledWith(7, 42, 1, [
      { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', binding: { kind: 'CONTENT_CONTROL_TAG', tag: 'meeting.title' } },
      { fieldId: 'action.item.due', type: 'DATE', cardinality: 'REPEATED', requiredness: 'OPTIONAL', binding: { kind: 'CONTENT_CONTROL_TAG', tag: 'action.item.due' } },
    ])
    expect(getTemplateLayout).toHaveBeenCalledWith(7, 42, 42)
    expect(replaceDraftBindings).toHaveBeenCalledTimes(1)
    expect(activateTemplateVersion).toHaveBeenCalledWith(7, 42, 2)
    expect(createDocument).toHaveBeenCalledWith(7, expect.any(String), {
      title: documentTitleFor('Club minutes'),
      templateId: 42,
      templateVersionId: 42,
      fields: {},
      initialRevisionReason: 'Created from a Word form uploaded on Home.',
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

  /** Uploading the same form again adds a second template; its name tells it apart from the first. */
  it('names a form uploaded again beside one already learned apart from it', async () => {
    vi.mocked(listTemplates).mockReset()
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 40, displayName: 'Club minutes', status: 'ACTIVE', currentActiveVersionId: 40, createdAt: '2026-09-28T10:00:00Z' },
      { id: 41, displayName: 'Club minutes (2)', status: 'ACTIVE', currentActiveVersionId: 41, createdAt: '2026-09-28T11:00:00Z' },
    ])

    const { outcome } = await run()

    expect(createTemplateDraft).toHaveBeenCalledWith(7, 'Club minutes (3)', 5)
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
    ['a PDF by its name', new File(['%PDF'], 'form.pdf', { type: '' })],
    ['a PDF by its type', new File(['%PDF'], 'scan', { type: 'application/pdf' })],
  ])('says plainly that a PDF cannot be filled, and sends nothing (%s)', async (_case, file) => {
    const { outcome, steps } = await run(file)

    expect(refusal(outcome)).toBe('Brownie can fill Word (.docx) forms. It cannot fill a PDF.')
    expect(steps).toEqual([])
    expect(allocateUpload).not.toHaveBeenCalled()
  })

  it('asks for a .docx file when the chosen one is something else, and sends nothing', async () => {
    const { outcome } = await run(new File(['notes'], 'notes.txt', { type: 'text/plain' }))

    expect(refusal(outcome)).toBe('Brownie can fill Word (.docx) forms. Choose a .docx file.')
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

  it('says a file named .docx that is not a Word file inside cannot be opened', async () => {
    vi.mocked(uploadArtifactContent).mockRejectedValue(
      new ApiRequestError(415, problem(415, 'Content does not match any supported media type or package signature.', 'UNSUPPORTED_MEDIA_TYPE')),
    )

    const { outcome } = await run(wordFile('old form.docx'))

    expect(refusal(outcome)).toBe(
      'Brownie can fill Word (.docx) forms, and it could not open this file as one. Choose a .docx file saved from Word.',
    )
    expect(completeUpload).not.toHaveBeenCalled()
  })

  it('goes by what the server found in the bytes: a PDF with a Word name is still a PDF', async () => {
    vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 5, status: 'UPLOADING', detectedMediaType: 'PDF', displayFilename: 'form.docx' })

    const { outcome } = await run(wordFile('form.docx'))

    expect(refusal(outcome)).toBe('Brownie can fill Word (.docx) forms. It cannot fill a PDF.')
    expect(completeUpload).not.toHaveBeenCalled()
  })

  it.each([
    [{ status: 'REJECTED' as const, rejectionReason: 'MALWARE_DETECTED' }, 'Brownie did not accept "Club minutes.docx": the malware scan flagged it.'],
    [{ status: 'REJECTED' as const, rejectionReason: 'DECOMPRESSION_LIMIT_EXCEEDED' }, 'Brownie did not accept "Club minutes.docx": it unpacks to far more than Brownie accepts.'],
    [{ status: 'SCANNING' as const, rejectionReason: null }, 'Brownie could not finish checking "Club minutes.docx". Try again in a minute.'],
  ])('says why the scan did not accept the file (%#)', async (answer, sentence) => {
    vi.mocked(completeUpload).mockResolvedValue({ id: 5, displayFilename: 'Club minutes.docx', ...answer })

    const { outcome, steps } = await run()

    expect(refusal(outcome)).toBe(sentence)
    expect(steps).toEqual(['uploading', 'checking'])
    expect(createTemplateDraft).not.toHaveBeenCalled()
  })

  it('names what in the Word file Brownie cannot keep, once each, when the reader will not read it', async () => {
    vi.mocked(extractArtifact).mockResolvedValue({
      status: 'UNSUPPORTED',
      unsupportedFeatures: [
        { feature: 'TRACKED_CHANGES', location: 'body', detail: 'x' },
        { feature: 'TRACKED_CHANGES', location: 'body', detail: 'y' },
        { feature: 'UNRESOLVED_COMMENT', location: 'body', detail: 'z' },
        { feature: 'SOMETHING_NEW', location: 'body', detail: 'w' },
      ],
    } as Awaited<ReturnType<typeof extractArtifact>>)

    const { outcome } = await run()

    expect(refusal(outcome)).toBe(
      'Brownie cannot fill "Club minutes.docx" because it has tracked changes and comments. Remove those in Word, then upload the file again.',
    )
    expect(createTemplateDraft).not.toHaveBeenCalled()
  })

  it('says a Word file that could not be read at all may be damaged', async () => {
    vi.mocked(extractArtifact).mockResolvedValue({ status: 'FAILED', failureReason: 'PARSE_ERROR' })

    const { outcome } = await run()

    expect(refusal(outcome)).toBe(
      'Brownie could not read "Club minutes.docx". The file may be damaged: open it in Word, save it again, and upload it again.',
    )
  })

  it('explains that a Word file with no content controls cannot be filled, and adds nothing to My Templates', async () => {
    vi.mocked(getDraftCandidateBindings).mockResolvedValue({ candidates: [], ambiguousContentControlTags: [] })

    const { outcome } = await run()

    expect(refusal(outcome)).toBe(
      'Brownie found no content control with a tag in this Word file, and the tag is how Brownie knows which value goes where, ' +
        "so Brownie cannot fill it. In Word's Developer tab, add a content control where each value goes and give each one a tag " +
        'under Properties, then upload the file again.',
    )
    expect(replaceDraftBindings).not.toHaveBeenCalled()
    expect(activateTemplateVersion).not.toHaveBeenCalled()
    // Read once, for the names already taken; My Templates is never refreshed for a form that was not learned.
    expect(listTemplates).toHaveBeenCalledTimes(1)
  })

  /** Content controls are there, so "it has none" would be untrue: the trouble is that they cannot be told apart. */
  it('says so when every content control shares its tag with another', async () => {
    vi.mocked(getDraftCandidateBindings).mockResolvedValue({ candidates: [], ambiguousContentControlTags: ['client.name'] })

    const { outcome } = await run()

    expect(refusal(outcome)).toContain('Every content control in this Word file shares its tag with another one')
    expect(refusal(outcome)).not.toContain('has no content controls')
  })

  it('learns the rest of the form and says which shared tags it left out', async () => {
    vi.mocked(getDraftCandidateBindings).mockResolvedValue({ ...TWO_FIELDS, ambiguousContentControlTags: ['client.name', 'client.city'] })

    const { outcome } = await run()

    expect(outcome.ok).toBe(true)
    expect(outcome.ok && outcome.note).toBe(
      'Brownie did not learn "client.name" and "client.city". Each of those tags is on more than one content control in the form, ' +
        'so Brownie cannot tell which one a value belongs in. To fill them, give each content control a tag of its own in Word and upload the form again.',
    )
  })

  it.each([
    [
      1,
      'Brownie did not learn 1 content control that has no tag; it stays as it is in the form. ' +
        "To fill it, give it a tag under Properties in Word's Developer tab and upload the form again.",
    ],
    [
      3,
      'Brownie did not learn 3 content controls that have no tag; they stay as they are in the form. ' +
        "To fill them, give each one a tag under Properties in Word's Developer tab and upload the form again.",
    ],
  ])('learns the tagged controls and says how many without a tag it left as they are (%i)', async (count, note) => {
    vi.mocked(getDraftCandidateBindings).mockResolvedValue({ ...TWO_FIELDS, untaggedContentControlCount: count })

    const { outcome } = await run()

    expect(outcome).toEqual({ ok: true, documentId: 77, name: 'Club minutes', note })
  })

  it('puts the untagged note after the note about shared tags', async () => {
    vi.mocked(getDraftCandidateBindings).mockResolvedValue({
      ...TWO_FIELDS,
      ambiguousContentControlTags: ['client.name'],
      untaggedContentControlCount: 2,
    })

    const { outcome } = await run()

    expect(outcome.ok && outcome.note).toBe(
      'Brownie did not learn "client.name". That tag is on more than one content control in the form, so Brownie cannot tell which ' +
        'one a value belongs in. To fill it, give each content control a tag of its own in Word and upload the form again. ' +
        'Brownie did not learn 2 content controls that have no tag; they stay as they are in the form. ' +
        "To fill them, give each one a tag under Properties in Word's Developer tab and upload the form again.",
    )
  })

  /** Content controls without a tag cannot be learned, so a form with only those is still refused as having none with a tag. */
  it('still refuses a form whose content controls all have no tag', async () => {
    vi.mocked(getDraftCandidateBindings).mockResolvedValue({ candidates: [], ambiguousContentControlTags: [], untaggedContentControlCount: 4 })

    const { outcome } = await run()

    expect(refusal(outcome)).toContain('Brownie found no content control with a tag in this Word file')
    expect(replaceDraftBindings).not.toHaveBeenCalled()
  })

  /**
   * The filler never writes into a header or footer, and a field bound to a
   * control there stops the whole form from activating; the page the server
   * draws for the draft names those fields, and they are left out.
   */
  it('leaves out a content control outside the body of the form, and says which', async () => {
    vi.mocked(getTemplateLayout).mockResolvedValue({
      templateId: 42, versionId: 42, parserVersion: 'p', parts: [], unplacedFieldIds: ['action.item.due'],
    })
    vi.mocked(replaceDraftBindings).mockResolvedValueOnce(version(2)).mockResolvedValueOnce(version(3))
    vi.mocked(activateTemplateVersion).mockResolvedValue(version(3, 'ACTIVATED'))

    const { outcome } = await run()

    expect(replaceDraftBindings).toHaveBeenLastCalledWith(7, 42, 2, [
      { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', binding: { kind: 'CONTENT_CONTROL_TAG', tag: 'meeting.title' } },
    ])
    expect(activateTemplateVersion).toHaveBeenCalledWith(7, 42, 3)
    expect(outcome).toEqual({
      ok: true,
      documentId: 77,
      name: 'Club minutes',
      note:
        'Brownie did not learn "action.item.due": it is outside the body of the form, such as in a header or footer, ' +
        'and Brownie writes values only in the body.',
    })
  })

  it('refuses a form whose every content control is outside its body', async () => {
    vi.mocked(getTemplateLayout).mockResolvedValue({
      templateId: 42, versionId: 42, parserVersion: 'p', parts: [], unplacedFieldIds: ['meeting.title', 'action.item.due'],
    })

    const { outcome } = await run()

    expect(refusal(outcome)).toBe(
      'Every content control in this Word file is outside the body of the form, such as in a header or footer, and Brownie ' +
        'writes values only in the body, so Brownie cannot fill it.',
    )
    expect(activateTemplateVersion).not.toHaveBeenCalled()
  })

  /** A server older than the page it draws still learns the form; activation is then the only judge. */
  it('goes on without the page when the server cannot draw it', async () => {
    vi.mocked(getTemplateLayout).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'No static resource api/v1/workspaces/7/templates/42/versions/42/layout.', 'NOT_FOUND')),
    )

    const { outcome } = await run()

    expect(outcome.ok).toBe(true)
    expect(replaceDraftBindings).toHaveBeenCalledTimes(1)
  })

  /**
   * The server suggests a repeated value for every control in a table cell,
   * and then refuses to activate a form laid out as a table of labels and
   * boxes because only the last row of the first table can repeat.
   */
  it('activates a table-laid-out form with every value single when the server refuses its repeated row', async () => {
    vi.mocked(activateTemplateVersion)
      .mockRejectedValueOnce(
        new ApiRequestError(422, problem(422, 'No prototype paragraph found for repeated field action.item.due.', 'TEMPLATE_FILL_BINDING_NOT_FOUND')),
      )
      .mockResolvedValueOnce(version(3, 'ACTIVATED'))
    vi.mocked(replaceDraftBindings).mockResolvedValueOnce(version(2)).mockResolvedValueOnce(version(3))

    const { outcome } = await run()

    expect(outcome).toEqual({ ok: true, documentId: 77, name: 'Club minutes', note: null })
    expect(replaceDraftBindings).toHaveBeenLastCalledWith(7, 42, 2, [
      { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', binding: { kind: 'CONTENT_CONTROL_TAG', tag: 'meeting.title' } },
      { fieldId: 'action.item.due', type: 'DATE', cardinality: 'SCALAR', requiredness: 'OPTIONAL', binding: { kind: 'CONTENT_CONTROL_TAG', tag: 'action.item.due' } },
    ])
    expect(activateTemplateVersion).toHaveBeenLastCalledWith(7, 42, 3)
    expect(createDocument).toHaveBeenCalledWith(7, expect.any(String), expect.objectContaining({ templateVersionId: 43 }))
  })

  it('does not try again with single values when nothing was repeated to begin with', async () => {
    vi.mocked(getDraftCandidateBindings).mockResolvedValue({ candidates: [TWO_FIELDS.candidates[0]!], ambiguousContentControlTags: [] })
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
