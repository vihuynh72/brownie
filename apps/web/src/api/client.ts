import type { components } from './schema'

export type MeResponse = components['schemas']['MeResponse']
export type DocumentSummaryResponse = components['schemas']['DocumentSummaryResponse']
export type DocumentResponse = components['schemas']['DocumentResponse']
export type CreateDocumentRequest = components['schemas']['CreateDocumentRequest']
export type TemplateResponse = components['schemas']['TemplateResponse']
export type TemplateVersionResponse = components['schemas']['TemplateVersionResponse']
export type ArtifactResponse = components['schemas']['ArtifactResponse']
export type ExtractionResponse = components['schemas']['ExtractionResponse']
export type SnapshotResponse = components['schemas']['SnapshotResponse']
export type JobResponse = components['schemas']['JobResponse']
export type CommandReceiptResponse = components['schemas']['CommandReceiptResponse']
export type ExtractionResultResponse = components['schemas']['ExtractionResultResponse']
export type QuestionResponse = components['schemas']['QuestionResponse']
export type PatchProposalResponse = components['schemas']['PatchProposalResponse']
export type PatchAcceptResponse = components['schemas']['PatchAcceptResponse']
export type DocumentRevisionResponse = components['schemas']['DocumentRevisionResponse']
export type ReviewDecision = components['schemas']['RecordReviewDecisionRequest']['decision']
export type FieldLock = components['schemas']['SetFieldLockRequest']['lock']
export type TemplateDraftResponse = components['schemas']['TemplateDraftResponse']
export type CandidateBindingReportResponse = components['schemas']['CandidateBindingReportResponse']
export type FieldDefinitionRequest = components['schemas']['FieldDefinitionRequest']
export type RuleScopeRequest = components['schemas']['RuleScopeRequest']
export type RulePayloadRequest = components['schemas']['RulePayloadRequest']
export type ProposeRuleRequest = components['schemas']['ProposeRuleRequest']
export type RuleResponse = components['schemas']['RuleResponse']
export type ApiError = components['schemas']['Error']

/** Thrown for any non-2xx response; carries the server's own structured problem body when it sent one. */
export class ApiRequestError extends Error {
  readonly status: number
  readonly problem: ApiError | undefined

  constructor(status: number, problem: ApiError | undefined) {
    super(problem?.detail ?? problem?.title ?? `Request failed with status ${status}`)
    this.status = status
    this.problem = problem
  }
}

function readCookie(name: string): string | undefined {
  const match = document.cookie.split('; ').find((row) => row.startsWith(`${name}=`))
  return match ? decodeURIComponent(match.substring(name.length + 1)) : undefined
}

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

interface RequestOptions {
  method?: string
  body?: unknown
  /** Raw (non-JSON) body, e.g. artifact bytes for an upload. Mutually exclusive with body. */
  rawBody?: BodyInit
  headers?: Record<string, string>
}

/**
 * A thin, typed fetch wrapper -- not a generated client -- built on top of
 * the OpenAPI-generated request/response types above. It handles the two
 * things every call needs: same-origin session cookies, and the
 * double-submit CSRF header the backend requires on every mutation (see
 * the repository README's CSRF section: the browser echoes the
 * XSRF-TOKEN cookie back as X-XSRF-TOKEN).
 */
async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { ...options.headers }
  if (MUTATING_METHODS.has(method)) {
    const token = readCookie('XSRF-TOKEN')
    if (token) {
      headers['X-XSRF-TOKEN'] = token
    }
  }

  let body: BodyInit | undefined
  if (options.rawBody !== undefined) {
    body = options.rawBody
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  const response = await fetch(path, {
    method,
    headers,
    body,
    credentials: 'include',
  })

  if (response.status === 204) {
    return undefined as T
  }

  const contentType = response.headers.get('content-type') ?? ''
  const isJson = contentType.includes('json')
  const payload = isJson ? await response.json() : undefined

  if (!response.ok) {
    throw new ApiRequestError(response.status, isJson ? (payload as ApiError) : undefined)
  }
  return payload as T
}

export function getCurrentIdentity(): Promise<MeResponse> {
  return request<MeResponse>('/api/v1/me')
}

export function listDocuments(workspaceId: number): Promise<DocumentSummaryResponse[]> {
  return request(`/api/v1/workspaces/${workspaceId}/documents`)
}

export function getDocument(workspaceId: number, documentId: number): Promise<DocumentResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}`)
}

export function createDocument(
  workspaceId: number,
  idempotencyKey: string,
  body: CreateDocumentRequest,
): Promise<DocumentResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/documents`, {
    method: 'POST',
    body,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function listTemplates(workspaceId: number): Promise<TemplateResponse[]> {
  return request(`/api/v1/workspaces/${workspaceId}/templates`)
}

export function getTemplateVersion(
  workspaceId: number,
  templateId: number,
  versionId: number,
): Promise<TemplateVersionResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/templates/${templateId}/versions/${versionId}`)
}

export function allocateUpload(workspaceId: number, filename: string): Promise<ArtifactResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/uploads`, {
    method: 'POST',
    body: { filename },
  })
}

export function uploadArtifactContent(
  workspaceId: number,
  artifactId: number,
  file: File,
): Promise<ArtifactResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/uploads/${artifactId}/content`, {
    method: 'PUT',
    rawBody: file,
    headers: { 'Content-Type': 'application/octet-stream' },
  })
}

export function completeUpload(workspaceId: number, artifactId: number): Promise<ArtifactResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/uploads/${artifactId}/complete`, { method: 'POST' })
}

export function extractArtifact(workspaceId: number, artifactId: number): Promise<ExtractionResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/artifacts/${artifactId}/extraction`, { method: 'POST' })
}

export function attachSource(workspaceId: number, artifactId: number): Promise<SnapshotResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/sources`, {
    method: 'POST',
    body: { artifactId },
  })
}

export function startExtraction(
  workspaceId: number,
  documentId: number,
  sourceArtifactId: number,
  idempotencyKey: string,
): Promise<CommandReceiptResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/generations`, {
    method: 'POST',
    body: { sourceArtifactId },
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function getJob(workspaceId: number, jobId: number): Promise<JobResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/jobs/${jobId}`)
}

export function getExtractionResult(workspaceId: number, documentId: number, jobId: number): Promise<ExtractionResultResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/generations/${jobId}/result`)
}

export function getGenerationQuestions(workspaceId: number, documentId: number, jobId: number): Promise<QuestionResponse[]> {
  return request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/generations/${jobId}/questions`)
}

export function answerQuestion(workspaceId: number, questionId: number, answerValue: string): Promise<QuestionResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/questions/${questionId}/answer`, {
    method: 'POST',
    body: { answerValue },
  })
}

export function resumeGeneration(
  workspaceId: number,
  documentId: number,
  jobId: number,
  idempotencyKey: string,
): Promise<CommandReceiptResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/generations/${jobId}/resume`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function applyGenerationResult(workspaceId: number, documentId: number, jobId: number): Promise<PatchProposalResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/generations/${jobId}/apply`, { method: 'POST' })
}

export function acceptPatchProposal(
  workspaceId: number,
  documentId: number,
  proposalId: number,
  expectedRevisionId: number,
  idempotencyKey: string,
): Promise<PatchAcceptResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/patch-proposals/${proposalId}/accept`, {
    method: 'POST',
    body: { expectedRevisionId },
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function recordReviewDecision(
  workspaceId: number,
  documentId: number,
  expectedRevisionId: number,
  fieldId: string,
  decision: ReviewDecision,
  idempotencyKey: string,
): Promise<DocumentRevisionResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/fields/review-decision`, {
    method: 'POST',
    body: { expectedRevisionId, fieldId, decision },
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function setFieldLock(
  workspaceId: number,
  documentId: number,
  expectedRevisionId: number,
  fieldId: string,
  lock: FieldLock,
  idempotencyKey: string,
): Promise<DocumentRevisionResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/fields/lock`, {
    method: 'POST',
    body: { expectedRevisionId, fieldId, lock },
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function createTemplateDraft(workspaceId: number, displayName: string, sourceArtifactId: number): Promise<TemplateDraftResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/templates`, {
    method: 'POST',
    body: { displayName, sourceArtifactId },
  })
}

export function getDraftCandidateBindings(workspaceId: number, templateId: number): Promise<CandidateBindingReportResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/templates/${templateId}/draft/candidate-bindings`)
}

export function replaceDraftBindings(
  workspaceId: number,
  templateId: number,
  expectedVersionNumber: number,
  fields: FieldDefinitionRequest[],
): Promise<TemplateVersionResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/templates/${templateId}/draft/bindings`, {
    method: 'PUT',
    body: { expectedVersionNumber, fields },
  })
}

export function activateTemplateVersion(
  workspaceId: number,
  templateId: number,
  expectedVersionNumber: number,
): Promise<TemplateVersionResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/templates/${templateId}/versions`, {
    method: 'POST',
    body: { expectedVersionNumber },
  })
}

export function proposeRule(
  workspaceId: number,
  templateId: number,
  scope: RuleScopeRequest,
  payload: RulePayloadRequest,
  humanExplanation?: string,
): Promise<RuleResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/templates/${templateId}/rules`, {
    method: 'POST',
    body: { scope, payload, humanExplanation: humanExplanation ?? null },
  })
}

export function listRules(workspaceId: number, templateId: number): Promise<RuleResponse[]> {
  return request(`/api/v1/workspaces/${workspaceId}/templates/${templateId}/rules`)
}

export function getRule(workspaceId: number, templateId: number, ruleId: number): Promise<RuleResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/templates/${templateId}/rules/${ruleId}`)
}

export function acceptRule(workspaceId: number, templateId: number, ruleId: number): Promise<RuleResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/templates/${templateId}/rules/${ruleId}/accept`, { method: 'POST' })
}

export function rejectRule(workspaceId: number, templateId: number, ruleId: number): Promise<RuleResponse> {
  return request(`/api/v1/workspaces/${workspaceId}/templates/${templateId}/rules/${ruleId}/reject`, { method: 'POST' })
}
