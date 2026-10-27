<script setup lang="ts">
/**
 * The composer's microphone: press to record, press again to stop, and the
 * words land in the composer's text to edit -- nothing sends on its own.
 * It only renders when this deployment offers voice input and the browser
 * can record.
 */
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { useVoiceInput } from '@/actions/useVoiceInput'

const props = defineProps<{ workspaceId: number; enabled: boolean }>()
const emit = defineEmits<{ (e: 'transcript', text: string): void; (e: 'error', message: string): void }>()

const voice = useVoiceInput(
  () => props.workspaceId,
  (text) => emit('transcript', text),
  (message) => emit('error', message),
)
const visible = computed(() => props.enabled && voice.supported)
const label = computed(() =>
  voice.transcribing.value ? 'Transcribing…' : voice.recording.value ? `Stop recording (${voice.elapsed.value}s)` : 'Record a voice note',
)

function toggle() {
  if (voice.recording.value) voice.stop()
  else void voice.start()
}
</script>

<template>
  <button
    v-if="visible"
    type="button"
    class="icon-button voice-mic"
    :class="{ 'voice-mic--recording': voice.recording.value }"
    :aria-pressed="voice.recording.value"
    :title="label"
    @click="toggle"
  >
    <AppIcon :name="voice.recording.value ? 'stop-circle' : 'mic'" :size="22" />
    <span class="visually-hidden">{{ label }}</span>
  </button>
  <span v-if="visible" class="visually-hidden" aria-live="polite">
    {{ voice.recording.value ? `Recording, ${voice.elapsed} seconds` : voice.transcribing.value ? 'Transcribing' : '' }}
  </span>
</template>

<style scoped>
.voice-mic--recording {
  color: var(--color-danger, #b3261e);
}
</style>
