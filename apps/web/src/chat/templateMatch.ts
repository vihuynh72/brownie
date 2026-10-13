import type { TemplateResponse } from '@/api/client'

/** The two things about a template that a suggestion looks at. */
type NamedTemplate = Pick<TemplateResponse, 'displayName' | 'createdAt'>

interface TemplateSuggestion<T extends NamedTemplate> {
  /** Every template given, the best fit first. */
  ranked: T[]
  /**
   * How much the first of `ranked` is worth trusting. 'clear': its name shares the most words with the request
   * and no other name shares as many. 'tied': two or more names share the same most words, so the first was
   * picked among equals. 'none': no name shares a single word, so the first is only the newest template.
   */
  certainty: 'clear' | 'tied' | 'none'
}

/**
 * Words that say what is being asked rather than what the document is, and words every template could carry. A
 * request is full of them ("make", "from my") and none says which template it means, so they never count as a
 * match. Listed as they read after `stem`.
 */
const FILLER = new Set([
  'a', 'an', 'the', 'and', 'or', 'of', 'for', 'to', 'from', 'in', 'on', 'at', 'by', 'with', 'into', 'about', 'as',
  'is', 'are', 'be', 'it', 'its', 'this', 'that', 'these', 'those',
  'i', 'me', 'my', 'mine', 'we', 'us', 'our', 'you', 'your',
  'please', 'thank', 'thanks', 'need', 'want', 'would', 'could', 'can', 'will', 'should', 'let',
  'make', 'made', 'create', 'start', 'begin', 'fill', 'filled', 'write', 'draft', 'prepare', 'build', 'generate',
  'new', 'use', 'using', 'do', 'get',
  'form', 'document', 'template', 'file', 'doc',
])

/**
 * Takes a plural off a word the simple ways English makes one, so "minutes" and "minute", or "policies" and
 * "policy", read as the same word. It is applied to both sides of every comparison, so a word it gets wrong is
 * still wrong the same way on each side and still matches itself.
 */
function stem(word: string): string {
  if (word.length <= 3) return word
  if (word.endsWith('ies') && word.length > 4) return `${word.slice(0, -3)}y`
  if (/(ch|sh|ss|x|z|us)es$/.test(word)) return word.slice(0, -2)
  if (/(ss|us|is)$/.test(word)) return word
  return word.endsWith('s') ? word.slice(0, -1) : word
}

/** The meaningful words of a sentence or a template's name: lower case, no filler, plurals made singular. */
function wordsOf(text: string): string[] {
  // "Tuesday's" is the word Tuesday, and a typographic apostrophe is the same mark as a plain one.
  const plain = text.toLowerCase().replace(/['’]s\b/g, '').replace(/['’]/g, '')
  return (plain.match(/[\p{L}\p{N}]+/gu) ?? []).map(stem).filter((word) => !FILLER.has(word))
}

/**
 * Orders templates by how many of the words in their names the request also uses, best first. This compares
 * words and nothing else: it cannot tell what a template is for, only what it is called, which is why the
 * answer says how sure it is.
 *
 * Templates that share as many words keep the order they were given in. When no name shares a word at all
 * there is nothing to go on, so the newest template comes first: the one someone last taught Brownie is the
 * likeliest to be the one they mean.
 */
export function suggestTemplates<T extends NamedTemplate>(request: string, templates: readonly T[]): TemplateSuggestion<T> {
  const asked = new Set(wordsOf(request))
  const scored = templates.map((template, index) => ({
    template,
    index,
    score: new Set(wordsOf(template.displayName).filter((word) => asked.has(word))).size,
    // A date that cannot be read counts as the oldest, so it is never mistaken for the newest.
    created: Date.parse(template.createdAt) || 0,
  }))
  const best = Math.max(0, ...scored.map((entry) => entry.score))
  const ranked =
    best === 0
      ? [...scored].sort((a, b) => b.created - a.created || a.index - b.index)
      : [...scored].sort((a, b) => b.score - a.score || a.index - b.index)
  const atBest = scored.filter((entry) => entry.score === best).length
  return {
    ranked: ranked.map((entry) => entry.template),
    certainty: best === 0 ? 'none' : atBest > 1 ? 'tied' : 'clear',
  }
}
