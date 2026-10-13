import { ref } from 'vue'
import type { LearnStep, StepDetail } from '@/upload/learnAndStart'

/**
 * The file a person chose, as the cards on Home name it. Kept with the step and with the refusal because those cards
 * outlive the file input: coming back to Home, the person is shown which form it was about, not just that one was.
 */
export interface ChosenForm {
  name: string
  /** "Word", "PDF" and so on, from the file's extension; null for one this page does not know by name. */
  kind: string | null
  /** "48 KB"; null when the size is not a whole number of bytes. */
  size: string | null
}

/**
 * The form being learned from Home, kept outside the page: someone who
 * leaves Home while it runs finds, when they come back, either the step it
 * is on or how it ended, rather than a page that forgot it and would start
 * a second one beside it.
 */
export const formUploadStep = ref<{ step: LearnStep; detail: StepDetail; form: ChosenForm } | null>(null)
export const formUploadError = ref<{ form: ChosenForm; message: string } | null>(null)

/**
 * A form learned after the person had left Home: its document, said on Home until they open it, dismiss it or
 * upload another. `fileName` is the file as they chose it, which is what they will recognise.
 */
export const formUploadFinished = ref<{ documentId: number; fileName: string; notes: string[] } | null>(null)
