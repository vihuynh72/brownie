<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import RulesPanel from '@/components/RulesPanel.vue'
import {
  ApiRequestError,
  activateTemplateVersion,
  allocateUpload,
  completeUpload,
  createTemplateDraft,
  extractArtifact,
  getDraftCandidateBindings,
  replaceDraftBindings,
  uploadArtifactContent,
  type FieldDefinitionRequest,
} from '@/api/client'

const session = useSessionStore()

type Stage = 'upload' | 'extracting' | 'bind-fields' | 'rules' | 'activating' | 'activated'
const stage = ref<Stage>('upload')
const errorMessage = ref<string | null>(null)

const displayName = ref('')
const templateWorkspaceId = ref<number | null>(null)
const templateId = ref<number | null>(null)
const draftVersionNumber = ref<number | null>(null)
const ambiguousTags = ref<string[]>([])
const savingFields = ref(false)

type FieldType = 'TEXT' | 'DATE'
type FieldCardinality = 'SCALAR' | 'REPEATED'
type FieldRequiredness = 'REQUIRED' | 'OPTIONAL'

interface EditableField {
  fieldId: string
  type: FieldType
  cardinality: FieldCardinality
  requiredness: FieldRequiredness
  tag: string
}
const fields = ref<EditableField[]>([])

function addField(): void {
  fields.value.push({ fieldId: '', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', tag: '' })
}

function removeField(index: number): void {
  fields.value.splice(index, 1)
}

async function onFileChosen(event: Event): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (workspaceId === undefined || !file || displayName.value.trim() === '') return

  stage.value = 'extracting'
  errorMessage.value = null
  try {
    const allocated = await allocateUpload(workspaceId, file.name)
    await uploadArtifactContent(workspaceId, allocated.id, file)
    const completed = await completeUpload(workspaceId, allocated.id)
    if (completed.status !== 'READY') {
      errorMessage.value = `Your file was not accepted (${completed.rejectionReason ?? completed.status}).`
      stage.value = 'upload'
      return
    }
    const extraction = await extractArtifact(workspaceId, allocated.id)
    if (extraction.status !== 'COMPLETE') {
      errorMessage.value = `Could not read this file's structure (${extraction.status}). Try a different DOCX.`
      stage.value = 'upload'
      return
    }

    const draft = await createTemplateDraft(workspaceId, displayName.value.trim(), allocated.id)
    templateWorkspaceId.value = workspaceId
    templateId.value = draft.template.id
    draftVersionNumber.value = draft.draftVersion.versionNumber

    const candidateReport = await getDraftCandidateBindings(workspaceId, draft.template.id)
    fields.value = candidateReport.candidates.map((candidate) => ({
      fieldId: candidate.fieldId,
      type: candidate.type,
      cardinality: candidate.cardinality,
      requiredness: 'OPTIONAL',
      tag: candidate.contentControlTag,
    }))
    ambiguousTags.value = candidateReport.ambiguousContentControlTags
    stage.value = 'bind-fields'
  } catch (error) {
    errorMessage.value = error instanceof ApiRequestError ? error.message : 'Something went wrong reading that file.'
    stage.value = 'upload'
  }
}

/**
 * Only replaces the draft's own field bindings -- a rule can only be
 * proposed once these are saved, since proposing one against a field ID
 * the draft does not yet know about is rejected. Activation is a separate,
 * later step (see activateVersion) so the rules stage always has real
 * fields to propose rules against.
 */
async function saveFields(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || templateId.value === null || draftVersionNumber.value === null) return

  savingFields.value = true
  errorMessage.value = null
  try {
    const payload: FieldDefinitionRequest[] = fields.value.map((field) => ({
      fieldId: field.fieldId.trim(),
      type: field.type,
      cardinality: field.cardinality,
      requiredness: field.requiredness,
      binding: { kind: 'CONTENT_CONTROL_TAG', tag: field.tag.trim() },
    }))
    const updated = await replaceDraftBindings(workspaceId, templateId.value, draftVersionNumber.value, payload)
    draftVersionNumber.value = updated.versionNumber
    stage.value = 'rules'
  } catch (error) {
    errorMessage.value = error instanceof ApiRequestError ? error.message : 'Something went wrong saving these fields.'
  } finally {
    savingFields.value = false
  }
}

async function activateVersion(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || templateId.value === null || draftVersionNumber.value === null) return

  stage.value = 'activating'
  errorMessage.value = null
  try {
    await activateTemplateVersion(workspaceId, templateId.value, draftVersionNumber.value)
    stage.value = 'activated'
  } catch (error) {
    stage.value = 'rules'
    errorMessage.value = error instanceof ApiRequestError ? error.message : 'Something went wrong activating this template.'
  }
}
</script>

<template>
  <section v-if="session.status === 'anonymous'" class="card">
    <p>Sign in to teach a new template.</p>
    <a class="button button--primary" href="/oauth2/authorization/entra">Sign in</a>
  </section>

  <section v-else class="card new-template">
    <h1>Teach a new template</h1>

    <template v-if="stage === 'upload' || stage === 'extracting'">
      <p class="field-hint">
        Upload a DOCX with named content controls for the fields you want Brownie to fill in -- each control's own
        tag becomes a suggested field.
      </p>
      <div class="field">
        <label class="field-label" for="display-name">Template name</label>
        <input id="display-name" v-model="displayName" type="text" placeholder="e.g. Club Bylaws Minutes" />
      </div>
      <div class="field">
        <label class="field-label" for="template-source">Source DOCX</label>
        <input
          id="template-source"
          type="file"
          accept=".docx"
          :disabled="stage === 'extracting' || displayName.trim() === ''"
          @change="onFileChosen"
        />
      </div>
      <p v-if="stage === 'extracting'" aria-live="polite">Uploading and reading the document's structure…</p>
      <p v-if="errorMessage" class="field-error" role="alert">{{ errorMessage }}</p>
    </template>

    <template v-else-if="stage === 'bind-fields'">
      <p class="field-hint">
        Suggested from your document's own content controls -- edit, remove, or add a field, then save.
      </p>
      <ul v-if="ambiguousTags.length > 0" class="field-hint ambiguous-list">
        <li v-for="tag in ambiguousTags" :key="tag">
          "{{ tag }}" appears more than once in the document and was not suggested automatically.
        </li>
      </ul>

      <div v-for="(field, index) in fields" :key="index" class="field-row-edit">
        <input v-model="field.fieldId" type="text" placeholder="field.id" aria-label="Field ID" />
        <select v-model="field.type" aria-label="Field type">
          <option value="TEXT">Text</option>
          <option value="DATE">Date</option>
        </select>
        <select v-model="field.cardinality" aria-label="Field cardinality">
          <option value="SCALAR">One value</option>
          <option value="REPEATED">Repeated</option>
        </select>
        <select v-model="field.requiredness" aria-label="Field requiredness">
          <option value="OPTIONAL">Optional</option>
          <option value="REQUIRED">Required</option>
        </select>
        <input v-model="field.tag" type="text" placeholder="content control tag" aria-label="Content control tag" />
        <button
          class="button"
          type="button"
          :aria-label="`Remove ${field.fieldId.trim() || 'field ' + (index + 1)}`"
          @click="removeField(index)"
        >
          Remove
        </button>
      </div>
      <button class="button" type="button" @click="addField">Add field</button>

      <p v-if="errorMessage" class="field-error" role="alert">{{ errorMessage }}</p>

      <button
        class="button button--primary"
        type="button"
        :disabled="fields.length === 0 || savingFields"
        @click="saveFields"
      >
        {{ savingFields ? 'Saving…' : 'Save fields' }}
      </button>
      <p v-if="savingFields" aria-live="polite">Saving…</p>
    </template>

    <template v-else-if="stage === 'rules' || stage === 'activating'">
      <p class="field-hint">
        Propose rules against these fields, then activate the template once its rules look right. An activated
        template's draft is gone, so this is the last chance to propose or decide a rule against it.
      </p>
      <RulesPanel
        v-if="templateId !== null && templateWorkspaceId !== null"
        :workspace-id="templateWorkspaceId"
        :template-id="templateId"
        :fields="
          fields
            .filter((field) => field.fieldId.trim() !== '')
            .map((field) => ({ fieldId: field.fieldId.trim(), cardinality: field.cardinality }))
        "
      />

      <p v-if="errorMessage" class="field-error" role="alert">{{ errorMessage }}</p>

      <button class="button button--primary" type="button" :disabled="stage === 'activating'" @click="activateVersion">
        {{ stage === 'activating' ? 'Activating…' : 'Activate template' }}
      </button>
      <p v-if="stage === 'activating'" aria-live="polite">Activating…</p>
    </template>

    <template v-else-if="stage === 'activated'">
      <p>"{{ displayName }}" is now active and ready to use.</p>
      <div class="wizard-actions">
        <RouterLink class="button button--primary" to="/documents/new">Create a document from it</RouterLink>
        <RouterLink class="button" to="/">Back to your documents</RouterLink>
      </div>
    </template>
  </section>
</template>

<style scoped>
.new-template {
  max-width: 48rem;
}

.field {
  margin-bottom: var(--space-4);
}

.field select,
.field input[type='text'] {
  width: 100%;
  min-height: var(--control-height);
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
}

.ambiguous-list {
  padding-left: var(--space-5);
}

.field-row-edit {
  display: grid;
  grid-template-columns: 1.5fr 1fr 1fr 1fr 1.5fr auto;
  gap: var(--space-2);
  align-items: center;
  margin-bottom: var(--space-2);
}

.field-row-edit input,
.field-row-edit select {
  min-height: var(--control-height);
  padding: 0 var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
}

@media (max-width: 720px) {
  .field-row-edit {
    grid-template-columns: 1fr 1fr;
  }
}

.wizard-actions {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-4);
}
</style>
