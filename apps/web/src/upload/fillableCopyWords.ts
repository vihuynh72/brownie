/**
 * What a person is told about a file they chose as a form, in plain words:
 * when Brownie will not open it, what the file is, why Brownie stops there,
 * and what to do next; and once it is open, what Brownie changed or found
 * in making it ready to fill. The server says which in codes (a refusal's
 * `reason`, a notice's `code`); this page owns the sentences, so they read
 * the same wherever they are shown.
 */

const WHAT_BROWNIE_FILLS = 'Brownie can fill Word, RTF, OpenDocument and Pages documents, and PDF forms.'

/** One sentence for each reason the server gives with a 415 refusal of an upload. */
export const REFUSAL_SENTENCES = {
  PASSWORD_PROTECTED:
    "This file is locked with a password, so Brownie can't open it. Open it, remove the password, save it, and upload it again.",
  RIGHTS_PROTECTED: "This file is protected by your organization's rights management, so Brownie can't open it.",
  SPREADSHEET: `This is a spreadsheet, not a document. ${WHAT_BROWNIE_FILLS}`,
  PRESENTATION: `This is a presentation, not a document. ${WHAT_BROWNIE_FILLS}`,
  NOT_A_DOCUMENT:
    'Brownie can fill Word (.docx, .doc), RTF, OpenDocument (.odt) and Pages documents, and PDF forms. ' +
    'It could not recognize this file as any of them.',
  REMOTE_CONTENT:
    'This file asks the computer that opens it to fetch something from the internet (a linked template, picture or ' +
    'object), and Brownie does not accept files that do that. Open it in Word, save a copy without the links, and upload that.',
  DAMAGED: 'Brownie could not read this file. It may be damaged: open it, save it again, and upload it again.',
} as const

export type RefusalReason = keyof typeof REFUSAL_SENTENCES

/**
 * The sentence for the server's reason, or null for a reason this page does
 * not know or none at all (an older server sends none), so the caller can
 * say something of its own instead.
 */
export function refusalSentence(reason: unknown): string | null {
  return typeof reason === 'string' && Object.hasOwn(REFUSAL_SENTENCES, reason)
    ? REFUSAL_SENTENCES[reason as RefusalReason]
    : null
}

/** For each format that is converted to a Word copy: what to call it, and where a person can save it as Word. */
const CONVERTED_FORMATS: Record<string, { name: string; openIn: string; how: string }> = {
  DOC: { name: 'Word 97-2003', openIn: 'Word', how: '' },
  RTF: { name: 'RTF', openIn: 'a word processor', how: '' },
  ODT: { name: 'OpenDocument', openIn: 'LibreOffice', how: '' },
  PAGES: { name: 'Pages', openIn: 'Pages', how: ' (File > Export To > Word)' },
}

/**
 * What to call a format that is converted to a Word copy, by the upload's
 * detected media type (DOC, RTF, ODT, PAGES); null for any other type,
 * including the Word types that need no converting and PDF.
 */
export function convertedFormatName(mediaType: string | null | undefined): string | null {
  return (mediaType && Object.hasOwn(CONVERTED_FORMATS, mediaType) ? CONVERTED_FORMATS[mediaType]?.name : null) ?? null
}

/** The server names a converted format by the converter's own names; these are the words for each. */
const CONVERTER_FORMAT_NAMES: Record<string, string> = {
  WORD_97: 'Word 97-2003',
  WORD_95: 'Word 95',
  RTF: 'RTF',
  ODT: 'OpenDocument',
  ODT_TEMPLATE: 'OpenDocument',
  PAGES: 'Pages',
}

function converterFormatName(format: string | null | undefined): string | null {
  return format && Object.hasOwn(CONVERTER_FORMAT_NAMES, format) ? (CONVERTER_FORMAT_NAMES[format] ?? null) : null
}

/**
 * When the file was accepted but a Word copy could not be made from it:
 * `mediaType` is the upload's detected media type. A type this page does
 * not know is still told how to go on, without naming the format.
 */
export function cannotOpenSentence(mediaType: string | null | undefined): string {
  const format = mediaType ? CONVERTED_FORMATS[mediaType] : undefined
  if (!format) {
    return 'Brownie could not open this file. Open it in the program that made it and save it as a Word document (.docx), then upload that.'
  }
  return (
    `Brownie could not open this ${format.name} file. Open it in ${format.openIn} and save it as a Word document ` +
    `(.docx)${format.how}, then upload that.`
  )
}

/**
 * A Pages document saved as a package is a folder on a Mac, and a browser
 * that is handed one sends an empty file with its name.
 */
export const PAGES_PACKAGE_SENTENCE =
  "This Pages document is saved as a package, which a browser can't upload. In Pages, choose File > Export To > Word " +
  'and upload that file, or choose File > Advanced > Change File Type > Single File.'

/** Whether the chosen file is what a browser sends for a Pages package: its name, and nothing in it. */
export function isPagesPackage(file: Pick<File, 'name' | 'size'>): boolean {
  return file.size === 0 && file.name.toLowerCase().endsWith('.pages')
}

// ---- Making an accepted file ready to fill ------------------------------------------------------

/** One sentence for each reason the server gives when a PDF cannot be filled at all. */
export const PDF_REFUSAL_SENTENCES = {
  ENCRYPTED:
    'This PDF is locked with a password or protection settings, so Brownie cannot fill it without removing that ' +
    'protection. Save an unlocked copy and upload that.',
  SIGNED: 'This PDF has been signed. Filling it in would break the signature, so Brownie leaves it as it is.',
  XFA: 'This PDF is a kind of form Brownie cannot fill. Open it in Adobe Acrobat Reader, print it to a new PDF, and upload that copy.',
  LAUNCH_ACTION:
    'This PDF tries to start other programs when it is opened, so Brownie does not accept it. Print it to a new PDF and upload that copy.',
  EMBEDDED_FILES:
    'This PDF has other files attached inside it, so Brownie does not accept it. Save a copy without the attached files and upload that.',
  DOCUMENT_JAVASCRIPT:
    'This PDF runs scripts when it is opened, so Brownie does not accept it. Print it to a new PDF and upload that copy.',
  DAMAGED: 'Brownie could not read this PDF. It may be damaged: open it, save it again, and upload it again.',
  TOO_LARGE:
    'This PDF has more pages or fields than Brownie can fill in one form. Upload a shorter PDF, such as just the pages to fill in.',
} as const

export type PdfRefusalReason = keyof typeof PDF_REFUSAL_SENTENCES

/** The sentence for why a PDF cannot be filled, or null for a reason this page does not know. */
export function pdfRefusalSentence(reason: unknown): string | null {
  return typeof reason === 'string' && Object.hasOwn(PDF_REFUSAL_SENTENCES, reason)
    ? PDF_REFUSAL_SENTENCES[reason as PdfRefusalReason]
    : null
}

/** What the server says about an upload it accepted but could not make ready to fill: its code, and the reason or format some codes carry. */
export interface FillableFormProblem {
  code?: string | null
  reason?: string | null
  format?: unknown
}

const SAVE_AS_WORD = 'save it as a Word document (.docx) and upload that'

/**
 * Why the server would not make an accepted upload ready to fill, from the
 * refusal's code: `mediaType` is the upload's detected media type, so the
 * sentence can name the format. Null for a refusal that is not about the
 * file (a busy server, a lost connection), which the caller words itself.
 */
export function fillableFormRefusal(problem: FillableFormProblem | null | undefined, mediaType: string | null | undefined): string | null {
  const format = convertedFormatName(mediaType)
  switch (problem?.code) {
    case 'NOT_A_WORD_PROCESSING_DOCUMENT':
      return `This file is not a document Brownie can fill in. ${WHAT_BROWNIE_FILLS}`
    case 'FORMAT_DISABLED': {
      const named = converterFormatName(typeof problem.format === 'string' ? problem.format : null) ?? format
      return `This Brownie does not open ${named ? `${named} files` : 'files of this kind'} right now. Open the file, ${SAVE_AS_WORD}.`
    }
    case 'CONVERTER_UNAVAILABLE':
      return (
        `Brownie cannot open ${format ? `${format} files` : 'files of this kind'} right now. Try again in a few minutes, ` +
        `or open the file, ${SAVE_AS_WORD}.`
      )
    case 'FILLABLE_FORM_FAILED':
      if (problem.reason === 'DAMAGED') return REFUSAL_SENTENCES.DAMAGED
      if (problem.reason === 'TIMED_OUT') {
        return `Opening this ${format ? `${format} ` : ''}file took too long, so Brownie stopped. Try again, or open it, ${SAVE_AS_WORD}.`
      }
      return cannotOpenSentence(mediaType)
    case 'PDF_FORM_NOT_FILLABLE':
      return pdfRefusalSentence(problem.reason) ?? PDF_REFUSAL_SENTENCES.DAMAGED
    default:
      return null
  }
}

// ---- Notes on arrival ---------------------------------------------------------------------------

/** What Brownie kept as it is in its copy of a Word file, by the reader's own name for it. */
const KEPT_AS_IS_WORDS: Record<string, string> = {
  FLOATING_SHAPE: 'floating shapes',
  NESTED_TABLE: 'tables inside tables',
  EMBEDDED_OBJECT: 'embedded objects',
  DYNAMIC_FIELD: 'parts Word fills in by itself, such as page numbers',
  TRACKED_FORMATTING_CHANGE: 'formatting changes that were being tracked',
}

/** What the notes are worked out from: the made-ready form's kind, its notices in order, and its spots. */
export interface FormNoteSource {
  kind: 'DOCX' | 'PDF'
  notices: readonly { code: string; count: number; detail?: string | null }[]
  spots: readonly { origin: string; binding: { kind: string } }[]
}

/**
 * The notices the server sent about a form it made ready, as sentences for
 * the notes about the form, in the server's order. Every thing kept as it
 * is goes in one sentence; a code this page does not know says nothing,
 * since a newer server may send one. Where the page itself says how many
 * places Brownie found (`foundShownOnPage`), the notes leave that out.
 */
export function formNoteSentences(form: FormNoteSource, options: { foundShownOnPage?: boolean } = {}): string[] {
  const sentences: string[] = []
  const kept: string[] = []
  let keptAt = -1
  for (const notice of form.notices) {
    if (notice.code === 'KEPT_AS_IS') {
      const words = notice.detail ? KEPT_AS_IS_WORDS[notice.detail] : undefined
      if (words && !kept.includes(words)) kept.push(words)
      if (keptAt < 0) keptAt = sentences.length
      continue
    }
    if (notice.code === 'SPOTS_FOUND' && options.foundShownOnPage) continue
    const sentence = noticeSentence(notice, form)
    if (sentence) sentences.push(sentence)
  }
  if (kept.length > 0) {
    sentences.splice(keptAt, 0, `Brownie kept ${listed(kept)} as they are in your file. Check how they look in Print preview.`)
  }
  return sentences
}

function noticeSentence(notice: { code: string; count: number; detail?: string | null }, form: FormNoteSource): string | null {
  const count = notice.count
  switch (notice.code) {
    case 'SPOTS_FOUND':
      return spotsFoundSentence(count, form.spots.filter((spot) => spot.origin === 'FOUND_BY_BROWNIE').length, form.kind)
    case 'NO_SPOTS_FOUND':
      return form.kind === 'PDF'
        ? 'Brownie did not find any blanks. Choose Add a fill spot, or draw a box wherever something should be filled in.'
        : 'Brownie did not find any blanks. Choose Add a fill spot, or select a place on the page and choose Fill in here.'
    case 'TRACKED_CHANGES_AND_COMMENTS':
      return "Brownie's copy has the tracked changes accepted and the comments left out; your original file is unchanged."
    case 'MACROS_REMOVED':
      return "Brownie's copy leaves out the file's macros, so nothing in it can run; your original file is unchanged."
    case 'SIGNATURE_REMOVED':
      return "Brownie's copy has no digital signature, because filling it in changes the file; your original file keeps its signature."
    case 'LINKED_CONTENT_REMOVED':
      return "Brownie's copy leaves out links to things outside the file, such as a linked picture; your original file is unchanged."
    case 'FIELDS_FROZEN':
      return "Brownie's copy shows parts that fetch or work out their own text as the text they showed; they no longer update."
    case 'EMBEDDED_FILES_TO_PICTURES':
      return "Brownie's copy shows files embedded in the document as pictures of them; they can no longer be opened from it."
    case 'EDITING_RESTRICTION_REMOVED':
      return "Brownie's copy has the file's editing restrictions turned off so it can be filled in; your original file is unchanged."
    case 'CONVERTED': {
      const format = converterFormatName(notice.detail) ?? convertedFormatName(notice.detail)
      return `Brownie turned your ${format ? `${format} ` : ''}file into a Word copy to fill; check that it looks right.`
    }
    case 'SIGNATURE_LINES_LEFT':
      return count === 1
        ? 'Brownie left the signature line empty, for signing by hand.'
        : `Brownie left ${count} signature lines empty, for signing by hand.`
    case 'CHECKBOXES_LEFT':
      return count === 1
        ? 'Brownie does not tick check boxes, so it left the one in this form for you to tick in the exported file.'
        : `Brownie does not tick check boxes, so it left the ${count} in this form for you to tick in the exported file.`
    case 'SOME_NAMED_BY_RULES':
      return count === 1
        ? 'Brownie named one place from the words next to it, so check that its name makes sense.'
        : `Brownie named ${count} places from the words next to them, so check that their names make sense.`
    case 'SPOTS_SKIPPED':
      return count === 1
        ? 'Brownie found one more blank but could not make it a place to fill in. Add it yourself if you need it.'
        : `Brownie found ${count} more blanks but could not make them places to fill in. Add them yourself if you need them.`
    case 'PLACES_LEFT_OUT':
      return count === 1
        ? 'Brownie left out one blank that did not look like a place for you to fill in. Add it yourself if you need it.'
        : `Brownie left out ${count} blanks that did not look like places for you to fill in. Add them yourself if you need them.`
    case 'BLANKS_OUTSIDE_BODY':
      return count === 1
        ? 'Brownie left one blank outside the main text, such as in a header or footer, as it is; it fills only the main text.'
        : `Brownie left ${count} blanks outside the main text, such as in a header or footer, as they are; it fills only the main text.`
    case 'TABLE_ROWS_GROW':
      return 'A table in this form gets a new row for each item you add.'
    case 'SCANNED_PDF':
      // The page says this itself, over the scanned pages, for as long as the document is open.
      return null
    case 'PDF_FIELDS_LEFT': {
      const filled = form.spots.filter((spot) => spot.binding.kind === 'ACROFORM_FIELD').length
      return filled > 0
        ? `Brownie will fill ${filled} of this form's fields. It leaves the check boxes and lists for you to set in your PDF reader.`
        : "Brownie leaves this form's check boxes and lists for you to set in your PDF reader."
    }
    default:
      return null
  }
}

/**
 * How a place Brownie found is marked, for one place and for several: a Word page labels it, and a PDF's own
 * page, where a label would cover the form's words, outlines its box with dashes.
 */
const FOUND_MARK = {
  DOCX: { one: 'is marked "Found by Brownie"', many: 'are marked "Found by Brownie"' },
  PDF: { one: 'has a dashed outline', many: 'have a dashed outline' },
} as const

/** How many places there are to fill in, and which of them Brownie added itself and marked for checking. */
function spotsFoundSentence(total: number, found: number, kind: FormNoteSource['kind']): string {
  const places = total === 1 ? '1 place' : `${total} places`
  const mark = FOUND_MARK[kind]
  if (found === 0) return `Brownie found ${places} to fill in.`
  if (found >= total) {
    return total === 1
      ? `Brownie found ${places} to fill in. It ${mark.one} so you can check it.`
      : `Brownie found ${places} to fill in. Each ${mark.one} so you can check it.`
  }
  return found === 1
    ? `Brownie found ${places} to fill in. The one it added itself ${mark.one} so you can check it.`
    : `Brownie found ${places} to fill in. The ${found} it added itself ${mark.many} so you can check them.`
}

function listed(items: readonly string[]): string {
  if (items.length <= 1) return items.join('')
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}
