import { searchForms, type FormEntry } from '@/api/client'

// shortcut: topics use curated catalog IDs; derive server-side purpose metadata when the catalog grows.
export const FORM_PURPOSES = [
  { id: 'driving', label: 'Driving & vehicles', searchTerms: ['driver', 'driving', 'drive', 'license', 'licence', 'dmv', 'vehicle', 'car registration', 'real id'], formIds: [], handoffUrl: 'https://www.usa.gov/state-motor-vehicle-services' },
  { id: 'work', label: 'Work & employment', searchTerms: ['new job', 'employment', 'work', 'job', 'new hire', 'payroll'], formIds: ['irs-w4', 'uscis-i9', 'irs-w9', 'irs-941'] },
  { id: 'taxes', label: 'Taxes', searchTerms: ['tax', 'taxes', 'irs', 'withholding', 'itin'], formIds: ['irs-w9', 'irs-w4', 'irs-w4p', 'irs-ss4', 'irs-4506t', 'irs-8822', 'irs-2848', 'irs-8821', 'irs-14039', 'irs-1040es', 'irs-941', 'irs-4868', 'irs-w7'] },
  { id: 'travel', label: 'Passports & travel', searchTerms: ['passport', 'travel'], formIds: ['state-ds11'] },
  { id: 'health', label: 'Medicare & health', searchTerms: ['medicare', 'healthcare', 'health care', 'health insurance'], formIds: ['cms-40b', 'cms-l564', 'cms-1696'] },
  { id: 'veterans', label: 'Veterans benefits', searchTerms: ['veteran', 'veterans', 'va benefits', 'va disability'], formIds: ['va-21-4138', 'va-21-526ez', 'va-21-0781'] },
] as const

export type FormPurposeId = typeof FORM_PURPOSES[number]['id']

export function matchesPurpose(form: FormEntry, purposeId: FormPurposeId): boolean {
  return (FORM_PURPOSES.find((purpose) => purpose.id === purposeId)?.formIds as readonly string[] | undefined)?.includes(form.id) ?? false
}

// Search targeted catalog fields so the default 20-result limit does not hide a topic's forms.
export async function findFormsForPurpose(purposeId: FormPurposeId): Promise<FormEntry[]> {
  const queries: Record<FormPurposeId, string[]> = {
    driving: [],
    work: ['new job', 'contractor', '941'],
    taxes: ['Internal Revenue Service'],
    travel: ['passport'],
    health: ['medicare'],
    veterans: ['Veterans Affairs'],
  }
  const answers = await Promise.all(queries[purposeId].map((query) => searchForms(query)))
  const entries = new Map<string, FormEntry>()
  for (const forms of answers) {
    for (const form of forms) {
      if (matchesPurpose(form, purposeId)) entries.set(form.id, form)
    }
  }
  return [...entries.values()]
}

export function matchingPurposes(query: string): FormPurposeId[] {
  const normalized = query.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
  return FORM_PURPOSES.filter((purpose) => purpose.searchTerms.some((term) =>
    (` ${normalized} `).includes(` ${term} `),
  )).map((purpose) => purpose.id)
}

// These words describe the request rather than which catalog entry to find.
const FILLER = new Set([
  'a', 'an', 'the', 'and', 'or', 'of', 'for', 'to', 'from', 'in', 'on', 'at', 'with',
  'i', 'me', 'my', 'we', 'our', 'you', 'your', 'is', 'are', 'what', 'which', 'how',
  'please', 'need', 'want', 'would', 'could', 'can', 'get', 'find', 'help', 'fill',
  'form', 'forms', 'document', 'documents',
])

/** Search the catalog as written first, then try a bounded set of meaningful keywords. */
export async function findForms(query: string): Promise<FormEntry[]> {
  const direct = query.trim()
  const exact = await searchForms(direct)
  if (!direct || exact.length) return exact

  const words = [...new Set((direct.toLowerCase().match(/[\p{L}\p{N}]+(?:-[\p{L}\p{N}]+)*/gu) ?? [])
    .filter((word) => word.length > 1 && !FILLER.has(word)))]
  const phrase = words.length > 1 ? words.slice(-2).join(' ') : ''
  const aliases = /\b(?:start(?:ing|ed)?|begin(?:ning)?|new)\b.*\bjob\b|\bnew hire\b/i.test(direct) ? ['new job']
    : /\bhealth\s*care\b|\bhealth insurance\b/i.test(direct) ? ['medicare'] : []
  const candidates = [...new Set([...aliases, phrase, ...words.sort((a, b) => Number(/\d/.test(b)) - Number(/\d/.test(a)) || b.length - a.length)])]
    .filter((term) => term && term !== direct.toLowerCase()).slice(0, 3)
  const answers = await Promise.all(candidates.map((term) => searchForms(term)))
  const ranked = new Map<string, { entry: FormEntry; score: number; order: number }>()
  for (const forms of answers) {
    // A match from a smaller result set is more specific; ties retain catalog response order.
    const weight = 1 / Math.max(forms.length, 1)
    for (const form of forms) {
      const previous = ranked.get(form.id)
      if (previous) previous.score += weight
      else ranked.set(form.id, { entry: form, score: weight, order: ranked.size })
    }
  }
  return [...ranked.values()].sort((a, b) => b.score - a.score || a.order - b.order).map(({ entry }) => entry)
}
