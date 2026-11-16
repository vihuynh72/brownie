import { beforeEach, describe, expect, it, vi } from 'vitest'
import { searchForms, type FormEntry } from '@/api/client'
import catalog from '@/test/form-catalog.json'
import { findForms, findFormsForPurpose, FORM_PURPOSES, matchingPurposes, matchesPurpose } from '../findForms'

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


describe('catalog purposes', () => {
  beforeEach(() => {
    vi.mocked(searchForms).mockImplementation(async (query) => {
      const needle = query.trim().toLowerCase()
      return catalog.filter((form) => [form.title, form.agency, ...form.keywords].some((value) => value.toLowerCase().includes(needle))).slice(0, 20)
    })
  })

  it('classifies real catalog entries and never invents a driving form', () => {
    for (const purpose of FORM_PURPOSES) {
      for (const id of purpose.formIds) expect(catalog.some((form) => form.id === id)).toBe(true)
    }
    expect(catalog.filter((form) => matchesPurpose(form, 'work')).map((form) => form.id)).toContain('uscis-i9')
    expect(catalog.filter((form) => matchesPurpose(form, 'driving'))).toEqual([])
    expect(matchingPurposes('I need to drive')).toContain('driving')
    expect(matchingPurposes('renew my license')).toContain('driving')
    expect(matchingPurposes('starting a new job')).toContain('work')
    expect(matchingPurposes('network access')).not.toContain('work')
  })

  it.each(FORM_PURPOSES)('loads every catalog form for $label within four requests', async (purpose) => {
    const results = await findFormsForPurpose(purpose.id)
    expect(results.map((form) => form.id).sort()).toEqual([...purpose.formIds].sort())
    expect(vi.mocked(searchForms).mock.calls.length).toBeLessThanOrEqual(4)
  })

  it('loads the Medicare form omitted by the capped blank search', async () => {
    expect((await searchForms('')).some((form) => form.id === 'cms-1696')).toBe(false)
    expect((await findFormsForPurpose('health')).map((form) => form.id)).toContain('cms-1696')
  })

  it.each([
    ['starting a new job', ['irs-w4', 'uscis-i9']],
    ['I need healthcare', ['cms-40b', 'cms-l564', 'cms-1696']],
    ['I need a passport', ['state-ds11']],
    ['veterans benefits', ['va-21-4138', 'va-21-526ez', 'va-21-0781']],
  ])('finds real forms for %s', async (query, expected) => {
    expect((await findForms(query)).map((form) => form.id)).toEqual(expect.arrayContaining(expected))
    expect(vi.mocked(searchForms).mock.calls.length).toBeLessThanOrEqual(4)
  })
})
