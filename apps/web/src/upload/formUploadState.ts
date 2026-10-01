import { ref } from 'vue'
import type { LearnStep } from '@/upload/learnAndStart'

/**
 * The form being learned from Home, kept outside the page: someone who
 * leaves Home while it runs finds, when they come back, either the step it
 * is on or how it ended, rather than a page that forgot it and would start
 * a second one beside it.
 */
export const formUploadStep = ref<LearnStep | null>(null)
export const formUploadError = ref<string | null>(null)

/** A form learned after the person had left Home: its document, said on Home until they open it or upload another. */
export const formUploadFinished = ref<{ documentId: number; name: string; note: string | null } | null>(null)
