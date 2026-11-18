import { searchForms, type FormEntry } from '@/api/client'
import { decisionForQuery } from './formDecision'

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

/** The current library contains U.S. government PDFs only. */
export function formSourceUrl(form: FormEntry): string | undefined {
  try {
    const url = new URL(form.sourceUrl ?? '')
    return url.protocol === 'https:' && url.hostname.endsWith('.gov') && !url.username && !url.password ? url.href : undefined
  } catch { return undefined }
}

export function matchesPurpose(form: FormEntry, purposeId: FormPurposeId): boolean {
  return (FORM_PURPOSES.find((purpose) => purpose.id === purposeId)?.formIds as readonly string[] | undefined)?.includes(form.id) ?? false
}

// shortcut: each agency search is capped at 20; add pagination when an agency grows beyond that.
let catalogPromise: Promise<FormEntry[]> | undefined

function loadCatalog(): Promise<FormEntry[]> {
  if (!catalogPromise) {
    catalogPromise = Promise.all(['Internal Revenue Service', 'Citizenship and Immigration', 'Veterans Affairs', 'Department of State', 'Medicare'].map(searchForms))
      .then((groups) => [...new Map(groups.flat().map((form) => [form.id, form])).values()])
      .catch((error: unknown) => { catalogPromise = undefined; throw error })
  }
  return catalogPromise
}

export async function findFormsForPurpose(purposeId: FormPurposeId): Promise<FormEntry[]> {
  if (purposeId === 'driving') return []
  return (await loadCatalog()).filter((form) => matchesPurpose(form, purposeId))
}

export function matchingPurposes(query: string): FormPurposeId[] {
  const normalized = query.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
  return FORM_PURPOSES.filter((purpose) => purpose.searchTerms.some((term) =>
    (` ${normalized} `).includes(` ${term} `),
  )).map((purpose) => purpose.id)
}

const FILLER = new Set([
  'a', 'an', 'the', 'and', 'or', 'of', 'for', 'to', 'from', 'in', 'on', 'at', 'with',
  'i', 'me', 'my', 'we', 'our', 'you', 'your', 'is', 'are', 'what', 'which', 'how',
  'please', 'need', 'want', 'would', 'could', 'can', 'get', 'find', 'help', 'fill',
  'form', 'forms', 'document', 'documents', 'starting', 'start', 'new', 'apply', 'application',
])

const TASKS: { pattern: RegExp; ids: string[] }[] = [
  { pattern: /\b(?:new employee|employee onboarding|new hire|starting (?:a )?(?:new )?job|new job)\b/, ids: ['irs-w4', 'uscis-i9'] },
  { pattern: /\b(?:independent contractor|freelanc\w*|self employed|self employment)\b/, ids: ['irs-w9', 'irs-1040es'] },
  { pattern: /\b(?:employer payroll|payroll taxes|employer quarterly)\b/, ids: ['irs-941'] },
  { pattern: /\b(?:medicare enrollment|enroll in medicare|medicare part b)\b/, ids: ['cms-40b', 'cms-l564'] },
  { pattern: /\b(?:veterans? benefits|veterans? disability)\b/, ids: ['va-21-526ez', 'va-21-4138', 'va-21-0781'] },
]

function normalize(value: string): string {
  return value.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
}

// Only tolerate one edit in longer words, so short form numbers do not match unrelated forms.
function closeWord(a: string, b: string): boolean {
  if (a.length < 5 || b.length < 5 || Math.abs(a.length - b.length) > 1) return false
  if (a.length > b.length) return closeWord(b, a)
  let i = 0
  while (i < a.length && a[i] === b[i]) i++
  return a.slice(i + 1) === b.slice(i + 1) || a.slice(i) === b.slice(i + 1)
    || (a.length === b.length && a[i] === b[i + 1] && a[i + 1] === b[i] && a.slice(i + 2) === b.slice(i + 2))
}

/** Search the local catalog by form number, description, and a small set of common tasks. */
export async function findForms(query: string): Promise<FormEntry[]> {
  const forms = await loadCatalog()
  const text = normalize(query)
  if (!text) return forms
  if (decisionForQuery(text) || (/\bhealth(?:care| care| insurance)\b/.test(text) && !/\bmedicare\b/.test(text))) return []
  const task = TASKS.find(({ pattern }) => pattern.test(text))
  if (task) return task.ids.flatMap((id) => forms.filter((form) => form.id === id))

  const words = [...new Set(text.split(' ').filter((word) => word.length > 1 && !FILLER.has(word)))]
  const compactQuery = text.replace(/^form /, '').replace(/ /g, '')
  const embeddedCode = text.match(/\b(?:w|i|ds|ss|cms)\s*\d+[a-z]*\b/)?.[0].replace(/ /g, '')
  if (!words.length && !/\d/.test(compactQuery)) return []
  return forms.map((form, order) => {
    const id = normalize(form.id)
    const content = normalize(`${form.title} ${form.description} ${form.agency} ${form.category}`)
    const tokens = content.split(' ')
    const compactId = id.replace(/ /g, '')
    const code = compactId.replace(/^(?:irs|uscis|state|va|cms)/, '')
    const codeMatches = compactQuery === code || compactQuery === compactId || embeddedCode === code
    let score = codeMatches ? 100 : 0
    let matchedWords = 0
    for (const word of words) {
      if (tokens.includes(word)) { score += 5; matchedWords++ }
      else if (tokens.some((token) => closeWord(word, token))) { score += 2; matchedWords++ }
    }
    if (!codeMatches && matchedWords < words.length) score = 0
    return { form, order, score }
  }).filter(({ score }) => score > 0).sort((a, b) => b.score - a.score || a.order - b.order).map(({ form }) => form)
}
