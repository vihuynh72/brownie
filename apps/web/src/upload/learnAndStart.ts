import {
  ApiRequestError,
  activateTemplateVersion,
  allocateUpload,
  completeUpload,
  createDocument,
  createTemplateDraft,
  getTemplateLayout,
  listTrashedTemplates,
  makeFillableForm,
  replaceDraftBindings,
  uploadArtifactContent,
  type ArtifactResponse,
  type FieldDefinitionRequest,
  type FillableFormResponse,
  type FillableFormSpot,
  type TemplateVersionResponse,
} from '@/api/client'
import { describeCommonFailure } from '@/api/failures'
import { formatBytes, loadCapabilities } from '@/capabilities'
import { documentTitleFor, useTemplatesStore } from '@/stores/templates'
import {
  PAGES_PACKAGE_SENTENCE,
  REFUSAL_SENTENCES,
  convertedFormatName,
  fillableFormRefusal,
  formNoteSentences,
  isPagesPackage,
  refusalSentence,
} from '@/upload/fillableCopyWords'
import { FORM_FILE_ACCEPT as OFFERED_FORM_FILES, fileKind, loadFormFileTypes } from '@/upload/formFileTypes'
import { fieldLabel } from '@/workspace/layout'

/**
 * Uploading a form: the file a person wants filled in -- a Word document,
 * another word processor's file, or a PDF -- becomes a template, and from
 * Home a document too, in one go, with no screen in between.
 *
 * It runs, in the browser, the requests that make that happen: upload and
 * scan the file; ask the server to make it ready to fill (a clean Word copy,
 * converted first where the file is not Word, or the PDF's own form read as
 * it is, with the places to fill found and named either way); create a
 * template with a field for each place, as the server found it, keeping
 * what the server noticed about the file with it; activate the template;
 * and, from Home, create a document from it. The + beside My Templates
 * stops once the template is active. Every place is accepted as found,
 * optional, because the person asked to fill the form; the places Brownie
 * found itself stay marked on the page until the person says they are
 * right.
 *
 * Nothing half-made is left where the person can see it. Every step up to
 * activation works on a draft, and drafts are never listed with the
 * templates; once the template is active it is complete, and only then is
 * it added to My Templates.
 */

/** What is happening now, in the order it happens: the page says each one as it starts. */
export type LearnStep = 'uploading' | 'checking' | 'preparing-copy' | 'learning' | 'preparing' | 'opening'

/** What the words for a step depend on besides the step itself. */
export interface StepDetail {
  /** What the file is, as the server found it in the bytes (DOCX, DOC, PAGES, PDF...), or a guess from its name until then; null when neither says. */
  mediaType: string | null
  /** The server was too busy to start, and the step waits a moment before asking again. */
  waiting: boolean
}

export type LearnOutcome =
  | {
      ok: true
      documentId: number
      /** What the template and the document are both called: the file's own name, without its extension. */
      name: string
      /** What the person should know about the form Brownie made ready, one sentence each, shown when the document opens. */
      notes: string[]
    }
  | { ok: false; message: string }

/** How adding a form to My Templates ended: the template, or the sentence that says why there is none. */
export type LearnTemplateOutcome =
  | {
      ok: true
      templateId: number
      /** The version made active, which a document is started from. */
      versionId: number
      /** What the template is called: the file's own name, without its extension. */
      name: string
      /** Places Brownie had to leave out of the template, in one sentence; null when it kept them all. */
      leftOutNote: string | null
    }
  | { ok: false; message: string }

/** What the file chooser offers: every kind of form a person might have, each judged by the server once chosen. */
export const FORM_FILE_ACCEPT = OFFERED_FORM_FILES

/** The words the page shows while a step is under way; making the file ready says what kind of file it is opening. */
export function learnStepWords(step: LearnStep, detail: StepDetail): string {
  if (detail.waiting) return 'Waiting for a free moment…'
  switch (step) {
    case 'uploading':
      return 'Uploading…'
    case 'checking':
      return 'Checking the file…'
    case 'preparing-copy': {
      if (detail.mediaType === 'PDF') return 'Reading your PDF and finding where the values go…'
      const format = convertedFormatName(detail.mediaType)
      return format ? `Opening your ${format} file and finding where the values go…` : 'Finding where the values go…'
    }
    case 'learning':
      return 'Learning the form…'
    case 'preparing':
      return 'Getting the template ready…'
    case 'opening':
      return 'Opening your document…'
  }
}

const INITIAL_REVISION_REASON = 'Created from a form uploaded on Home.'

/**
 * Making a file ready runs in a sandbox with a few slots shared by every
 * render. When all stay taken the server says so and when to ask again;
 * the flow waits that long, but never more than this, and asks at most
 * twice more before saying it is busy.
 */
const BUSY_RETRIES = 2
const LONGEST_WAIT_SECONDS = 10

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
  onStep: (step: LearnStep, detail: StepDetail) => void = () => {},
): Promise<LearnOutcome> {
  try {
    return { ok: true, ...(await learnAndStart(workspaceId, file, onStep)) }
  } catch (error) {
    return { ok: false, message: refusalWords(error, file) }
  }
}

/**
 * Learns the form in `file` and adds it to My Templates, the same way as
 * from Home and with the same words for a refusal, but starts no document
 * from it. It never throws.
 */
export async function learnFormAsTemplate(
  workspaceId: number,
  file: File,
  onStep: (step: LearnStep, detail: StepDetail) => void = () => {},
): Promise<LearnTemplateOutcome> {
  try {
    const learned = await learnTemplate(workspaceId, file, onStep)
    return { ok: true, templateId: learned.templateId, versionId: learned.versionId, name: learned.name, leftOutNote: leftOutNote(learned.leftOut) }
  } catch (error) {
    return { ok: false, message: refusalWords(error, file) }
  }
}

function refusalWords(error: unknown, file: File): string {
  return error instanceof Refusal ? error.sentence : `Could not upload "${file.name}". Try again.`
}

async function learnAndStart(
  workspaceId: number,
  file: File,
  onStep: (step: LearnStep, detail: StepDetail) => void,
): Promise<{ documentId: number; name: string; notes: string[] }> {
  const { templateId, versionId, name, form, leftOut, mediaType } = await learnTemplate(workspaceId, file, onStep)

  onStep('opening', { mediaType, waiting: false })
  const document = await attempt(
    () =>
      createDocument(workspaceId, crypto.randomUUID(), {
        title: documentTitleFor(name),
        templateId,
        templateVersionId: versionId,
        fields: {},
        initialRevisionReason: INITIAL_REVISION_REASON,
      }),
    (error) =>
      `Brownie learned "${name}" and added it to My Templates, but could not start a document from it. ` +
      `${reason(error, 'a way to create documents')} Choose it under My Templates to try again.`,
  )
  // The page says itself how many places Brownie found and how they are marked, so the notes do not say it again.
  const foundShownOnPage = form.spots.some((spot) => spot.origin === 'FOUND_BY_BROWNIE')
  const notes = [...formNoteSentences(form, { foundShownOnPage }), leftOutNote(leftOut)].filter((note): note is string => note !== null)
  return { documentId: document.id, name, notes }
}

/** A form learned and its template active: what it is called, the server's reading of the file, and the places left out. */
interface LearnedTemplate {
  templateId: number
  versionId: number
  name: string
  form: FillableFormResponse
  /** The names of the places the filler could never write into, which the template leaves out. */
  leftOut: string[]
  /** What the file is, as the server found it in the bytes. */
  mediaType: string | null
}

async function learnTemplate(
  workspaceId: number,
  file: File,
  onStep: (step: LearnStep, detail: StepDetail) => void,
): Promise<LearnedTemplate> {
  const fileName = file.name
  // A browser handed a Pages package (a folder on a Mac) sends an empty file, which no server could open.
  if (isPagesPackage(file)) throw new Refusal(PAGES_PACKAGE_SENTENCE)
  // The name is only a first guess at what the file is, for the words said while it is opened; every file goes to
  // the server, which reads the bytes and decides. A name this page does not know is no reason to stop.
  let mediaType: string | null = fileKind(file, await loadFormFileTypes()) === 'pdf' ? 'PDF' : null
  const say = (step: LearnStep, waiting = false) => onStep(step, { mediaType, waiting })
  // Checked here as well as by the server, so a file that could never be accepted is not sent first.
  const limit = await uploadLimit()
  if (limit !== null && file.size > limit) throw new Refusal(tooLarge(limit))

  say('uploading')
  const couldNotUpload = `Could not upload "${fileName}".`
  const allocated = await attempt(
    () => allocateUpload(workspaceId, fileName),
    (error) => `${couldNotUpload} ${reason(error, 'a way to upload files')}`,
  )
  const uploaded = await attempt(
    () => uploadArtifactContent(workspaceId, allocated.id, file),
    (error) => uploadRefusal(error, file, limit),
  )
  mediaType = uploaded.detectedMediaType ?? mediaType

  say('checking')
  const completed = await attempt(
    () => completeUpload(workspaceId, allocated.id),
    (error) => `Could not check "${fileName}". ${reason(error, 'a way to check uploaded files')}`,
  )
  if (completed.status !== 'READY') throw new Refusal(scanRefusal(completed, fileName))
  mediaType = completed.detectedMediaType ?? mediaType

  say('preparing-copy')
  const form = await makeReady(workspaceId, allocated.id, fileName, () => mediaType, say)

  say('learning')
  // The name of the file the person chose, not of Brownie's copy of it; the server's own tidy form of it where it
  // gave one, since that is what it shows elsewhere. A form uploaded again beside one already learned gets a name of
  // its own, so the two can be told apart. The names the person can see: My Templates and the Trash Bin. A draft
  // left by a refused upload is seen nowhere.
  const templates = useTemplatesStore()
  await templates.refresh(workspaceId)
  const taken = [...templates.usable.map((template) => template.displayName), ...(await trashedNames(workspaceId))]
  const name = uniqueName(nameOf(allocated.displayFilename ?? fileName), taken)
  const couldNotLearn = (error: unknown) => learningRefusal(error, fileName, mediaType)
  // What the server noticed in making the file ready stays with the template, so any document made from it can say it again.
  const draft = await attempt(() => createTemplateDraft(workspaceId, name, form.templateSourceArtifactId, form.notices), couldNotLearn)
  const templateId = draft.template.id
  // Every place as the server found it and named it, with the marks that say who put it there.
  let fields = form.spots.map(fieldFromSpot)
  let bound: TemplateVersionResponse = draft.draftVersion
  if (fields.length > 0) {
    const expected = bound.versionNumber
    const all = fields
    bound = await attempt(() => replaceDraftBindings(workspaceId, templateId, expected, all), couldNotLearn)
  }
  // A place the filler never writes into (one in a Word header, say) would stop the whole form from activating, so
  // it is left out, and the person is told which. A PDF's places are on its pages, and each is written where it is.
  let leftOut: string[] = []
  if (form.kind === 'DOCX' && fields.length > 0) {
    const unplaced = await fieldsWithNoPlace(workspaceId, templateId, bound)
    if (unplaced.length > 0) {
      leftOut = fields.filter((field) => unplaced.includes(field.fieldId)).map((field) => fieldLabel(field))
      const placed = fields.filter((field) => !unplaced.includes(field.fieldId))
      fields = placed
      const expected = bound.versionNumber
      bound = await attempt(() => replaceDraftBindings(workspaceId, templateId, expected, placed), couldNotLearn)
    }
  }

  say('preparing')
  const activated = await activate(workspaceId, templateId, bound, fields, fileName)
  // The template is complete now, so My Templates may show it; a failed refresh is the store's own quiet state.
  await useTemplatesStore().refresh(workspaceId)
  return { templateId, versionId: activated.id, name, form, leftOut, mediaType }
}

/**
 * Asks the server to make the upload ready to fill, waiting and asking
 * again while it is too busy to start. `mediaType` is read when a refusal
 * is worded, so it names the format the server found.
 */
async function makeReady(
  workspaceId: number,
  artifactId: number,
  fileName: string,
  mediaType: () => string | null,
  say: (step: LearnStep, waiting?: boolean) => void,
): Promise<FillableFormResponse> {
  for (let tried = 0; ; tried++) {
    try {
      return await makeFillableForm(workspaceId, artifactId)
    } catch (error) {
      if (tried < BUSY_RETRIES && error instanceof ApiRequestError && error.problem?.code === 'RENDERER_BUSY') {
        say('preparing-copy', true)
        await pause(waitSeconds(error))
        say('preparing-copy')
        continue
      }
      throw new Refusal(
        (error instanceof ApiRequestError ? fillableFormRefusal(error.problem, mediaType()) : null) ??
          `Could not open "${fileName}". ${reason(error, 'a way to open forms')}`,
      )
    }
  }
}

/** As long as the server asked, up to the longest this flow waits; the longest when it did not say. */
function waitSeconds(error: ApiRequestError): number {
  const asked = error.retryAfterSeconds ?? LONGEST_WAIT_SECONDS
  return Math.min(Math.max(asked, 0), LONGEST_WAIT_SECONDS)
}

function pause(seconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, seconds * 1000))
}

/**
 * A spot as a template field, in the shape the server sent it: what it
 * holds, where it is, the name the form gives it, and who put it there.
 * What only describes how it was found is not sent, and a mark that means
 * "it came with the form" is left out, as the server stores it.
 */
function fieldFromSpot(spot: FillableFormSpot): FieldDefinitionRequest {
  const field: FieldDefinitionRequest = {
    fieldId: spot.fieldId,
    type: spot.type,
    cardinality: spot.cardinality,
    requiredness: spot.requiredness,
    binding: spot.binding,
  }
  if (spot.label) field.label = spot.label
  if (spot.origin !== 'FORM') field.origin = spot.origin
  if (spot.docxControl && spot.docxControl !== 'ORIGINAL') field.docxControl = spot.docxControl
  if (spot.blankText) field.blankText = spot.blankText
  return field
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
 * value when the server refuses a repeated row it cannot fill. Filled as
 * single values, the same places work, which is what a form laid out as a
 * table of labels and boxes means anyway. A form with no places at all is
 * activated as one, so it opens and the person can add them.
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
  if (fields.length === 0) {
    return attempt(() => activateTemplateVersion(workspaceId, templateId, bound.versionNumber, { allowNoPlaces: true }), couldNotActivate)
  }
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
  // The server says why it refused the file; an older one does not, and is told the kinds of file Brownie fills.
  if (error instanceof ApiRequestError && error.status === 415) return refusalSentence(error.problem?.reason) ?? REFUSAL_SENTENCES.NOT_A_DOCUMENT
  return `Could not upload "${file.name}". ${reason(error, 'a way to upload files')}`
}

/** The rejection reasons are the scanner's and the content inspector's own codes; a file still being checked has none. */
function scanRefusal(artifact: ArtifactResponse, fileName: string): string {
  if (artifact.status !== 'REJECTED') return `Brownie could not finish checking "${fileName}". Try again in a minute.`
  const refused = refusalSentence(artifact.rejectionReason)
  if (refused) return refused
  switch (artifact.rejectionReason) {
    case 'MALWARE_DETECTED':
      return `Brownie did not accept "${fileName}": the malware scan flagged it.`
    case 'UNSUPPORTED_MEDIA_TYPE':
      return REFUSAL_SENTENCES.NOT_A_DOCUMENT
    case 'DECOMPRESSION_LIMIT_EXCEEDED':
      return `Brownie did not accept "${fileName}": it unpacks to far more than Brownie accepts.`
    case 'EXPIRED_ABANDONED_UPLOAD':
      return `Brownie did not accept "${fileName}": the upload took too long to finish. Try again.`
    default:
      return `Brownie did not accept "${fileName}": it did not pass Brownie's checks.`
  }
}

/** A PDF whose form cannot be filled is refused again when a template is made from it; the same words say why. */
function learningRefusal(error: unknown, fileName: string, mediaType: string | null): string {
  const refused = error instanceof ApiRequestError ? fillableFormRefusal(error.problem, mediaType) : null
  return refused ?? `Brownie could not learn the form in "${fileName}". ${reason(error, 'a way to learn forms')}`
}

/** Places the filler never writes into, such as those in a header or footer, are left out of the template. */
function leftOutNote(labels: string[]): string | null {
  if (labels.length === 0) return null
  const quoted = listed(labels.map((label) => `"${label}"`))
  return (
    `Brownie left out ${quoted}: ${labels.length === 1 ? 'it is' : 'they are'} outside the main text of the form, such as in a ` +
    'header or footer, and Brownie fills only the main text.'
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
