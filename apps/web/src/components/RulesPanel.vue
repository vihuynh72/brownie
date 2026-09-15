<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ApiRequestError,
  acceptRule,
  listRules,
  proposeRule,
  rejectRule,
  type RulePayloadRequest,
  type RuleResponse,
  type RuleScopeRequest,
} from '@/api/client'

export interface RuleFieldOption {
  fieldId: string
  cardinality: 'SCALAR' | 'REPEATED'
}

const props = defineProps<{
  workspaceId: number
  templateId: number
  fields: RuleFieldOption[]
}>()

type PayloadKind = RulePayloadRequest['kind']

const PAYLOAD_KIND_OPTIONS: { value: PayloadKind; label: string }[] = [
  { value: 'REQUIRED_FIELDS', label: 'Require fields to have a value' },
  { value: 'MAX_TEXT_LENGTH', label: "Limit a field's text length" },
  { value: 'MAX_ITEM_COUNT', label: "Limit a repeated field's item count" },
  { value: 'ALLOWED_SECTION_ORDER', label: 'Require a section order' },
  { value: 'DATE_DISPLAY_FORMAT', label: "Set a date field's display format" },
  { value: 'ALLOWED_SOURCE_KINDS', label: "Restrict where a field's value may come from" },
  { value: 'MISSING_VALUE_BEHAVIOR', label: 'Set behavior when a field is empty' },
  { value: 'ALLOWED_OVERFLOW_BEHAVIOR', label: 'Set behavior when content overflows' },
  { value: 'REPEATABLE_REGION_EMPTY_BEHAVIOR', label: 'Set behavior when a repeated region has no items' },
  { value: 'PROTECTED_REGION', label: 'Protect a content control from edits' },
]

/** Payload kinds that name exactly one field -- everything except REQUIRED_FIELDS (many fields), ALLOWED_SECTION_ORDER (no field), and PROTECTED_REGION (a binding target, not a field). */
const SINGLE_FIELD_KINDS: PayloadKind[] = [
  'MAX_TEXT_LENGTH',
  'MAX_ITEM_COUNT',
  'DATE_DISPLAY_FORMAT',
  'ALLOWED_SOURCE_KINDS',
  'MISSING_VALUE_BEHAVIOR',
  'ALLOWED_OVERFLOW_BEHAVIOR',
  'REPEATABLE_REGION_EMPTY_BEHAVIOR',
]

/** These two require the field they target to be REPEATED (RulePayloadValidator#requireKnownRepeatedField) -- a scalar field always fails validation, so the Field dropdown only offers REPEATED fields for them. */
const REPEATED_FIELD_KINDS: PayloadKind[] = ['MAX_ITEM_COUNT', 'REPEATABLE_REGION_EMPTY_BEHAVIOR']

const rules = ref<RuleResponse[]>([])
const loadState = ref<'loading' | 'loaded' | 'error'>('loading')
const listError = ref<string | null>(null)
const decidingRuleId = ref<number | null>(null)

const scopeKind = ref<RuleScopeRequest['kind']>('WHOLE_TEMPLATE')
const payloadKind = ref<PayloadKind>('REQUIRED_FIELDS')

const requiredFieldIds = ref<string[]>([])
const singleFieldId = ref('')
const maxCharacters = ref('')
const maxItems = ref('')
const orderedSectionIdsText = ref('')
const dateFormatStyle = ref<'LONG' | 'SHORT' | 'ISO'>('LONG')
const sourceKindArtifact = ref(true)
const emptyValueResolution = ref<'OMIT' | 'BLANK' | 'PLACEHOLDER_TEXT'>('BLANK')
const overflowResolution = ref<'BLOCK_EXPORT' | 'ALLOW_REFLOW'>('BLOCK_EXPORT')
const protectedRegionTag = ref('')
const humanExplanation = ref('')

const formError = ref<string | null>(null)
const formProblems = ref<{ reason?: string; detail?: string }[]>([])
const submitting = ref(false)

async function loadRules(): Promise<void> {
  loadState.value = 'loading'
  listError.value = null
  try {
    rules.value = await listRules(props.workspaceId, props.templateId)
    loadState.value = 'loaded'
  } catch (error) {
    listError.value = error instanceof ApiRequestError ? error.message : "Could not load this template's rules."
    loadState.value = 'error'
  }
}

onMounted(loadRules)

function resetPayloadFields(): void {
  requiredFieldIds.value = []
  singleFieldId.value = ''
  maxCharacters.value = ''
  maxItems.value = ''
  orderedSectionIdsText.value = ''
  dateFormatStyle.value = 'LONG'
  sourceKindArtifact.value = true
  emptyValueResolution.value = 'BLANK'
  overflowResolution.value = 'BLOCK_EXPORT'
  protectedRegionTag.value = ''
}

const needsSingleField = computed<boolean>(() => SINGLE_FIELD_KINDS.includes(payloadKind.value))

/**
 * The backend (RulePayloadValidator#validateScopeCompatibility) unconditionally rejects SINGLE_FIELD scope for
 * ALLOWED_SECTION_ORDER and PROTECTED_REGION, and for every other field-specific kind requires the scope's own
 * fieldId to exactly equal the payload's fieldId. Restricting "Applies to: Single field" to the same kinds that
 * already show the Field dropdown -- and deriving the scope's fieldId from that same selection below, rather than
 * a second independent one -- means the two can never disagree.
 */
watch(payloadKind, () => {
  resetPayloadFields()
  if (!needsSingleField.value) {
    scopeKind.value = 'WHOLE_TEMPLATE'
  }
})

const orderedSectionIds = computed<string[]>(() =>
  orderedSectionIdsText.value
    .split(',')
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0),
)

/** MAX_ITEM_COUNT and REPEATABLE_REGION_EMPTY_BEHAVIOR only validate against a REPEATED field (RulePayloadValidator#requireKnownRepeatedField); a SCALAR field always fails, so it is never offered for those two kinds. */
const availableSingleFields = computed<RuleFieldOption[]>(() =>
  REPEATED_FIELD_KINDS.includes(payloadKind.value)
    ? props.fields.filter((field) => field.cardinality === 'REPEATED')
    : props.fields,
)

const canSubmit = computed<boolean>(() => {
  if (scopeKind.value === 'SINGLE_FIELD' && singleFieldId.value === '') return false
  if (needsSingleField.value && singleFieldId.value === '') return false
  switch (payloadKind.value) {
    case 'REQUIRED_FIELDS':
      return requiredFieldIds.value.length > 0
    case 'MAX_TEXT_LENGTH':
      return Number(maxCharacters.value) > 0
    case 'MAX_ITEM_COUNT':
      return Number(maxItems.value) > 0
    case 'ALLOWED_SECTION_ORDER':
      return orderedSectionIds.value.length > 0
    case 'ALLOWED_SOURCE_KINDS':
      return sourceKindArtifact.value
    case 'PROTECTED_REGION':
      return protectedRegionTag.value.trim() !== ''
    default:
      return true
  }
})

function buildPayload(): RulePayloadRequest {
  switch (payloadKind.value) {
    case 'REQUIRED_FIELDS':
      return { kind: 'REQUIRED_FIELDS', fieldIds: requiredFieldIds.value }
    case 'MAX_TEXT_LENGTH':
      return { kind: 'MAX_TEXT_LENGTH', fieldId: singleFieldId.value, maxCharacters: Number(maxCharacters.value) }
    case 'MAX_ITEM_COUNT':
      return { kind: 'MAX_ITEM_COUNT', fieldId: singleFieldId.value, maxItems: Number(maxItems.value) }
    case 'ALLOWED_SECTION_ORDER':
      return { kind: 'ALLOWED_SECTION_ORDER', orderedSectionIds: orderedSectionIds.value }
    case 'DATE_DISPLAY_FORMAT':
      return { kind: 'DATE_DISPLAY_FORMAT', fieldId: singleFieldId.value, dateFormatStyle: dateFormatStyle.value }
    case 'ALLOWED_SOURCE_KINDS':
      return { kind: 'ALLOWED_SOURCE_KINDS', fieldId: singleFieldId.value, allowedSourceKinds: ['ARTIFACT'] }
    case 'MISSING_VALUE_BEHAVIOR':
      return {
        kind: 'MISSING_VALUE_BEHAVIOR',
        fieldId: singleFieldId.value,
        emptyValueResolution: emptyValueResolution.value,
      }
    case 'ALLOWED_OVERFLOW_BEHAVIOR':
      return {
        kind: 'ALLOWED_OVERFLOW_BEHAVIOR',
        fieldId: singleFieldId.value,
        overflowResolution: overflowResolution.value,
      }
    case 'REPEATABLE_REGION_EMPTY_BEHAVIOR':
      return {
        kind: 'REPEATABLE_REGION_EMPTY_BEHAVIOR',
        fieldId: singleFieldId.value,
        emptyValueResolution: emptyValueResolution.value,
      }
    case 'PROTECTED_REGION':
      return {
        kind: 'PROTECTED_REGION',
        protectedRegionTarget: { kind: 'CONTENT_CONTROL_TAG', tag: protectedRegionTag.value.trim() },
      }
  }
}

async function submitProposal(): Promise<void> {
  if (!canSubmit.value) return
  const scope: RuleScopeRequest =
    scopeKind.value === 'WHOLE_TEMPLATE' ? { kind: 'WHOLE_TEMPLATE' } : { kind: 'SINGLE_FIELD', fieldId: singleFieldId.value }
  const payload = buildPayload()
  const explanation = humanExplanation.value.trim()

  submitting.value = true
  formError.value = null
  formProblems.value = []
  try {
    await proposeRule(props.workspaceId, props.templateId, scope, payload, explanation === '' ? undefined : explanation)
    scopeKind.value = 'WHOLE_TEMPLATE'
    payloadKind.value = 'REQUIRED_FIELDS'
    resetPayloadFields()
    humanExplanation.value = ''
    await loadRules()
  } catch (error) {
    if (error instanceof ApiRequestError) {
      formError.value = error.message
      // RULE_VALIDATION_FAILED (422) carries one itemized reason per thing wrong with the payload -- the
      // top-level detail alone just says "see the problems below", so it is useless without these.
      formProblems.value = error.problem?.problems ?? []
    } else {
      formError.value = 'Could not propose this rule.'
    }
  } finally {
    submitting.value = false
  }
}

async function decide(rule: RuleResponse, action: 'accept' | 'reject'): Promise<void> {
  decidingRuleId.value = rule.id
  listError.value = null
  try {
    if (action === 'accept') {
      await acceptRule(props.workspaceId, props.templateId, rule.id)
    } else {
      await rejectRule(props.workspaceId, props.templateId, rule.id)
    }
    await loadRules()
  } catch (error) {
    listError.value = error instanceof ApiRequestError ? error.message : `Could not ${action} this rule.`
  } finally {
    decidingRuleId.value = null
  }
}

function describeScope(scope: RuleScopeRequest): string {
  return scope.kind === 'WHOLE_TEMPLATE' ? 'Whole template' : `Field: ${scope.fieldId}`
}

const DATE_FORMAT_LABELS: Record<string, string> = {
  LONG: 'long (September 10, 2026)',
  SHORT: 'short (9/10/2026)',
  ISO: 'ISO (2026-09-10)',
}
const EMPTY_VALUE_LABELS: Record<string, string> = {
  OMIT: 'omit it',
  BLANK: 'leave it blank',
  PLACEHOLDER_TEXT: 'show placeholder text',
}
const OVERFLOW_LABELS: Record<string, string> = {
  BLOCK_EXPORT: 'block export',
  ALLOW_REFLOW: 'allow the layout to reflow',
}

function describePayload(payload: RulePayloadRequest): string {
  switch (payload.kind) {
    case 'REQUIRED_FIELDS':
      return `Require: ${(payload.fieldIds ?? []).join(', ')}`
    case 'MAX_TEXT_LENGTH':
      return `${payload.fieldId}: at most ${payload.maxCharacters} characters`
    case 'MAX_ITEM_COUNT':
      return `${payload.fieldId}: at most ${payload.maxItems} items`
    case 'ALLOWED_SECTION_ORDER':
      return `Section order: ${(payload.orderedSectionIds ?? []).join(' → ')}`
    case 'DATE_DISPLAY_FORMAT':
      return `${payload.fieldId}: show dates as ${DATE_FORMAT_LABELS[payload.dateFormatStyle ?? 'LONG']}`
    case 'ALLOWED_SOURCE_KINDS':
      return `${payload.fieldId}: only allow values from ${(payload.allowedSourceKinds ?? []).join(', ').toLowerCase()}`
    case 'MISSING_VALUE_BEHAVIOR':
      return `${payload.fieldId}: when empty, ${EMPTY_VALUE_LABELS[payload.emptyValueResolution ?? 'BLANK']}`
    case 'ALLOWED_OVERFLOW_BEHAVIOR':
      return `${payload.fieldId}: on overflow, ${OVERFLOW_LABELS[payload.overflowResolution ?? 'BLOCK_EXPORT']}`
    case 'REPEATABLE_REGION_EMPTY_BEHAVIOR':
      return `${payload.fieldId}: when no items, ${EMPTY_VALUE_LABELS[payload.emptyValueResolution ?? 'BLANK']}`
    case 'PROTECTED_REGION':
      return `Protect content control "${payload.protectedRegionTarget?.tag ?? ''}" from edits`
    default:
      return payload.kind
  }
}
</script>

<template>
  <div class="rules-panel">
    <p v-if="loadState === 'loading'" aria-live="polite">Loading rules…</p>
    <p v-if="listError" class="field-error" role="alert">{{ listError }}</p>

    <div v-if="loadState !== 'loading'">
      <p v-if="rules.length === 0" class="field-hint">No rules proposed yet.</p>
      <ul v-else class="rule-list">
        <li v-for="rule in rules" :key="rule.id" class="rule-row">
          <div class="rule-row__body">
            <span class="rule-row__scope">{{ describeScope(rule.scope) }}</span>
            <span>{{ describePayload(rule.payload) }}</span>
            <p v-if="rule.humanExplanation" class="field-hint">{{ rule.humanExplanation }}</p>
          </div>
          <div class="rule-row__actions">
            <span v-if="rule.status !== 'PROPOSED'" class="badge">{{ rule.status }}</span>
            <template v-else>
              <button class="button" type="button" :disabled="decidingRuleId === rule.id" @click="decide(rule, 'accept')">
                Accept
              </button>
              <button class="button" type="button" :disabled="decidingRuleId === rule.id" @click="decide(rule, 'reject')">
                Reject
              </button>
            </template>
          </div>
        </li>
      </ul>
    </div>

    <div class="card rule-form">
      <h3>Propose a rule</h3>

      <div class="field">
        <label class="field-label" for="rule-scope-kind">Applies to</label>
        <select id="rule-scope-kind" v-model="scopeKind">
          <option value="WHOLE_TEMPLATE">Whole template</option>
          <option v-if="needsSingleField" value="SINGLE_FIELD">Single field</option>
        </select>
        <p v-if="scopeKind === 'SINGLE_FIELD'" class="field-hint">Applies to the field chosen below.</p>
      </div>

      <div class="field">
        <label class="field-label" for="rule-payload-kind">Rule kind</label>
        <select id="rule-payload-kind" v-model="payloadKind">
          <option v-for="option in PAYLOAD_KIND_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
        </select>
      </div>

      <fieldset v-if="payloadKind === 'REQUIRED_FIELDS'" class="field rule-fieldset">
        <legend class="field-label">Required fields</legend>
        <label v-for="field in fields" :key="field.fieldId" class="rule-checkbox-row">
          <input v-model="requiredFieldIds" type="checkbox" :value="field.fieldId" />
          {{ field.fieldId }}
        </label>
      </fieldset>

      <div v-if="needsSingleField" class="field">
        <label class="field-label" for="rule-single-field">Field</label>
        <select id="rule-single-field" v-model="singleFieldId">
          <option value="" disabled>Choose a field</option>
          <option v-for="field in availableSingleFields" :key="field.fieldId" :value="field.fieldId">{{ field.fieldId }}</option>
        </select>
        <p v-if="REPEATED_FIELD_KINDS.includes(payloadKind) && availableSingleFields.length === 0" class="field-hint">
          No repeated fields yet -- this rule kind only applies to a field marked Repeated.
        </p>
      </div>

      <div v-if="payloadKind === 'MAX_TEXT_LENGTH'" class="field">
        <label class="field-label" for="rule-max-characters">Maximum characters</label>
        <input id="rule-max-characters" v-model="maxCharacters" type="number" min="1" />
      </div>

      <div v-if="payloadKind === 'MAX_ITEM_COUNT'" class="field">
        <label class="field-label" for="rule-max-items">Maximum items</label>
        <input id="rule-max-items" v-model="maxItems" type="number" min="1" />
      </div>

      <div v-if="payloadKind === 'ALLOWED_SECTION_ORDER'" class="field">
        <label class="field-label" for="rule-section-order">Section order (comma-separated)</label>
        <input id="rule-section-order" v-model="orderedSectionIdsText" type="text" placeholder="intro, decisions, action items" />
      </div>

      <div v-if="payloadKind === 'DATE_DISPLAY_FORMAT'" class="field">
        <label class="field-label" for="rule-date-format">Date format</label>
        <select id="rule-date-format" v-model="dateFormatStyle">
          <option value="LONG">Long -- September 10, 2026</option>
          <option value="SHORT">Short -- 9/10/2026</option>
          <option value="ISO">ISO -- 2026-09-10</option>
        </select>
      </div>

      <div v-if="payloadKind === 'ALLOWED_SOURCE_KINDS'" class="field">
        <label class="rule-checkbox-row">
          <input v-model="sourceKindArtifact" type="checkbox" />
          Allow values from uploaded artifacts
        </label>
      </div>

      <div v-if="payloadKind === 'MISSING_VALUE_BEHAVIOR' || payloadKind === 'REPEATABLE_REGION_EMPTY_BEHAVIOR'" class="field">
        <label class="field-label" for="rule-empty-resolution">When empty</label>
        <select id="rule-empty-resolution" v-model="emptyValueResolution">
          <option value="OMIT">Omit it</option>
          <option value="BLANK">Leave it blank</option>
          <option value="PLACEHOLDER_TEXT">Show placeholder text</option>
        </select>
      </div>

      <div v-if="payloadKind === 'ALLOWED_OVERFLOW_BEHAVIOR'" class="field">
        <label class="field-label" for="rule-overflow-resolution">On overflow</label>
        <select id="rule-overflow-resolution" v-model="overflowResolution">
          <option value="BLOCK_EXPORT">Block export</option>
          <option value="ALLOW_REFLOW">Allow the layout to reflow</option>
        </select>
      </div>

      <div v-if="payloadKind === 'PROTECTED_REGION'" class="field">
        <label class="field-label" for="rule-protected-tag">Content control tag</label>
        <input id="rule-protected-tag" v-model="protectedRegionTag" type="text" placeholder="signature-block" />
      </div>

      <div class="field">
        <label class="field-label" for="rule-explanation">Explanation (optional)</label>
        <input id="rule-explanation" v-model="humanExplanation" type="text" placeholder="Why this rule exists" />
      </div>

      <div v-if="formError" class="field-error" role="alert">
        <p>{{ formError }}</p>
        <ul v-if="formProblems.length > 0">
          <li v-for="(problem, index) in formProblems" :key="index">{{ problem.detail ?? problem.reason }}</li>
        </ul>
      </div>

      <button class="button button--primary" type="button" :disabled="!canSubmit || submitting" @click="submitProposal">
        {{ submitting ? 'Proposing…' : 'Propose rule' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.field {
  margin-bottom: var(--space-4);
}

.field select,
.field input[type='text'],
.field input[type='number'] {
  width: 100%;
  min-height: var(--control-height);
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
}

.rule-fieldset {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: var(--space-3);
}

.rule-checkbox-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-1);
}

.rule-list {
  list-style: none;
  margin: 0 0 var(--space-5);
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.rule-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2) var(--space-4);
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.rule-row__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.rule-row__scope {
  font-weight: 600;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.rule-row__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.badge {
  display: inline-block;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius);
  background: var(--color-honey-soft);
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.rule-form {
  margin-top: var(--space-4);
}
</style>
