import { searchForms, type FormEntry } from '@/api/client'

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
  const candidates = [...new Set([phrase, ...words.sort((a, b) => Number(/\d/.test(b)) - Number(/\d/.test(a)) || b.length - a.length)])]
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
