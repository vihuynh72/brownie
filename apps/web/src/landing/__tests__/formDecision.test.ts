import { describe, expect, it } from 'vitest'
import { decisionForQuery } from '../formDecision'

describe('discovery choices', () => {
  it('asks what kind of work for a generic job search', () => {
    const result = decisionForQuery('starting a job')
    expect(result?.choices.map((choice) => choice.query)).toEqual(['new employee onboarding', 'independent contractor', 'employer payroll'])
    expect(decisionForQuery('independent contractor')).toBeUndefined()
  })
  it('does not assume Medicare for health insurance', () => {
    expect(decisionForQuery('I need health insurance')?.choices.map((choice) => choice.query)).toEqual(['Medicare enrollment', 'health insurance marketplace'])
    expect(decisionForQuery('Medicare enrollment')).toBeUndefined()
    expect(decisionForQuery('health insurance marketplace')).toBeUndefined()
  })
})
