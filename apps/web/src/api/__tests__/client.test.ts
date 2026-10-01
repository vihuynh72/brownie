import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiRequestError,
  activateTemplateVersion,
  getCurrentIdentity,
  changeFillSpots,
  createDocument,
  createTemplateDraft,
  executeAssist,
  getFillableForm,
  getTemplateLayout,
  keepFillSpot,
  keepFillSpots,
  listDocuments,
  listTrashedTemplates,
  interpretAssist,
  makeFillableForm,
  moveDocumentToTemplateVersion,
  onSessionEnded,
  restoreRevision,
  restoreTemplate,
  suggestBox,
  trashTemplate,
} from '@/api/client'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('api client', () => {
  afterEach(() => {
    onSessionEnded(null)
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 UTC'
  })

  it('sends credentials and no CSRF header on a GET', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { userId: 1, issuer: 'x', subject: 'y', memberships: [] }))
    vi.stubGlobal('fetch', fetchMock)

    await getCurrentIdentity()

    const [, init] = fetchMock.mock.calls[0]!
    expect(init.credentials).toBe('include')
    expect(init.headers['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('echoes the XSRF-TOKEN cookie as a request header on a mutation', async () => {
    document.cookie = 'XSRF-TOKEN=test-token-value'
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(201, {
        id: 1,
        title: 't',
        templateId: 1,
        templateVersionId: 1,
        currentRevisionId: 1,
        createdAt: '2026-01-01T00:00:00Z',
        currentRevision: { id: 1, revisionNumber: 1, fields: {}, contentHash: 'a'.repeat(64), editReason: 'r', createdAt: '2026-01-01T00:00:00Z' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await createDocument(1, 'key-1', { title: 't', templateId: 1, templateVersionId: 1, fields: {}, initialRevisionReason: 'r' })

    const [, init] = fetchMock.mock.calls[0]!
    expect(init.headers['X-XSRF-TOKEN']).toBe('test-token-value')
  })

  it('throws ApiRequestError with the server problem body on a non-2xx response', async () => {
    const problem = { status: 404, title: 'Not Found', code: 'NOT_FOUND', correlationId: 'abc', fields: [], recoveryActions: [] }
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(404, problem))))

    await expect(getCurrentIdentity()).rejects.toBeInstanceOf(ApiRequestError)
    await expect(getCurrentIdentity()).rejects.toMatchObject({ status: 404, problem, retryAfterSeconds: null })
  })

  it('keeps how long the server asked to wait before trying again, when it says so in seconds', async () => {
    const problem = { status: 503, title: 'Service Unavailable', code: 'RENDERER_BUSY', correlationId: 'abc', fields: [], recoveryActions: [] }
    const busy = (retryAfter: string) =>
      new Response(JSON.stringify(problem), { status: 503, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': retryAfter } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(busy('10')).mockResolvedValueOnce(busy('Wed, 21 Oct 2026 07:28:00 GMT')))

    await expect(makeFillableForm(7, 5)).rejects.toMatchObject({ status: 503, retryAfterSeconds: 10 })
    await expect(makeFillableForm(7, 5)).rejects.toMatchObject({ status: 503, retryAfterSeconds: null })
  })

  /**
   * What the owner's own machine showed: a page newer than the server behind it asked for a route the server did
   * not have. That is not "not found" in the sense a page acts on, and has to be told apart where it is caught.
   */
  it('tells a route the server does not have apart from a thing that is not there', () => {
    const noRoute = new ApiRequestError(404, {
      status: 404, title: 'Not Found', code: 'NOT_FOUND', detail: 'No static resource api/v1/data-practices.',
      correlationId: 'c', fields: [], recoveryActions: [],
    })
    const noDocument = new ApiRequestError(404, {
      status: 404, title: 'Not Found', code: 'NOT_FOUND', detail: 'Document 12 not found.',
      correlationId: 'c', fields: [], recoveryActions: [],
    })

    expect(noRoute.routeMissing).toBe(true)
    expect(noDocument.routeMissing).toBe(false)
    expect(new ApiRequestError(404, undefined).routeMissing).toBe(false)
    expect(new ApiRequestError(500, noRoute.problem).routeMissing).toBe(false)
  })

  it('reports a session that ended to whoever listens, except for the identity request, which reports its own', async () => {
    const ended = vi.fn()
    onSessionEnded(ended)
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(null, { status: 401 }))))

    await expect(getCurrentIdentity()).rejects.toMatchObject({ status: 401 })
    expect(ended).not.toHaveBeenCalled()

    await expect(listDocuments(7)).rejects.toMatchObject({ status: 401 })
    expect(ended).toHaveBeenCalledTimes(1)
  })

  it('reads a template version layout with a plain GET', async () => {
    const layout = { templateId: 3, versionId: 4, parserVersion: 'p', parts: [], unplacedFieldIds: [] }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, layout))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getTemplateLayout(2, 3, 4)).resolves.toEqual(layout)

    const [path, init] = fetchMock.mock.calls[0]!
    expect(path).toBe('/api/v1/workspaces/2/templates/3/versions/4/layout')
    expect(init.method).toBe('GET')
    expect(init.body).toBeUndefined()
  })

  it('asks where a box goes on a PDF page with a CSRF-checked POST carrying the page and the point', async () => {
    document.cookie = 'XSRF-TOKEN=box-token'
    const suggestion = {
      box: { x: 130, y: 88, width: 300, height: 14 }, style: { font: 'SANS', bold: false, sizePt: 11 }, labelGuess: 'Full name',
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, suggestion))
    vi.stubGlobal('fetch', fetchMock)

    await expect(suggestBox(2, 3, 4, { pageNumber: 1, point: { x: 140, y: 95 } })).resolves.toEqual(suggestion)

    const [path, init] = fetchMock.mock.calls[0]!
    expect(path).toBe('/api/v1/workspaces/2/templates/3/versions/4/box-suggestion')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({ pageNumber: 1, point: { x: 140, y: 95 } })
    expect(init.headers['X-XSRF-TOKEN']).toBe('box-token')
  })

  it('moves a template to the Trash Bin and back with CSRF-checked POSTs and no body', async () => {
    document.cookie = 'XSRF-TOKEN=trash-token'
    const template = {
      id: 3, displayName: 'Minutes', status: 'ACTIVE', currentActiveVersionId: 4,
      createdAt: '2026-01-01T00:00:00Z', trashedAt: '2026-09-29T10:00:00Z',
    }
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, template)))
    vi.stubGlobal('fetch', fetchMock)

    await expect(trashTemplate(2, 3)).resolves.toEqual(template)
    await restoreTemplate(2, 3)

    const [trashPath, trashInit] = fetchMock.mock.calls[0]!
    expect(trashPath).toBe('/api/v1/workspaces/2/templates/3/trash')
    expect(trashInit.method).toBe('POST')
    expect(trashInit.body).toBeUndefined()
    expect(trashInit.headers['X-XSRF-TOKEN']).toBe('trash-token')
    const [restorePath, restoreInit] = fetchMock.mock.calls[1]!
    expect(restorePath).toBe('/api/v1/workspaces/2/templates/3/restore')
    expect(restoreInit.method).toBe('POST')
    expect(restoreInit.headers['X-XSRF-TOKEN']).toBe('trash-token')
  })

  it('asks for the Trash Bin and keeps only templates that say when they were trashed', async () => {
    const trashed = {
      id: 5, displayName: 'Old minutes', status: 'ACTIVE', currentActiveVersionId: 6,
      createdAt: '2026-01-01T00:00:00Z', trashedAt: '2026-09-29T10:00:00Z',
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, [trashed]))
    vi.stubGlobal('fetch', fetchMock)

    await expect(listTrashedTemplates(2)).resolves.toEqual([trashed])

    const [path, init] = fetchMock.mock.calls[0]!
    expect(path).toBe('/api/v1/workspaces/2/templates?trashed=true')
    expect(init.method).toBe('GET')
  })

  /** A server that predates the Trash Bin ignores the question and sends its whole list; the page reads that as an older server. */
  it('passes on an older server\'s ordinary template list as it came, for the page to recognise', async () => {
    const untrashed = [
      { id: 5, displayName: 'Minutes', status: 'ACTIVE', currentActiveVersionId: 6, createdAt: '2026-01-01T00:00:00Z' },
      { id: 7, displayName: 'Notes', status: 'DRAFT', currentActiveVersionId: null, createdAt: '2026-01-02T00:00:00Z', trashedAt: null },
    ]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, untrashed)))

    await expect(listTrashedTemplates(2)).resolves.toEqual(untrashed)
  })

  it('restores a revision with the expected revision, the idempotency key and a reason only when one is given', async () => {
    document.cookie = 'XSRF-TOKEN=restore-token'
    const answer = {
      revision: { id: 9, revisionNumber: 4, fields: {}, contentHash: 'b'.repeat(64), editReason: 'Restored version 1.', createdAt: '2026-01-01T00:00:00Z' },
      keptLockedFieldIds: ['meeting.date'],
    }
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, answer)))
    vi.stubGlobal('fetch', fetchMock)

    await expect(restoreRevision(2, 5, 1, 8, 'key-restore')).resolves.toEqual(answer)
    await restoreRevision(2, 5, 1, 8, 'key-restore-2', 'Back to the first draft.')

    const [path, init] = fetchMock.mock.calls[0]!
    expect(path).toBe('/api/v1/workspaces/2/documents/5/revisions/1/restore')
    expect(init.method).toBe('POST')
    expect(init.headers['Idempotency-Key']).toBe('key-restore')
    expect(init.headers['X-XSRF-TOKEN']).toBe('restore-token')
    expect(JSON.parse(init.body)).toEqual({ expectedRevisionId: 8 })
    const [, withReason] = fetchMock.mock.calls[1]!
    expect(JSON.parse(withReason.body)).toEqual({ expectedRevisionId: 8, editReason: 'Back to the first draft.' })
  })

  it('makes an upload fillable with a CSRF-checked POST and reads what was made with a GET', async () => {
    document.cookie = 'XSRF-TOKEN=form-token'
    const form = { kind: 'DOCX', sourceArtifactId: 5, templateSourceArtifactId: 6, spots: [], notices: [] }
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(201, form)))
    vi.stubGlobal('fetch', fetchMock)

    await expect(makeFillableForm(2, 5)).resolves.toEqual(form)
    await expect(getFillableForm(2, 5)).resolves.toEqual(form)

    const [makePath, makeInit] = fetchMock.mock.calls[0]!
    expect(makePath).toBe('/api/v1/workspaces/2/artifacts/5/fillable-form')
    expect(makeInit.method).toBe('POST')
    expect(makeInit.body).toBeUndefined()
    expect(makeInit.headers['X-XSRF-TOKEN']).toBe('form-token')
    const [readPath, readInit] = fetchMock.mock.calls[1]!
    expect(readPath).toBe('/api/v1/workspaces/2/artifacts/5/fillable-form')
    expect(readInit.method).toBe('GET')
  })

  it('keeps one found spot by its escaped id, or several at once, and gets nothing back', async () => {
    document.cookie = 'XSRF-TOKEN=keep-token'
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })))
    vi.stubGlobal('fetch', fetchMock)

    await expect(keepFillSpot(2, 3, 'full.name')).resolves.toBeUndefined()
    await expect(keepFillSpots(2, 3, ['town', 'date'])).resolves.toBeUndefined()

    const [onePath, oneInit] = fetchMock.mock.calls[0]!
    expect(onePath).toBe('/api/v1/workspaces/2/templates/3/fields/full.name/review')
    expect(oneInit.method).toBe('PUT')
    expect(oneInit.headers['X-XSRF-TOKEN']).toBe('keep-token')
    expect(JSON.parse(oneInit.body)).toEqual({ decision: 'KEPT' })
    const [allPath, allInit] = fetchMock.mock.calls[1]!
    expect(allPath).toBe('/api/v1/workspaces/2/templates/3/field-reviews')
    expect(allInit.method).toBe('POST')
    expect(JSON.parse(allInit.body)).toEqual({ fieldIds: ['town', 'date'] })
  })

  it('asks to activate with no places only when told to', async () => {
    const version = { id: 4, templateId: 3, versionNumber: 1, status: 'ACTIVATED', fields: [] }
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(201, version)))
    vi.stubGlobal('fetch', fetchMock)

    await activateTemplateVersion(2, 3, 1)
    await activateTemplateVersion(2, 3, 1, { allowNoPlaces: true })

    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({ expectedVersionNumber: 1 })
    expect(JSON.parse(fetchMock.mock.calls[1]![1].body)).toEqual({ expectedVersionNumber: 1, allowNoPlaces: true })
  })

  it('keeps the upload\'s notes with a new template only when they are given', async () => {
    const draft = { template: { id: 3 }, draftVersion: { id: 4 } }
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(201, draft)))
    vi.stubGlobal('fetch', fetchMock)

    await createTemplateDraft(2, 'Sign-up', 9)
    await createTemplateDraft(2, 'Sign-up', 9, [{ code: 'PLACES_LEFT_OUT', count: 1, detail: null }])

    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({ displayName: 'Sign-up', sourceArtifactId: 9 })
    expect(JSON.parse(fetchMock.mock.calls[1]![1].body)).toEqual({
      displayName: 'Sign-up',
      sourceArtifactId: 9,
      preparationNotices: [{ code: 'PLACES_LEFT_OUT', count: 1, detail: null }],
    })
  })

  it('sends the place selected on the page with a request to Brownie only when there is one', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, { kind: 'NONE', summary: 's', executable: false, usesModel: false, help: [] })))
    vi.stubGlobal('fetch', fetchMock)
    const anchor = {
      part: 'MAIN_DOCUMENT' as const,
      paragraphNodeId: 'p3',
      placement: 'AT' as const,
      start: 8,
      end: 8,
      anchorTextHash: 'h',
      parserVersion: 'brownie-docx-graph-v3+poi-5.5.1',
      controlNodeId: null,
    }

    await interpretAssist(2, 5, 'add a fill spot for Company here')
    await interpretAssist(2, 5, 'add a fill spot for Company here', anchor)
    await executeAssist(2, 5, 'add a fill spot for Company here', 9, anchor)
    await executeAssist(2, 5, 'change title to x', 9, null)

    expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/workspaces/2/documents/5/assist/interpret')
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({ text: 'add a fill spot for Company here' })
    expect(JSON.parse(fetchMock.mock.calls[1]![1].body)).toEqual({ text: 'add a fill spot for Company here', pageAnchor: anchor })
    expect(fetchMock.mock.calls[2]![0]).toBe('/api/v1/workspaces/2/documents/5/assist/execute')
    expect(JSON.parse(fetchMock.mock.calls[2]![1].body)).toEqual({ text: 'add a fill spot for Company here', expectedRevisionId: 9, pageAnchor: anchor })
    expect(JSON.parse(fetchMock.mock.calls[3]![1].body)).toEqual({ text: 'change title to x', expectedRevisionId: 9 })
  })

  it('changes fill spots and moves a document to a version with an idempotency key', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, {})))
    vi.stubGlobal('fetch', fetchMock)

    await changeFillSpots(2, 5, 9, 4, [{ kind: 'RENAME', fieldId: 'company', label: 'Employer' }], 'key-1')
    await moveDocumentToTemplateVersion(2, 5, 9, 6, 'key-2')

    expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/workspaces/2/documents/5/fill-spots')
    expect(fetchMock.mock.calls[0]![1].headers['Idempotency-Key']).toBe('key-1')
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({
      expectedRevisionId: 9,
      templateVersionId: 4,
      changes: [{ kind: 'RENAME', fieldId: 'company', label: 'Employer' }],
    })
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/v1/workspaces/2/documents/5/template-version')
    expect(fetchMock.mock.calls[1]![1].headers['Idempotency-Key']).toBe('key-2')
    expect(JSON.parse(fetchMock.mock.calls[1]![1].body)).toEqual({ expectedRevisionId: 9, templateVersionId: 6 })
  })
})
