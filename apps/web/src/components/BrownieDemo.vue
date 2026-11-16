<script setup lang="ts">
import { nextTick, ref } from 'vue'

const examples = {
  rental: { label: 'Rental application', source: 'I’m Maya Chen, and I work at Harbor Freight Logistics.', name: 'Maya Chen', detailLabel: 'Employer', detail: 'Harbor Freight Logistics', missing: 'Phone number' },
  event: { label: 'Event registration', source: 'I’m Alex Rivera, and I’d like to attend the community garden workshop.', name: 'Alex Rivera', detailLabel: 'Event', detail: 'Community garden workshop', missing: 'Email address' },
} as const
type Example = keyof typeof examples
const example = ref<Example>('rental')
const step = ref<'tell' | 'review' | 'ready'>('tell')
const heading = ref<HTMLElement | null>(null)
const name = ref('')
const detail = ref('')
const missing = ref('')

async function show(next: typeof step.value) {
  step.value = next
  await nextTick()
  heading.value?.focus()
}
function choose(value: Example) { example.value = value }
function prepare() {
  name.value = examples[example.value].name
  detail.value = examples[example.value].detail
  missing.value = ''
  void show('review')
}
</script>

<template>
  <section id="demo" class="demo" aria-labelledby="demo-title">
    <div class="demo__intro">
      <h2 id="demo-title">See a form take shape.</h2>
      <p>Try a fictional example to see how you can review details before export.</p>
    </div>
    <div class="demo__window">
      <div v-if="step === 'tell'">
        <h3 ref="heading" tabindex="-1">Choose an example</h3>
        <div class="demo__choices" role="group" aria-label="Choose an example form">
          <button v-for="(item, key) in examples" :key="key" type="button" :aria-pressed="example === key" @click="choose(key)">{{ item.label }}</button>
        </div>
        <p class="demo__source">“{{ examples[example].source }}”</p>
        <button class="demo__primary" type="button" @click="prepare">Review example</button>
      </div>
      <div v-else-if="step === 'review'">
        <h3 ref="heading" tabindex="-1">Review {{ examples[example].label.toLowerCase() }}</h3>
        <p>These sample values come from: “{{ examples[example].source }}”</p>
        <div class="demo__fields">
          <label>Name <input v-model="name" /></label>
          <label>{{ examples[example].detailLabel }} <input v-model="detail" /></label>
          <label>{{ examples[example].missing }} <span>Not provided in the example</span><input v-model="missing" /></label>
        </div>
        <div class="demo__actions"><button type="button" @click="show('tell')">Choose another example</button><button class="demo__primary" type="button" @click="show('ready')">Approve example</button></div>
      </div>
      <div v-else>
        <h3 ref="heading" tabindex="-1">Example approved.</h3>
        <p>{{ examples[example].missing }}: {{ missing.trim() || 'left blank' }}</p>
        <p>In Brownie, you can export the completed form as Word or PDF after review.</p>
        <button type="button" @click="show('review')">Edit review</button>
        <p>This is a scripted demonstration. No AI request was made and no file was created or downloaded.</p>
        <button class="demo__primary" type="button" @click="show('tell')">Try it again</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.demo { max-width: 90rem; margin: auto; padding-block: clamp(4rem, 7vw, 7rem); display: grid; gap: 2rem; scroll-margin-block-start: 6rem; }
.demo__intro { max-width: 34rem; }
h2 { margin: 0 0 1rem; font: 400 clamp(2.2rem, 4vw, 4rem)/1.05 var(--font-display, Georgia, serif); letter-spacing: -.03em; text-wrap: balance; }
p { line-height: 1.55; }
.demo__window { padding: clamp(1.25rem, 3vw, 2rem); background: var(--color-surface); border-radius: 1rem; }
h3 { margin: 0; font-size: 1.3rem; }
.demo__choices, .demo__actions { display: flex; flex-wrap: wrap; gap: .75rem; margin-block: 1.25rem; }
button { font: inherit; cursor: pointer; border: 1px solid var(--color-outline); background: var(--color-surface); color: var(--color-text); border-radius: var(--radius-control); padding: .75rem 1rem; min-height: 2.75rem; }
button:focus-visible, input:focus-visible, h3:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 3px; }
button[aria-pressed='true'], .demo__primary { background: var(--color-cocoa-strong); color: var(--color-surface); border-color: var(--color-cocoa-strong); }
.demo__source { padding-block: 1rem; border-block: 1px solid var(--color-hairline); font-size: 1.15rem; }
.demo__fields { display: grid; gap: 1rem; margin-block: 1.5rem; }
label { display: grid; gap: .35rem; font-weight: 600; }
label span { color: var(--color-text-muted); font-size: .875rem; font-weight: 400; }
input { width: 100%; border: 1px solid var(--color-outline); border-radius: var(--radius-control); padding: .75rem; font: inherit; color: var(--color-text); background: var(--color-paper); }
@media (min-width: 60rem) { .demo { grid-template-columns: minmax(0, .8fr) minmax(0, 1fr); align-items: start; gap: 5rem; } }
</style>
