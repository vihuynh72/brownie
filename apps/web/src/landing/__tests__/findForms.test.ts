import { beforeEach, describe, expect, it, vi } from 'vitest'
import { searchForms } from '@/api/client'
import catalog from '@/test/form-catalog.json'

vi.mock('@/api/client', () => ({ searchForms: vi.fn() }))

beforeEach(() => {
  vi.resetModules()
  vi.mocked(searchForms).mockReset().mockImplementation(async (query) => {
    const needle = query.trim().toLowerCase()
    return catalog.filter((form) => [form.title, form.agency, ...form.keywords].some((value) => value.toLowerCase().includes(needle))).slice(0, 20)
  })
})

describe('catalog discovery', () => {
  it('loads all catalog entries once, including the entry beyond the default limit', async () => {
    const { findForms } = await import('../findForms')
    const forms = await findForms('')
    expect(forms.map((form) => form.id).sort()).toEqual(catalog.map((form) => form.id).sort())
    expect(forms.map((form) => form.id)).toContain('cms-1696')
    await findForms('passport')
    expect(vi.mocked(searchForms)).toHaveBeenCalledTimes(5)
  })

  it('shares concurrent requests and retries a failed load', async () => {
    const { findForms, findFormsForPurpose } = await import('../findForms')
    vi.mocked(searchForms).mockRejectedValueOnce(new Error('offline'))
    await expect(Promise.all([findForms('passport'), findFormsForPurpose('health')])).rejects.toThrow('offline')
    expect(vi.mocked(searchForms)).toHaveBeenCalledTimes(5)
    expect((await findFormsForPurpose('health')).map((form) => form.id)).toContain('cms-1696')
    expect(vi.mocked(searchForms)).toHaveBeenCalledTimes(10)
    await Promise.all([findForms('passport'), findFormsForPurpose('taxes')])
    expect(vi.mocked(searchForms)).toHaveBeenCalledTimes(10)
  })

  it('loads each purpose from the same index', async () => {
    const { findFormsForPurpose, FORM_PURPOSES, matchingPurposes } = await import('../findForms')
    for (const purpose of FORM_PURPOSES) {
      expect((await findFormsForPurpose(purpose.id)).map((form) => form.id).sort()).toEqual([...purpose.formIds].sort())
    }
    expect(vi.mocked(searchForms)).toHaveBeenCalledTimes(5)
    expect(matchingPurposes('I need to drive')).toContain('driving')
    expect(matchingPurposes('network access')).not.toContain('work')
  })

  it.each([
    ['W9', ['irs-w9']], ['W-9', ['irs-w9']], ['irs-w9', ['irs-w9']], ['please find form W-9', ['irs-w9']],
    ['1040-ES', ['irs-1040es']], ['passport', ['state-ds11']], ['passpor', ['state-ds11']], ['passprot', ['state-ds11']],
    ['new employee onboarding', ['irs-w4', 'uscis-i9']],
    ['independent contractor', ['irs-w9', 'irs-1040es']],
    ['employer payroll', ['irs-941']],
    ['Medicare enrollment', ['cms-40b', 'cms-l564']],
    ['veterans benefits', ['va-21-526ez', 'va-21-4138', 'va-21-0781']],
  ])('finds relevant forms for %s', async (query, expected) => {
    const { findForms } = await import('../findForms')
    expect((await findForms(query)).map((form) => form.id).slice(0, expected.length)).toEqual(expected)
  })

  it.each(['starting a job', 'health insurance', 'health insurance marketplace', 'volcano telescope', 'tax volcano', 'I need to drive'])('does not suggest unrelated forms for %s', async (query) => {
    const { findForms } = await import('../findForms')
    expect(await findForms(query)).toEqual([])
  })
})
