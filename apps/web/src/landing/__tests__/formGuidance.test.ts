import { describe, expect, it } from 'vitest'
import { guidanceForQuery } from '../formGuidance'

describe('official form guidance', () => {
  it.each(['renew my passport', 'my passport is expired', 'renew'])('routes %s to passport renewal instructions', (query) => {
    const guidance = guidanceForQuery(query, 'travel')
    expect(guidance?.url).toBe('https://travel.state.gov/en/passports/renew-replace.html')
    expect(guidance?.excludeFormIds).toContain('state-ds11')
    expect(guidance?.description).toContain('new application')
  })

  it('prioritizes lost passport guidance even if renewal is mentioned', () => {
    const guidance = guidanceForQuery('renew my lost passport')
    expect(guidance?.url).toContain('report-passport-lost-stolen')
    expect(guidance?.excludeFormIds).toBeUndefined()
  })

  it('offers state selection for driving and does not mistake a license renewal for a passport', () => {
    expect(guidanceForQuery('I need to drive')?.url).toBe('https://www.usa.gov/state-motor-vehicle-services')
    expect(guidanceForQuery('renew my license', 'driving')?.url).toBe('https://www.usa.gov/state-motor-vehicle-services')
    expect(guidanceForQuery('', 'driving')?.linkLabel).toContain('state')
    expect(guidanceForQuery('renew driver license', 'travel')?.url).toBe('https://www.usa.gov/state-motor-vehicle-services')
  })

  it('offers worker classification guidance without hiding either form', () => {
    const guidance = guidanceForQuery('starting a new job')
    expect(guidance?.url).toBe('https://www.irs.gov/pub/irs-pdf/p1779.pdf')
    expect(guidance?.excludeFormIds).toBeUndefined()
  })

  it('leaves unrelated and generic passport searches alone', () => {
    expect(guidanceForQuery('passport application')).toBeUndefined()
    expect(guidanceForQuery('tax transcript')).toBeUndefined()
  })
})
