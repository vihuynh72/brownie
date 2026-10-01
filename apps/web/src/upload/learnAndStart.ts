import {
  ApiRequestError,
  activateTemplateVersion,
  allocateUpload,
  completeUpload,
  createDocument,
  createTemplateDraft,
  extractArtifact,
  getDraftCandidateBindings,
  getTemplateLayout,
  listTrashedTemplates,
  replaceDraftBindings,
  uploadArtifactContent,
  type ArtifactResponse,
  type ExtractionResponse,
  type FieldDefinitionRequest,
  type TemplateVersionResponse,
} from '@/api/client'
import { describeCommonFailure } from '@/api/failures'
import { formatBytes, loadCapabilities } from '@/capabilities'
import { documentTitleFor, useTemplatesStore } from '@/stores/templates'

/**
 * Uploading a form from Home: the Word file a person wants filled in
 * becomes a template and a document in one go, with no screen in between.
 *
 * It runs, in the browser, the same requests the template screen drives
 * one step at a time: upload and scan the file, read its structure, learn
 * a field from each of its content controls, activate the template, and
 * create a document from it. Where that screen lets a person review the
 * suggested fields first, this accepts every suggestion as the server made
 * it, optional, because the person asked to fill the form, not to teach it.
 *
 * Nothing half-made is left where the person can see it. Every step up to
 * activation works on a draft, and drafts are never listed with the
 * templates; once the template is active it is complete, and only then is
 * it added to My Templates.
 */

/** What is happening now, in the order it happens: the page says each one as it starts. */
export type LearnStep = 'uploading' | 'checking' | 'learning' | 'preparing' | 'opening'

export type LearnOutcome =
  | {
      ok: true
      documentId: number
      /** What the template and the document are both called: the file's own name, without its extension. */
      name: string
      /** Something the person should know about the form Brownie learned, shown when the document opens; null when there is nothing to say. */
      note: string | null
    }
  | { ok: false; message: string }

const DOCX_MEDIA_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

/**
 * What the file chooser offers. A PDF is offered on purpose: someone with
 * only a PDF of their form should be told plainly that Brownie cannot fill
 * it, rather than find the file greyed out with no reason given.
 */
export const FORM_FILE_ACCEPT = `.docx,.pdf,${DOCX_MEDIA_TYPE},application/pdf`

const PDF_REFUSAL = 'Brownie can fill Word (.docx) forms. It cannot fill a PDF.'
const NOT_DOCX_REFUSAL = 'Brownie can fill Word (.docx) forms. Choose a .docx file.'
/** A file whose name says Word but whose bytes do not: an older .doc renamed, or something else entirely. */
const NOT_A_WORD_FILE = 'Brownie can fill Word (.docx) forms, and it could not open this file as one. Choose a .docx file saved from Word.'
const NO_CONTENT_CONTROLS =
  'Brownie found no content control with a tag in this Word file, and the tag is how Brownie knows which value goes where, ' +
  "so Brownie cannot fill it. In Word's Developer tab, add a content control where each value goes and give each one a tag " +
  'under Properties, then upload the file again.'
const EVERY_TAG_SHARED =
  'Every content control in this Word file shares its tag with another one, so Brownie cannot tell which one a value belongs in. ' +
  "Give each content control a tag of its own in Word's Developer tab, then upload the file again."
const NOTHING_IN_THE_BODY =
  "Every content control in this Word file is outside the body of the form, such as in a header or footer, and Brownie writes values only in the body, so Brownie cannot fill it."

const INITIAL_REVISION_REASON = 'Created from a Word form uploaded on Home.'

/** A step failed and the sentence says why; nothing after it runs. */
class Refusal extends Error {
  constructor(readonly sentence: string) {
    super(sentence)
  }
}

/**
 * Learns the form in `file`, creates a document from it, and answers with
 * the document to open, or with the sentence that says why it could not.
 * It never throws: whatever goes wrong, the page gets words to show.
 */
export async function learnFormAndStartDocument(
  workspaceId: number,
  file: File,
  onStep: (step: LearnStep) => void = () => {},
): Promise<LearnOutcome> {
  try {
    return { ok: true, ...(await learnAndStart(workspaceId, file, onStep)) }
  } catch (error) {
    if (error instanceof Refusal) return { ok: false, message: error.sentence }
    return { ok: false, message: `Could not upload "${file.name}". Try again.` }
  }
}

async function learnAndStart(
  workspaceId: number,
  file: File,
  onStep: (step: LearnStep) => void,
): Promise<{ documentId: number; name: string; note: string | null }> {
  const fileName = file.name
  const kind = fileKind(file)
  if (kind === 'pdf') throw new Refusal(PDF_REFUSAL)
  if (kind === 'other') throw new Refusal(NOT_DOCX_REFUSAL)
  // Checked here as well as by the server, so a file that could never be accepted is not sent first.
  const limit = await uploadLimit()
  if (limit !== null && file.size > limit) throw new Refusal(tooLarge(limit))

  onStep('uploading')
  const couldNotUpload = `Could not upload "${fileName}".`
  const allocated = await attempt(
    () => allocateUpload(workspaceId, fileName),
    (error) => `${couldNotUpload} ${reason(error, 'a way to upload files')}`,
  )
  const uploaded = await attempt(
    () => uploadArtifactContent(workspaceId, allocated.id, file),
    (error) => uploadRefusal(error, file, limit),
  )
  // The server reads the bytes, not the name: a PDF saved with a .docx name is still a PDF.
  if (uploaded.detectedMediaType === 'PDF') throw new Refusal(PDF_REFUSAL)
  if (uploaded.detectedMediaType && uploaded.detectedMediaType !== 'DOCX') throw new Refusal(NOT_A_WORD_FILE)

  onStep('checking')
  const completed = await attempt(
    () => completeUpload(workspaceId, allocated.id),
    (error) => `Could not check "${fileName}". ${reason(error, 'a way to check uploaded files')}`,
  )
  if (completed.status !== 'READY') throw new Refusal(scanRefusal(completed, fileName))
  const extraction = await attempt(
    () => extractArtifact(workspaceId, allocated.id),
    (error) => `Could not read "${fileName}". ${reason(error, 'a way to read Word files')}`,
  )
  if (extraction.status !== 'COMPLETE') throw new Refusal(extractionRefusal(extraction, fileName))

  onStep('learning')
  // The server's own tidy form of the file's name, where it gave one: that is what it will show elsewhere.
  // A form uploaded again beside one already learned gets a name of its own, so the two can be told apart.
  // The names the person can see: My Templates and the Trash Bin. A draft left by a refused upload is seen nowhere.
  const templates = useTemplatesStore()
  await templates.refresh(workspaceId)
  const taken = [...templates.usable.map((template) => template.displayName), ...(await trashedNames(workspaceId))]
  const name = uniqueName(nameOf(allocated.displayFilename ?? fileName), taken)
  const couldNotLearn = (error: unknown) => learningRefusal(error, fileName)
  const draft = await attempt(() => createTemplateDraft(workspaceId, name, allocated.id), couldNotLearn)
  const templateId = draft.template.id
  const report = await attempt(() => getDraftCandidateBindings(workspaceId, templateId), couldNotLearn)
  if (report.candidates.length === 0) {
    throw new Refusal(report.ambiguousContentControlTags.length > 0 ? EVERY_TAG_SHARED : NO_CONTENT_CONTROLS)
  }
  // The server's own suggestions, as the template screen fills them in before anyone changes them: a tag
  // with the word "date" in it is a date, and a control in the one row under a first table's header is one
  // of a repeated row's values.
  let fields: FieldDefinitionRequest[] = report.candidates.map((candidate) => ({
    fieldId: candidate.fieldId,
    type: candidate.type,
    cardinality: candidate.cardinality,
    requiredness: 'OPTIONAL',
    binding: { kind: 'CONTENT_CONTROL_TAG', tag: candidate.contentControlTag },
  }))
  let bound = await attempt(
    () => replaceDraftBindings(workspaceId, templateId, draft.draftVersion.versionNumber, fields),
    couldNotLearn,
  )
  // A control the filler never writes into (one in a header, say) would stop the whole form from activating,
  // so it is left out, and the person is told which.
  const unplaced = await fieldsWithNoPlace(workspaceId, templateId, bound)
  if (unplaced.length > 0) {
    const placed = fields.filter((field) => !unplaced.includes(field.fieldId))
    if (placed.length === 0) throw new Refusal(NOTHING_IN_THE_BODY)
    fields = placed
    const expected = bound.versionNumber
    bound = await attempt(() => replaceDraftBindings(workspaceId, templateId, expected, placed), couldNotLearn)
  }

  onStep('preparing')
  const activated = await activate(workspaceId, templateId, bound, fields, fileName)
  // The template is complete now, so My Templates may show it; a failed refresh is the store's own quiet state.
  await useTemplatesStore().refresh(workspaceId)

  onStep('opening')
  const document = await attempt(
    () =>
      createDocument(workspaceId, crypto.randomUUID(), {
        title: documentTitleFor(name),
        templateId,
        templateVersionId: activated.id,
        fields: {},
        initialRevisionReason: INITIAL_REVISION_REASON,
      }),
    (error) =>
      `Brownie learned "${name}" and added it to My Templates, but could not start a document from it. ` +
      `${reason(error, 'a way to create documents')} Choose it under My Templates to try again.`,
  )
  const notes = [
    sharedTagsNote(report.ambiguousContentControlTags),
    outsideTheBodyNote(unplaced),
    untaggedNote(report.untaggedContentControlCount ?? 0),
  ].filter((note) => note !== null)
  return { documentId: document.id, name, note: notes.length > 0 ? notes.join(' ') : null }
}

/** The names in the Trash Bin, or none where it cannot be read (an older server answers with every template, none trashed). */
async function trashedNames(workspaceId: number): Promise<string[]> {
  try {
    return (await listTrashedTemplates(workspaceId)).filter((template) => template.trashedAt != null).map((template) => template.displayName)
  } catch {
    return []
  }
}

/** The name itself when no template has it yet; otherwise the first of "name (2)", "name (3)", ... that none has. */
export function uniqueName(name: string, taken: readonly string[]): string {
  const names = new Set(taken)
  if (!names.has(name)) return name
  let suffix = 2
  while (names.has(`${name} (${suffix})`)) suffix += 1
  return `${name} (${suffix})`
}

/**
 * The fields the draft's page has no fill spot for: the page is drawn by
 * the same rules the filler writes by, so these are the ones it would never
 * fill. A server that cannot draw the page (an older one, or a file it will
 * not draw) says nothing here, and activation is left to judge the form.
 */
async function fieldsWithNoPlace(workspaceId: number, templateId: number, version: TemplateVersionResponse): Promise<string[]> {
  try {
    return (await getTemplateLayout(workspaceId, templateId, version.id)).unplacedFieldIds ?? []
  } catch {
    return []
  }
}

/**
 * Activates the draft as bound; and once more with every field as a single
 * value when the server refuses a repeated row it cannot find. The server
 * suggests a repeated value for every control inside a table cell, but it
 * repeats only the last row of the first table, so a form laid out as a
 * table of labels and boxes is refused as suggested. Filled as single
 * values, the same boxes work, which is what such a form means anyway.
 */
async function activate(
  workspaceId: number,
  templateId: number,
  bound: TemplateVersionResponse,
  fields: FieldDefinitionRequest[],
  fileName: string,
): Promise<TemplateVersionResponse> {
  const couldNotActivate = (error: unknown) =>
    `Brownie could not finish learning the form in "${fileName}", so nothing was added to My Templates. ` +
    reason(error, 'a way to learn forms')
  try {
    return await activateTemplateVersion(workspaceId, templateId, bound.versionNumber)
  } catch (error) {
    const repeatedRowRefused =
      error instanceof ApiRequestError &&
      error.status === 422 &&
      (error.problem?.code ?? '').startsWith('TEMPLATE_FILL_') &&
      fields.some((field) => field.cardinality === 'REPEATED')
    if (!repeatedRowRefused) throw new Refusal(couldNotActivate(error))
  }
  const single = fields.map((field) => ({ ...field, cardinality: 'SCALAR' as const }))
  const rebound = await attempt(() => replaceDraftBindings(workspaceId, templateId, bound.versionNumber, single), couldNotActivate)
  return attempt(() => activateTemplateVersion(workspaceId, templateId, rebound.versionNumber), couldNotActivate)
}

async function attempt<T>(call: () => Promise<T>, explain: (error: unknown) => string): Promise<T> {
  try {
    return await call()
  } catch (error) {
    throw new Refusal(explain(error))
  }
}

/**
 * Why a request failed, as the second half of a sentence. Never the
 * error's own message: for an answer with no explanation that is "Request
 * failed with status 502".
 */
function reason(error: unknown, what: string): string {
  const common = describeCommonFailure(error, what)
  if (common) return common
  if (!(error instanceof ApiRequestError)) return 'Try again.'
  // The server's own words for an error it did not expect point at a reference the page would not otherwise show.
  if (error.status === 500) {
    return error.problem?.correlationId
      ? `Something went wrong inside Brownie. If it happens again, give whoever runs this Brownie this reference: ${error.problem.correlationId}.`
      : 'Something went wrong inside Brownie. Try again.'
  }
  return error.problem?.detail ?? 'Try again.'
}

/** Judged by name first, then by the type the browser reports, since either can be missing. */
function fileKind(file: File): 'docx' | 'pdf' | 'other' {
  const lower = file.name.toLowerCase()
  if (lower.endsWith('.pdf') || file.type === 'application/pdf') return 'pdf'
  if (lower.endsWith('.docx') || file.type === DOCX_MEDIA_TYPE) return 'docx'
  return 'other'
}

/** The deployment's limit in bytes, or null when the server did not say (an older one) or could not be asked. */
async function uploadLimit(): Promise<number | null> {
  try {
    const { maxUploadBytes } = await loadCapabilities()
    return typeof maxUploadBytes === 'number' && maxUploadBytes > 0 ? maxUploadBytes : null
  } catch {
    return null
  }
}

function tooLarge(limit: number): string {
  return `This file is larger than the ${formatBytes(limit)} upload limit, so it was not uploaded.`
}

function uploadRefusal(error: unknown, file: File, limit: number | null): string {
  if (error instanceof ApiRequestError && error.status === 413) {
    if (limit !== null && file.size > limit) return tooLarge(limit)
    return error.problem?.detail
      ? `Could not upload "${file.name}". ${error.problem.detail}`
      : 'This file is larger than Brownie accepts, so it was not uploaded.'
  }
  if (error instanceof ApiRequestError && error.status === 415) return NOT_A_WORD_FILE
  return `Could not upload "${file.name}". ${reason(error, 'a way to upload files')}`
}

/** The rejection reasons are the scanner's and the content inspector's own codes; a file still being checked has none. */
function scanRefusal(artifact: ArtifactResponse, fileName: string): string {
  if (artifact.status !== 'REJECTED') return `Brownie could not finish checking "${fileName}". Try again in a minute.`
  switch (artifact.rejectionReason) {
    case 'MALWARE_DETECTED':
      return `Brownie did not accept "${fileName}": the malware scan flagged it.`
    case 'UNSUPPORTED_MEDIA_TYPE':
      return NOT_A_WORD_FILE
    case 'DECOMPRESSION_LIMIT_EXCEEDED':
      return `Brownie did not accept "${fileName}": it unpacks to far more than Brownie accepts.`
    case 'EXPIRED_ABANDONED_UPLOAD':
      return `Brownie did not accept "${fileName}": the upload took too long to finish. Try again.`
    default:
      return `Brownie did not accept "${fileName}": it did not pass Brownie's checks.`
  }
}

/** What the reader reports it found in a Word file that a filled copy could not keep faithfully. */
const UNSUPPORTED_FEATURE_WORDS: Record<string, string> = {
  TRACKED_CHANGES: 'tracked changes',
  UNRESOLVED_COMMENT: 'comments',
  FLOATING_SHAPE: 'floating shapes',
  NESTED_TABLE: 'a table inside another table',
  LINKED_EXTERNAL_IMAGE: 'a picture linked from outside the file',
  EMBEDDED_OBJECT: 'an embedded object',
  UNSUPPORTED_FIELD: 'a kind of Word field Brownie cannot fill around',
  PACKAGE_SIGNATURE: 'a digital signature',
}

/**
 * The server's answer carries, for a Word file it would not read, the
 * features it found; the generated type does not model that list, so it is
 * read here with care and anything unrecognised is left out.
 */
function extractionRefusal(extraction: ExtractionResponse, fileName: string): string {
  if (extraction.status === 'UNSUPPORTED') {
    const findings = (extraction as { unsupportedFeatures?: unknown }).unsupportedFeatures
    const words = Array.isArray(findings)
      ? [
          ...new Set(
            findings
              .map((finding) => UNSUPPORTED_FEATURE_WORDS[String((finding as { feature?: unknown } | null)?.feature)])
              .filter((word): word is string => word !== undefined),
          ),
        ]
      : []
    if (words.length === 0) return NOT_A_WORD_FILE
    return `Brownie cannot fill "${fileName}" because it has ${listed(words)}. Remove ${words.length === 1 ? 'that' : 'those'} in Word, then upload the file again.`
  }
  return `Brownie could not read "${fileName}". The file may be damaged: open it in Word, save it again, and upload it again.`
}

function learningRefusal(error: unknown, fileName: string): string {
  if (error instanceof ApiRequestError && error.problem?.code === 'SOURCE_NOT_EXTRACTABLE') return NOT_A_WORD_FILE
  return `Brownie could not learn the form in "${fileName}". ${reason(error, 'a way to learn forms')}`
}

/**
 * A tag on more than one content control is never suggested, because a
 * value could not be told which one it belongs in; the rest of the form
 * still works, and the person is told which boxes will stay empty.
 */
function sharedTagsNote(tags: string[]): string | null {
  if (tags.length === 0) return null
  const quoted = listed(tags.map((tag) => `"${tag}"`))
  const which = tags.length === 1 ? 'That tag is' : 'Each of those tags is'
  return (
    `Brownie did not learn ${quoted}. ${which} on more than one content control in the form, so Brownie cannot tell which ` +
    `one a value belongs in. To fill ${tags.length === 1 ? 'it' : 'them'}, give each content control a tag of its own in Word and upload the form again.`
  )
}

/** Content controls the filler never writes into, such as those in a header or footer, are left out of the template. */
function outsideTheBodyNote(tags: string[]): string | null {
  if (tags.length === 0) return null
  const quoted = listed(tags.map((tag) => `"${tag}"`))
  return (
    `Brownie did not learn ${quoted}: ${tags.length === 1 ? 'it is' : 'they are'} outside the body of the form, such as in a ` +
    'header or footer, and Brownie writes values only in the body.'
  )
}

/**
 * A content control with no tag has nothing to learn it by, so the filled
 * form keeps it as it was; the person is told how many, and how to have
 * Brownie fill them. A server from before the count says nothing here.
 */
function untaggedNote(count: number): string | null {
  if (count <= 0) return null
  if (count === 1) {
    return (
      'Brownie did not learn 1 content control that has no tag; it stays as it is in the form. ' +
      "To fill it, give it a tag under Properties in Word's Developer tab and upload the form again."
    )
  }
  return (
    `Brownie did not learn ${count} content controls that have no tag; they stay as they are in the form. ` +
    "To fill them, give each one a tag under Properties in Word's Developer tab and upload the form again."
  )
}

function listed(items: string[]): string {
  if (items.length <= 1) return items.join('')
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

/** "Club minutes.docx" is "Club minutes"; a name that is nothing but its extension still gets a name. */
export function nameOf(fileName: string): string {
  const withoutExtension = fileName.replace(/\.[^./\\]+$/, '').trim()
  return withoutExtension === '' ? 'Uploaded form' : withoutExtension
}
