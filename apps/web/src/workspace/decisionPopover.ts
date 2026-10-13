import type { IconName } from '@/components/icons'

/**
 * The small decisions behind the card that opens over the page for a fill spot: how each word of a value's state
 * is drawn as a chip, and which spot "Next to review" goes to.
 */

/** A place on the page a decision can be recorded for: a field's one spot, or one row of a table. */
export interface ReviewTarget {
  fieldId: string
  rowIndex: number | null
}

function sameTarget(left: ReviewTarget, right: ReviewTarget): boolean {
  return left.fieldId === right.fieldId && left.rowIndex === right.rowIndex
}

/**
 * The spot to go to after `current`: the next one down the page that `wantsReview` says is still waiting for a
 * decision, wrapping from the last spot to the first. The spot being left never answers for itself, so null means
 * there is nowhere else to go.
 */
export function nextToReview(
  targets: readonly ReviewTarget[],
  current: ReviewTarget | null,
  wantsReview: (target: ReviewTarget) => boolean,
): ReviewTarget | null {
  const start = current ? targets.findIndex((target) => sameTarget(target, current)) : -1
  for (let step = 1; step <= targets.length; step++) {
    const candidate = targets[(start + step) % targets.length]!
    if (current && sameTarget(candidate, current)) continue
    if (wantsReview(candidate)) return candidate
  }
  return null
}

/** How a chip about a value's state is drawn: its colour family and the small picture that goes with its words. */
export interface StateChipLook {
  tone: 'neutral' | 'honey' | 'success' | 'error'
  icon: IconName | null
}

const NEUTRAL: StateChipLook = { tone: 'neutral', icon: null }

/**
 * The words are those fieldStateWords gives a state. Colour never carries the meaning alone: every chip keeps its
 * words, and the picture is a second sign (a tick for what passed, a mark for what needs a second look).
 */
const LOOKS: Record<string, StateChipLook> = {
  'From Brownie': { tone: 'honey', icon: 'sparkle' },
  'Brownie and you': { tone: 'honey', icon: 'sparkle' },
  'Typed by you': { tone: 'neutral', icon: 'pencil' },
  'Source cited': { tone: 'neutral', icon: 'quote' },
  'Source cited, reworded': { tone: 'neutral', icon: 'quote' },
  'Evidence unclear': { tone: 'honey', icon: 'warning' },
  'Not in the source': { tone: 'honey', icon: 'warning' },
  'Checks passed': { tone: 'success', icon: 'check' },
  'Check warning': { tone: 'honey', icon: 'warning' },
  'Blocks export': { tone: 'error', icon: 'alert-circle' },
  Accepted: { tone: 'success', icon: 'check' },
  Rejected: { tone: 'error', icon: 'close-circle' },
  'Needs clarification': { tone: 'honey', icon: 'help-circle' },
  'Kept on regeneration': { tone: 'neutral', icon: 'lock' },
  Locked: { tone: 'neutral', icon: 'lock' },
}

/** The look of the chip for one word of a state; a word this page has no look for is a plain chip. */
export function stateChipLook(word: string): StateChipLook {
  return LOOKS[word] ?? NEUTRAL
}
