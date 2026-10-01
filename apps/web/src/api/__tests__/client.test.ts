import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiRequestError,
  getCurrentIdentity,
  createDocument,
  getTemplateLayout,
  listDocuments,
  listTrashedTemplates,
  onSessionEnded,
  restoreRevision,
  restoreTemplate,
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
    await expect(getCurrentIdentity()).rejects.toMatchObject({ status: 404, problem })
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
})
