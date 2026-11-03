<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import {
  deleteAllDetails,
  deleteDetail,
  listDetails,
  saveDetail,
  type SavedDetailResponse,
} from '@/api/client'
import { describeCommonFailure } from '@/api/failures'

/**
 * "Your details": the details Brownie keeps after a person approves a form,
 * grouped the way it files them, so the next form's fill starts from them.
 * A non-sensitive row is editable in place; a sensitive one only ever shows
 * its hint -- the value is sealed and stays sealed here -- and the only way
 * one comes to exist is the "Add a detail" picker's save below.
 */

type DetailKeyOption = { key: string; label: string; group: string; sensitive: boolean }

/** The picker offers every canonical key, mirroring the server's own DetailKey order. */
const DETAIL_KEY_OPTIONS: DetailKeyOption[] = [
  { key: 'person.full_name', label: 'Full name', group: 'IDENTITY', sensitive: false },
  { key: 'person.first_name', label: 'First name', group: 'IDENTITY', sensitive: false },
  { key: 'person.middle_name', label: 'Middle name', group: 'IDENTITY', sensitive: false },
  { key: 'person.last_name', label: 'Last name', group: 'IDENTITY', sensitive: false },
  { key: 'person.preferred_name', label: 'Preferred name', group: 'IDENTITY', sensitive: false },
  { key: 'person.date_of_birth', label: 'Date of birth', group: 'IDENTITY', sensitive: false },
  { key: 'contact.email', label: 'Email', group: 'CONTACT', sensitive: false },
  { key: 'contact.phone_mobile', label: 'Mobile phone', group: 'CONTACT', sensitive: false },
  { key: 'contact.phone_home', label: 'Home phone', group: 'CONTACT', sensitive: false },
  { key: 'address.line1', label: 'Street address', group: 'ADDRESS', sensitive: false },
  { key: 'address.unit', label: 'Apartment, suite or unit', group: 'ADDRESS', sensitive: false },
  { key: 'address.city', label: 'City', group: 'ADDRESS', sensitive: false },
  { key: 'address.state', label: 'State', group: 'ADDRESS', sensitive: false },
  { key: 'address.postal_code', label: 'ZIP or postal code', group: 'ADDRESS', sensitive: false },
  { key: 'address.country', label: 'Country', group: 'ADDRESS', sensitive: false },
  { key: 'work.employer', label: 'Employer', group: 'WORK', sensitive: false },
  { key: 'work.job_title', label: 'Job title', group: 'WORK', sensitive: false },
  { key: 'work.start_date', label: 'Start date', group: 'WORK', sensitive: false },
  { key: 'work.employer_phone', label: "Employer's phone", group: 'WORK', sensitive: false },
  { key: 'work.employer_address', label: "Employer's address", group: 'WORK', sensitive: false },
  { key: 'income.monthly_gross', label: 'Monthly gross income', group: 'INCOME', sensitive: false },
  { key: 'income.annual_gross', label: 'Annual gross income', group: 'INCOME', sensitive: false },
  { key: 'education.highest_level', label: 'Highest education', group: 'EDUCATION', sensitive: false },
  { key: 'education.school', label: 'School', group: 'EDUCATION', sensitive: false },
  { key: 'education.graduation_year', label: 'Graduation year', group: 'EDUCATION', sensitive: false },
  { key: 'household.size', label: 'Household size', group: 'HOUSEHOLD', sensitive: false },
  { key: 'household.marital_status', label: 'Marital status', group: 'HOUSEHOLD', sensitive: false },
  { key: 'emergency.contact_name', label: 'Emergency contact name', group: 'EMERGENCY', sensitive: false },
  { key: 'emergency.contact_phone', label: 'Emergency contact phone', group: 'EMERGENCY', sensitive: false },
  { key: 'emergency.contact_relationship', label: 'Emergency contact relationship', group: 'EMERGENCY', sensitive: false },
  { key: 'sensitive.ssn', label: 'Social security number', group: 'SENSITIVE', sensitive: true },
  { key: 'sensitive.passport_number', label: 'Passport number', group: 'SENSITIVE', sensitive: true },
  { key: 'sensitive.alien_number', label: 'Alien registration number', group: 'SENSITIVE', sensitive: true },
  { key: 'sensitive.drivers_license_number', label: "Driver's license number", group: 'SENSITIVE', sensitive: true },
  { key: 'sensitive.bank_account', label: 'Bank account number', group: 'SENSITIVE', sensitive: true },
  { key: 'sensitive.bank_routing', label: 'Bank routing number', group: 'SENSITIVE', sensitive: true },
  { key: 'sensitive.insurance_member_id', label: 'Insurance member ID', group: 'SENSITIVE', sensitive: true },
  { key: 'sensitive.medical_conditions', label: 'Medical conditions', group: 'SENSITIVE', sensitive: true },
  { key: 'sensitive.medications', label: 'Medications', group: 'SENSITIVE', sensitive: true },
]

const GROUP_ORDER = ['IDENTITY', 'CONTACT', 'ADDRESS', 'WORK', 'INCOME', 'EDUCATION', 'HOUSEHOLD', 'EMERGENCY', 'SENSITIVE']
const GROUP_LABELS: Record<string, string> = {
  IDENTITY: 'Identity',
  CONTACT: 'Contact',
  ADDRESS: 'Address',
  WORK: 'Work',
  INCOME: 'Income',
  EDUCATION: 'Education',
  HOUSEHOLD: 'Household',
  EMERGENCY: 'Emergency',
  SENSITIVE: 'Sensitive',
}

const details = ref<SavedDetailResponse[]>([])
const sealingAvailable = ref(true)
const loadState = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<string | null>(null)

const editingKey = ref<string | null>(null)
const editValue = ref('')
const rowBusy = ref<string | null>(null)
const confirmDeleteKey = ref<string | null>(null)
const confirmingDeleteAll = ref(false)
const deletingAll = ref(false)

const addOpen = ref(false)
const addKey = ref('')
const addValue = ref('')
const addBusy = ref(false)
const addError = ref<string | null>(null)

const rowError = ref<string | null>(null)

const grouped = computed(() => {
  const by = new Map<string, SavedDetailResponse[]>()
  for (const detail of details.value) {
    const list = by.get(detail.group) ?? []
    list.push(detail)
    by.set(detail.group, list)
  }
  return GROUP_ORDER.filter((group) => by.has(group)).map((group) => ({ group, label: GROUP_LABELS[group] ?? group, rows: by.get(group)! }))
})

const pickableGroups = computed(() => GROUP_ORDER)

onMounted(load)

async function load(): Promise<void> {
  loadState.value = 'loading'
  loadError.value = null
  try {
    const response = await listDetails()
    details.value = response.details
    sealingAvailable.value = response.sealingAvailable
    loadState.value = 'ready'
  } catch (error) {
    loadError.value = describeCommonFailure(error, 'your saved details') ?? 'Your details could not be loaded.'
    loadState.value = 'error'
  }
}

function beginEdit(detail: SavedDetailResponse): void {
  editingKey.value = detail.key
  editValue.value = detail.value ?? ''
  rowError.value = null
}

function cancelEdit(): void {
  editingKey.value = null
  editValue.value = ''
}

async function saveEdit(detail: SavedDetailResponse): Promise<void> {
  const value = editValue.value.trim()
  if (!value || rowBusy.value === detail.key) return
  rowBusy.value = detail.key
  rowError.value = null
  try {
    const saved = await saveDetail(detail.key, value)
    details.value = details.value.map((row) => (row.key === detail.key ? saved : row))
    editingKey.value = null
  } catch (error) {
    rowError.value = describeCommonFailure(error, 'this detail') ?? 'Could not save. Try again.'
  } finally {
    rowBusy.value = null
  }
}

async function removeOne(detail: SavedDetailResponse): Promise<void> {
  if (rowBusy.value === detail.key) return
  rowBusy.value = detail.key
  rowError.value = null
  try {
    await deleteDetail(detail.key)
    details.value = details.value.filter((row) => row.key !== detail.key)
    confirmDeleteKey.value = null
  } catch (error) {
    rowError.value = describeCommonFailure(error, 'this detail') ?? 'Could not delete. Try again.'
  } finally {
    rowBusy.value = null
  }
}

async function removeAll(): Promise<void> {
  if (deletingAll.value) return
  deletingAll.value = true
  rowError.value = null
  try {
    await deleteAllDetails()
    details.value = []
    confirmingDeleteAll.value = false
  } catch (error) {
    rowError.value = describeCommonFailure(error, 'your details') ?? 'Could not delete everything. Try again.'
  } finally {
    deletingAll.value = false
  }
}

const addOption = computed(() => DETAIL_KEY_OPTIONS.find((option) => option.key === addKey.value) ?? null)
const addSensitive = computed(() => addOption.value?.sensitive ?? false)
const addBlocked = computed(() => addSensitive.value && !sealingAvailable.value)

async function saveNew(): Promise<void> {
  const value = addValue.value.trim()
  if (!addKey.value || !value || addBusy.value || addBlocked.value) return
  addBusy.value = true
  addError.value = null
  try {
    const saved = await saveDetail(addKey.value, value)
    details.value = [...details.value.filter((row) => row.key !== saved.key), saved]
    addOpen.value = false
    addKey.value = ''
    addValue.value = ''
  } catch (error) {
    addError.value = describeCommonFailure(error, 'this detail') ?? 'Could not save. Try again.'
  } finally {
    addBusy.value = false
  }
}

function savedWhen(savedAt: string): string {
  return new Date(savedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}
</script>

<template>
  <section class="page-column page-column--wide details" aria-labelledby="details-title">
    <header class="page-head">
      <h1 id="details-title" class="page-title">Your details</h1>
      <p class="page-lede">Brownie saves the details from forms you approve, so the next form fills faster.</p>
    </header>

    <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Loading…</p>
    <div v-else-if="loadState === 'error'" class="panel panel--quiet details__notice">
      <p class="field-error" role="alert">{{ loadError }}</p>
      <button type="button" class="button button--small" @click="load">Try again</button>
    </div>

    <template v-else>
      <p v-if="rowError" class="field-error details__notice" role="alert">{{ rowError }}</p>

      <p v-if="details.length === 0 && !addOpen" class="panel panel--quiet details__empty">
        Brownie saves the details from forms you approve, so the next form fills faster. Nothing here yet.
      </p>

      <div v-for="group in grouped" :key="group.group" class="details__group">
        <h2 class="details__group-title">{{ group.label }}</h2>
        <ul class="details__list">
          <li v-for="detail in group.rows" :key="detail.key" class="details__row">
            <div class="details__row-main">
              <span class="details__label">{{ detail.label }}</span>
              <template v-if="editingKey === detail.key">
                <div class="details__edit">
                  <input v-model="editValue" class="field__input details__edit-input" :aria-label="detail.label" :disabled="rowBusy === detail.key" />
                  <button type="button" class="button button--small" :disabled="rowBusy === detail.key || !editValue.trim()" @click="saveEdit(detail)">
                    {{ rowBusy === detail.key ? 'Saving…' : 'Save' }}
                  </button>
                  <button type="button" class="button button--small button--ghost" :disabled="rowBusy === detail.key" @click="cancelEdit">Cancel</button>
                </div>
              </template>
              <template v-else>
                <span class="details__value" :class="{ 'details__value--masked': detail.sensitive }">{{ detail.sensitive ? (detail.hint ?? '•••') : (detail.value ?? '') }}</span>
                <span class="details__meta">
                  <template v-if="detail.origin === 'MANUAL'">Added by you</template>
                  <template v-else>
                    <RouterLink v-if="detail.source?.documentId" :to="`/documents/${detail.source.documentId}`">{{ detail.source.title ?? detail.source.label ?? 'the form' }}</RouterLink>
                    <span v-else>{{ detail.source?.title ?? detail.source?.label ?? 'a form' }}</span>
                    · saved {{ savedWhen(detail.savedAt) }}
                  </template>
                </span>
              </template>
            </div>
            <div v-if="editingKey !== detail.key" class="details__row-actions">
              <button v-if="!detail.sensitive" type="button" class="button button--small button--ghost" @click="beginEdit(detail)">Edit</button>
              <button type="button" class="button button--small button--ghost" @click="confirmDeleteKey = detail.key">Delete</button>
            </div>
            <div v-if="confirmDeleteKey === detail.key" class="details__confirm" role="group" :aria-label="`Delete ${detail.label}?`">
              <span>Delete "{{ detail.label }}"?</span>
              <button type="button" class="button button--small button--danger" :disabled="rowBusy === detail.key" @click="removeOne(detail)">Delete</button>
              <button type="button" class="button button--small button--ghost" :disabled="rowBusy === detail.key" @click="confirmDeleteKey = null">Keep it</button>
            </div>
          </li>
        </ul>
      </div>

      <section class="panel details__add">
        <h2 class="details__add-title"><AppIcon name="plus" :size="18" />Add a detail</h2>
        <p v-if="!addOpen" class="field-hint">Details you add by hand fill the same way as the ones Brownie learned. Sensitive details can only be added here.</p>
        <button v-if="!addOpen" type="button" class="button button--small" @click="addOpen = true">Add a detail</button>
        <template v-else>
          <div class="field details__add-field">
            <label class="field__label" for="add-key">Detail</label>
            <select id="add-key" v-model="addKey" class="field__input">
              <option value="" disabled>Choose a detail</option>
              <optgroup v-for="group in pickableGroups" :key="group" :label="GROUP_LABELS[group]">
                <option v-for="option in DETAIL_KEY_OPTIONS.filter((o) => o.group === group)" :key="option.key" :value="option.key" :disabled="option.sensitive && !sealingAvailable">
                  {{ option.label }}{{ option.sensitive && !sealingAvailable ? ' — sensitive details cannot be saved on this Brownie' : '' }}
                </option>
              </optgroup>
            </select>
          </div>
          <p v-if="addSensitive && !sealingAvailable" class="field-hint">This Brownie has no sealing key, so a sensitive detail cannot be saved.</p>
          <div class="field details__add-field">
            <label class="field__label" for="add-value">Value</label>
            <input id="add-value" v-model="addValue" class="field__input" :disabled="addBlocked" />
          </div>
          <p v-if="addError" class="field-error" role="alert">{{ addError }}</p>
          <div class="details__add-actions">
            <button type="button" class="button button--small" :disabled="addBusy || !addKey || !addValue.trim() || addBlocked" @click="saveNew">
              {{ addBusy ? 'Saving…' : 'Save' }}
            </button>
            <button type="button" class="button button--small button--ghost" :disabled="addBusy" @click="addOpen = false">Cancel</button>
          </div>
        </template>
      </section>

      <section v-if="details.length > 0" class="panel panel--quiet details__danger">
        <h2 class="details__danger-title">Delete all saved details</h2>
        <p class="field-hint">Removes every saved detail. The next form fills from nothing.</p>
        <button v-if="!confirmingDeleteAll" type="button" class="button button--small button--ghost" @click="confirmingDeleteAll = true">Delete all saved details</button>
        <div v-else class="details__confirm" role="group" aria-label="Delete all saved details?">
          <span>Delete every saved detail? This cannot be undone.</span>
          <button type="button" class="button button--small button--danger" :disabled="deletingAll" @click="removeAll">
            {{ deletingAll ? 'Deleting…' : 'Delete all' }}
          </button>
          <button type="button" class="button button--small button--ghost" :disabled="deletingAll" @click="confirmingDeleteAll = false">Keep them</button>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.details__group-title {
  margin: var(--space-5, 1.25rem) 0 var(--space-2, 0.5rem);
  font-size: var(--font-md, 0.95rem);
  font-weight: var(--font-semibold, 600);
  color: var(--color-text, #3d2b1f);
}
.details__list {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--color-hairline, #e7d8c9);
  border-radius: var(--radius-md, 0.625rem);
  background: var(--color-surface, #fff);
  overflow: hidden;
}
.details__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2, 0.5rem) var(--space-4, 1rem);
  padding: var(--space-3, 0.75rem) var(--space-4, 1rem);
  border-bottom: 1px solid var(--color-hairline, #e7d8c9);
}
.details__row:last-child {
  border-bottom: 0;
}
.details__row-main {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  min-width: 0;
}
.details__label {
  font-weight: var(--font-semibold, 600);
}
.details__value {
  color: var(--color-text, #3d2b1f);
  word-break: break-word;
}
.details__value--masked {
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.04em;
}
.details__meta {
  font-size: var(--font-sm, 0.85rem);
  color: var(--color-text-muted, #8a745f);
}
.details__row-actions,
.details__add-actions,
.details__edit,
.details__confirm {
  display: flex;
  align-items: center;
  gap: var(--space-2, 0.5rem);
}
.details__confirm {
  width: 100%;
  padding-top: var(--space-2, 0.5rem);
}
.details__edit-input {
  flex: 1;
  min-width: 12rem;
}
.details__add,
.details__danger {
  margin-top: var(--space-5, 1.25rem);
  padding: var(--space-4, 1rem);
}
.details__add-title,
.details__danger-title {
  display: flex;
  align-items: center;
  gap: var(--space-2, 0.5rem);
  margin: 0 0 var(--space-2, 0.5rem);
  font-size: var(--font-md, 0.95rem);
}
.details__add-field {
  margin: var(--space-3, 0.75rem) 0;
}
.details__empty,
.details__notice {
  padding: var(--space-4, 1rem);
}
</style>
