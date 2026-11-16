<script setup lang="ts">
import { nextTick, ref } from 'vue'

const examples = {
  rental: { label: 'Rental application', context: "I'm Maya Chen, and I work at Harbor Freight Logistics." },
  event: { label: 'Event registration', context: "I'm Alex Rivera, and I'd like to attend the community garden workshop." },
} as const
type Example = keyof typeof examples
const example = ref<Example>('rental')
const context = ref<string>(examples.rental.context)
const proposed = ref('')
const step = ref<'tell' | 'review' | 'ready'>('tell')
const heading = ref<HTMLElement | null>(null)

async function show(next: typeof step.value) {
  step.value = next
  await nextTick()
  heading.value?.focus()
}
function choose(value: Example) {
  example.value = value
  context.value = examples[value].context
}
function prepare() {
  proposed.value = context.value.trim()
  void show('review')
}
function replay() {
  choose(example.value)
  void show('tell')
}
</script>

<template>
  <section id="demo" class="demo" aria-labelledby="demo-title">
    <div class="demo__intro">
      <p class="demo__eyebrow">A little test drive</p>
      <h2 id="demo-title">See what happens to a blank page.</h2>
      <p>Give Brownie a form and a little context, then review what it suggests. This fictional demo stays in your browser.</p>
    </div>
    <div class="demo__window">
      <div class="demo__top"><span>Brownie / Try it out</span><span>Fictional example</span></div>
      <div v-if="step === 'tell'" class="demo__choices" aria-label="Choose an example form">
        <button v-for="(item, key) in examples" :key="key" type="button" :aria-pressed="example === key" @click="choose(key)">{{ item.label }}</button>
      </div>
      <ol class="demo__progress" aria-label="Demo steps"><li :class="{ active: step === 'tell' }">1. Tell</li><li :class="{ active: step === 'review' }">2. Review</li><li :class="{ active: step === 'ready' }">3. Ready</li></ol>
      <div class="demo__body">
        <template v-if="step === 'tell'">
          <h3 ref="heading" tabindex="-1">A form and a little context.</h3>
          <p>Here's a made-up request. Use it as is or make it your own.</p>
          <label>What would you tell Brownie? <textarea v-model="context" rows="3" maxlength="300" /></label>
          <button class="demo__primary" type="button" :disabled="!context.trim()" @click="prepare">Prepare this example <span aria-hidden="true">↗</span></button>
        </template>
        <template v-else-if="step === 'review'">
          <h3 ref="heading" tabindex="-1">Here's your example to review.</h3>
          <p>In the real app, Brownie proposes values for each field. This local example shows your request on a sample form.</p>
          <div class="demo__paper"><span>{{ examples[example].label }}</span><dl><div><dt>Details provided</dt><dd>{{ proposed }}</dd></div></dl></div>
          <div class="demo__actions"><button type="button" @click="show('tell')">Edit example</button><button class="demo__primary" type="button" @click="show('ready')">Approve example</button></div>
        </template>
        <template v-else>
          <h3 ref="heading" tabindex="-1">Ready when you are.</h3><p>Your example is approved. In Brownie, you can export the completed form as Word or PDF.</p>
          <p class="demo__notice">This is a demonstration. No AI request was made and no file was created or downloaded.</p>
          <button class="demo__primary" type="button" @click="replay">Try it again</button>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.demo { max-width: 90rem; margin: auto; padding-block: clamp(5rem, 10vw, 10rem); display: grid; gap: 3rem; scroll-margin-block-start: 6rem; }
.demo__intro { max-width: 33rem; }
.demo__eyebrow, .demo__top, .demo__paper > span { font-size: .75rem; font-weight: 600; letter-spacing: .1em; text-transform: uppercase; }
h2 { margin: .7rem 0 1.5rem; font: 400 clamp(3rem, 5vw, 5.5rem)/.98 var(--font-display, Georgia, serif); letter-spacing: -.04em; overflow-wrap: anywhere; }
.demo__intro > p:last-child { font-size: 1.1rem; line-height: 1.6; max-width: 28rem; }
.demo__window { background: var(--color-surface); border: 1px solid var(--color-hairline); border-radius: 2rem; padding: clamp(1.25rem, 3vw, 2.5rem); box-shadow: var(--shadow-popover); }
.demo__top, .demo__choices, .demo__actions { display: flex; flex-wrap: wrap; gap: .75rem; align-items: center; }
.demo__top { justify-content: space-between; color: var(--color-text-muted); margin-bottom: 2rem; }
button { font: inherit; cursor: pointer; border: 1px solid var(--color-outline); background: var(--color-surface); color: var(--color-text); border-radius: 999px; padding: .75rem 1rem; min-height: 2.75rem; }
button:focus-visible, textarea:focus-visible, h3:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 3px; }
button[aria-pressed='true'], .demo__primary { background: var(--color-cocoa-strong); color: var(--color-surface); border-color: var(--color-cocoa-strong); }
button:disabled { opacity: .5; cursor: not-allowed; }
.demo__progress { display: flex; flex-wrap: wrap; gap: 1.25rem; list-style: none; padding: 0 0 1rem; margin: 2rem 0; border-bottom: 1px solid var(--color-hairline); color: var(--color-text-muted); font-size: .875rem; }
.demo__progress .active { color: var(--color-cocoa-strong); font-weight: 700; }
.demo__body { min-height: 17rem; }
h3 { font-size: 1.5rem; margin: 0; }
.demo__body p { line-height: 1.5; color: var(--color-text-muted); }
label:not(.demo__check) { display: grid; gap: .4rem; margin: 1rem 0; font-weight: 600; }
label:not(.demo__check) textarea { width: 100%; border: 1px solid var(--color-outline); border-radius: .75rem; padding: .8rem; font: inherit; color: var(--color-text); background: var(--color-paper); }
.demo__paper { background: var(--color-paper); border: 1px solid var(--color-hairline); padding: 1.25rem; border-radius: .75rem; }
dl { margin: 1rem 0 0; } dl > div { display: grid; grid-template-columns: minmax(5rem, .4fr) 1fr; gap: 1rem; padding-block: .5rem; border-bottom: 1px solid var(--color-hairline); } dt { color: var(--color-text-muted); } dd { margin: 0; overflow-wrap: anywhere; }
.demo__actions { margin-top: 1.25rem; }
.demo__notice { padding: 1rem; border-left: 3px solid var(--color-cocoa); background: var(--color-paper); }
@media (min-width: 60rem) { .demo { grid-template-columns: minmax(0, .8fr) minmax(0, 1fr); align-items: center; gap: 6rem; } }
</style>
