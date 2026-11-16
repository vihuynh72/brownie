import type { FormPurposeId } from './findForms'

export type FormGuidance = {
  title: string
  description: string
  url: string
  linkLabel: string
  excludeFormIds?: string[]
}

/** Direct people to the agency's instructions when the right form depends on their situation. */
export function guidanceForQuery(query: string, purposeId?: FormPurposeId): FormGuidance | undefined {
  const text = query.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ')
  const passport = /\bpassports?\b/.test(text) || (purposeId === 'travel' && /\b(?:renew\w*|expir\w*|lost|stolen)\b/.test(text) && !/\b(?:license|licence|dmv|driver|vehicle)\b/.test(text))

  if (passport && /\b(?:lost|stolen|steal|missing)\b/.test(text)) return {
    title: 'Lost or stolen passport',
    description: 'The State Department explains how to report a lost or stolen passport and apply for a replacement.',
    url: 'https://travel.state.gov/en/passports/renew-replace/report-passport-lost-stolen.html',
    linkLabel: 'See lost passport instructions',
  }
  if (passport && /\b(?:renew\w*|expir\w*)\b/.test(text)) return {
    title: 'Renew or replace a passport',
    description: 'The right process depends on your circumstances. The State Department explains renewal and when a new application is needed.',
    url: 'https://travel.state.gov/en/passports/renew-replace.html',
    linkLabel: 'See passport renewal options',
    excludeFormIds: ['state-ds11'],
  }
  if (purposeId === 'driving' || /\b(?:dmv|driv(?:e|er|ers|ing)|vehicle|vehicles|real id)\b/.test(text)) return {
    title: 'Find your state motor vehicle agency',
    description: 'Driver licensing and vehicle services depend on your state or territory. Choose yours on USAGov to find the right agency.',
    url: 'https://www.usa.gov/state-motor-vehicle-services',
    linkLabel: 'Choose your state or territory',
  }
  if (purposeId === 'work' || /\b(?:job|employment|employee|contractor|freelance)\b/.test(text)) return {
    title: 'Employee or independent contractor?',
    description: 'Employment status affects which tax forms apply. Review the IRS guidance and check with the organization requesting the form.',
    url: 'https://www.irs.gov/pub/irs-pdf/p1779.pdf',
    linkLabel: 'Read IRS worker classification guidance',
  }
  return undefined
}
