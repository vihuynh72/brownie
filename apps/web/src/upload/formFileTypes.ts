import type { CapabilitiesResponse } from '@/api/client'
import { loadCapabilities } from '@/capabilities'

/**
 * The files a person may choose as a form to fill, and how Brownie fills
 * each: a Word document as it is, another word processor's file through a
 * Word copy converted from it, and a PDF as a PDF.
 *
 * The server says this in its capabilities; the list below is what this
 * page offers when it cannot ask (an older server, or a failed request).
 * Either way the chooser only suggests: the server reads every file's bytes
 * and decides what it really is.
 */

export type FormFileRoute = 'NATIVE' | 'CONVERTED' | 'PDF'

export interface FormFileType {
  /** Without the dot, lower case. */
  extension: string
  mediaType: string
  route: FormFileRoute
}

/** A browser reports some of these under more than one type, so an extension may appear once for each. */
export const FALLBACK_FORM_FILE_TYPES: readonly FormFileType[] = [
  { extension: 'docx', mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', route: 'NATIVE' },
  { extension: 'dotx', mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.template', route: 'NATIVE' },
  { extension: 'docm', mediaType: 'application/vnd.ms-word.document.macroEnabled.12', route: 'NATIVE' },
  { extension: 'dotm', mediaType: 'application/vnd.ms-word.template.macroEnabled.12', route: 'NATIVE' },
  { extension: 'doc', mediaType: 'application/msword', route: 'CONVERTED' },
  { extension: 'dot', mediaType: 'application/msword', route: 'CONVERTED' },
  { extension: 'rtf', mediaType: 'application/rtf', route: 'CONVERTED' },
  { extension: 'rtf', mediaType: 'text/rtf', route: 'CONVERTED' },
  { extension: 'odt', mediaType: 'application/vnd.oasis.opendocument.text', route: 'CONVERTED' },
  { extension: 'ott', mediaType: 'application/vnd.oasis.opendocument.text-template', route: 'CONVERTED' },
  { extension: 'pages', mediaType: 'application/vnd.apple.pages', route: 'CONVERTED' },
  { extension: 'pages', mediaType: 'application/x-iwork-pages-sffpages', route: 'CONVERTED' },
  { extension: 'pdf', mediaType: 'application/pdf', route: 'PDF' },
]

/** The chooser's `accept` value: every extension, then every media type, each once, in the list's order. */
export function formFileAccept(types: readonly FormFileType[] = FALLBACK_FORM_FILE_TYPES): string {
  const extensions = [...new Set(types.map((type) => `.${type.extension}`))]
  const mediaTypes = [...new Set(types.map((type) => type.mediaType))]
  return [...extensions, ...mediaTypes].join(',')
}

/** What the chooser offers when the server has not been asked. */
export const FORM_FILE_ACCEPT = formFileAccept()

const ROUTES: readonly string[] = ['NATIVE', 'CONVERTED', 'PDF']

/**
 * The server's list when it sent one this page can use, otherwise the
 * fallback. A row that is not well formed is left out; a list with no row
 * left, or none at all, is no list.
 */
export function formFileTypesFrom(capabilities: Pick<CapabilitiesResponse, 'formFileTypes'> | null | undefined): readonly FormFileType[] {
  const offered = capabilities?.formFileTypes
  if (!Array.isArray(offered)) return FALLBACK_FORM_FILE_TYPES
  const usable = offered
    .filter(
      (row): row is FormFileType =>
        typeof row?.extension === 'string' &&
        /^[a-z0-9]+$/.test(row.extension) &&
        typeof row.mediaType === 'string' &&
        row.mediaType.includes('/') &&
        ROUTES.includes(row.route),
    )
    .map((row) => ({ extension: row.extension, mediaType: row.mediaType, route: row.route }))
  return usable.length > 0 ? usable : FALLBACK_FORM_FILE_TYPES
}

/** The deployment's list, asked once per page load; the fallback when the server cannot be asked. */
export async function loadFormFileTypes(): Promise<readonly FormFileType[]> {
  try {
    return formFileTypesFrom(await loadCapabilities())
  } catch {
    return FALLBACK_FORM_FILE_TYPES
  }
}

/**
 * What a chosen file looks like before it is sent: a word-processing
 * document, a PDF, or something not on the list. Judged by the name's
 * extension, and by the type the browser reports only when the name has no
 * extension on the list. "other" is not a refusal: a file with no extension,
 * or one this page does not know, goes to the server, which judges it by
 * its bytes.
 */
export type FileKind = 'word-processing' | 'pdf' | 'other'

export function fileKind(file: Pick<File, 'name' | 'type'>, types: readonly FormFileType[] = FALLBACK_FORM_FILE_TYPES): FileKind {
  const dot = file.name.lastIndexOf('.')
  const extension = dot > 0 ? file.name.slice(dot + 1).toLowerCase() : ''
  const byExtension = types.find((type) => type.extension === extension)
  const reported = file.type.toLowerCase()
  const known = byExtension ?? types.find((type) => reported !== '' && type.mediaType.toLowerCase() === reported)
  if (!known) return 'other'
  return known.route === 'PDF' ? 'pdf' : 'word-processing'
}
