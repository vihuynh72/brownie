import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiRequestError, getCurrentIdentity, createDocument } from '@/api/client'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('api client', () => {
  afterEach(() => {
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
})
