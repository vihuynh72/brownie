import { beforeEach, describe, expect, it, vi } from 'vitest'
import { searchForms, type FormEntry } from '@/api/client'
import { findForms } from '../findForms'

vi.mock('@/api/client', () => ({ searchForms: vi.fn() }))
const entry = (id: string): FormEntry => ({ id, title: id, agency: 'Agency', category: 'Forms', description: id })
const passport = entry('DS-11')
const w4 = entry('W-4')
const i9 = entry('I-9')
const estimated = entry('1040-ES')
const tax = entry('tax-other')

beforeEach(() => { vi.mocked(searchForms).mockReset() })

describe('findForms', () => {
  it('returns popular or exact matches without fallback', async () => {
    vi.mocked(searchForms).mockResolvedValue([passport])
    expect(await findForms('')).toEqual([passport])
    expect(await findForms(' W-9 ')).toEqual([passport])
    expect(vi.mocked(searchForms).mock.calls).toEqual([[''], ['W-9']])
  })

  it.each([
    ['I need a passport', { passport: [passport] }, ['DS-11']],
    ['starting a new job', { 'new job': [w4, i9], job: [w4, i9] }, ['W-4', 'I-9']],
    ['self-employed tax form', { 'self-employed': [estimated], tax: [estimated, tax] }, ['1040-ES', 'tax-other']],
  ])('finds candidates for %s using catalog keywords', async (query, lookup, expected) => {
    vi.mocked(searchForms).mockImplementation(async (term) => (lookup as Record<string, FormEntry[]>)[term] ?? [])
    expect((await findForms(query)).map((form) => form.id)).toEqual(expected)
    expect(vi.mocked(searchForms).mock.calls.length).toBeLessThanOrEqual(4)
  })

  it('preserves hyphenated form codes and bounds distinct fallback requests', async () => {
    vi.mocked(searchForms).mockImplementation(async (term) => term === 'ds-11' ? [passport] : [])
    expect(await findForms('please find DS-11 application passport renewal')).toEqual([passport])
    const calls = vi.mocked(searchForms).mock.calls.map(([term]) => term)
    expect(calls).toContain('ds-11')
    expect(calls.length).toBeLessThanOrEqual(4)
    expect(new Set(calls).size).toBe(calls.length)
  })

  it('deduplicates and ranks matches from more specific result sets first, stably', async () => {
    vi.mocked(searchForms).mockImplementation(async (term) => ({ 'alpha beta': [w4, i9], alpha: [i9], beta: [w4, i9, tax] })[term] ?? [])
    expect((await findForms('find alpha beta')).map((form) => form.id)).toEqual(['I-9', 'W-4', 'tax-other'])
  })

  it('propagates a failed lookup instead of reporting no matches', async () => {
    vi.mocked(searchForms).mockImplementation(async (term) => {
      if (term === 'I need a passport') return []
      throw new Error('offline')
    })
    const result = await findForms('I need a passport').then(() => 'resolved', (error: Error) => error.message)
    expect(result).toBe('offline')
  })
})
