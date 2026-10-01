import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { documentTitleFor, useTemplatesStore } from '@/stores/templates'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, listTemplates: vi.fn() }
})

import { listTemplates, type TemplateResponse } from '@/api/client'

function template(id: number, overrides: Partial<TemplateResponse> = {}): TemplateResponse {
  return {
    id,
    displayName: `Template ${id}`,
    status: 'ACTIVE',
    currentActiveVersionId: id * 10,
    createdAt: '2026-09-01T10:00:00Z',
    trashedAt: null,
    ...overrides,
  }
}

describe('templates store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listTemplates).mockReset()
  })

  it('offers only templates that can start a document and are not in the Trash Bin', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      template(1),
      template(2, { status: 'DRAFT', currentActiveVersionId: null }),
      template(3, { trashedAt: '2026-09-29T12:00:00Z' }),
    ])
    const store = useTemplatesStore()

    await store.load(7)

    expect(listTemplates).toHaveBeenCalledWith(7)
    expect(store.usable.map((candidate) => candidate.id)).toEqual([1])
  })

  /** An older server's templates carry no trashedAt at all; none of them is in a Trash Bin it does not have. */
  it('keeps templates from a server that says nothing about the Trash Bin', async () => {
    const older = template(1)
    delete older.trashedAt
    vi.mocked(listTemplates).mockResolvedValue([older])
    const store = useTemplatesStore()

    await store.load(7)

    expect(store.usable.map((candidate) => candidate.id)).toEqual([1])
  })

  it('loads once, and says "loading" only the first time', async () => {
    let answer: (templates: TemplateResponse[]) => void = () => {}
    vi.mocked(listTemplates).mockReturnValue(new Promise((resolve) => (answer = resolve)))
    const store = useTemplatesStore()

    const first = store.load(7)
    expect(store.status).toBe('loading')
    answer([template(1)])
    await first
    expect(store.status).toBe('loaded')

    await store.load(7)
    expect(listTemplates).toHaveBeenCalledTimes(1)

    // A refresh of a list already on screen keeps it there while the new one comes.
    vi.mocked(listTemplates).mockReturnValue(new Promise((resolve) => (answer = resolve)))
    const again = store.refresh(7)
    expect(store.status).toBe('loaded')
    expect(store.usable.map((candidate) => candidate.id)).toEqual([1])
    answer([template(1), template(2)])
    await again
    expect(store.usable.map((candidate) => candidate.id)).toEqual([1, 2])
  })

  it('reports a failed load as a status rather than throwing', async () => {
    vi.mocked(listTemplates).mockRejectedValue(new TypeError('Failed to fetch'))
    const store = useTemplatesStore()

    await store.refresh(7)

    expect(store.status).toBe('error')
  })

  it('keeps the list it already has when a later refresh fails', async () => {
    vi.mocked(listTemplates).mockResolvedValueOnce([template(1)]).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const store = useTemplatesStore()
    await store.load(7)

    await store.refresh(7)

    expect(store.status).toBe('loaded')
    expect(store.templates.map((kept) => kept.id)).toEqual([1])
  })

  it('forgets a template the server has put in the Trash Bin, without asking it again', async () => {
    vi.mocked(listTemplates).mockResolvedValue([template(1), template(2)])
    const store = useTemplatesStore()
    await store.load(7)

    store.forget(1)

    expect(store.usable.map((candidate) => candidate.id)).toEqual([2])
    expect(listTemplates).toHaveBeenCalledTimes(1)
  })

  /** A Trash Bin page open at the same time watches the count; forgetting alone would leave it listing what it had. */
  it('counts every template moved to the Trash Bin from here, and forgets each one', async () => {
    vi.mocked(listTemplates).mockResolvedValue([template(1), template(2), template(3)])
    const store = useTemplatesStore()
    await store.load(7)
    expect(store.trashedSerial).toBe(0)

    store.markTrashed(1)
    expect(store.usable.map((candidate) => candidate.id)).toEqual([2, 3])
    expect(store.trashedSerial).toBe(1)

    store.markTrashed(3)
    expect(store.usable.map((candidate) => candidate.id)).toEqual([2])
    expect(store.trashedSerial).toBe(2)
    expect(listTemplates).toHaveBeenCalledTimes(1)
  })
})

describe('documentTitleFor', () => {
  it('names a document after its template and the day, the way dates read everywhere else in Brownie', () => {
    const day = new Date(2026, 8, 29, 15, 0)

    expect(documentTitleFor('Club minutes', day)).toBe(
      `Club minutes, ${new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(day)}`,
    )
  })

  it('uses today when no day is given', () => {
    expect(documentTitleFor('Club minutes')).toBe(
      `Club minutes, ${new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date())}`,
    )
  })
})
