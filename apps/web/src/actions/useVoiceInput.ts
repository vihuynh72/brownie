import { onBeforeUnmount, ref } from 'vue'
import { transcribeVoice } from '../api/client'

/**
 * Talk to the composer: hold the mike, stop, hear the words back. The audio
 * only ever travels to the transcriptions route -- nothing is kept -- and
 * the transcript lands in the composer's text for the person to edit,
 * never sent on its own.
 */
export function useVoiceInput(
  workspaceId: () => number,
  onTranscript: (text: string) => void,
  onError: (message: string) => void,
) {
  const supported =
    typeof MediaRecorder !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    !!navigator.mediaDevices?.getUserMedia
  const recording = ref(false)
  const transcribing = ref(false)
  const elapsed = ref(0)

  let recorder: MediaRecorder | null = null
  let chunks: Blob[] = []
  let timer: ReturnType<typeof setInterval> | null = null
  let autostop: ReturnType<typeof setTimeout> | null = null

  async function start(): Promise<void> {
    if (recording.value || transcribing.value) return
    let stream: MediaStream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch {
      onError('Brownie could not use your microphone. Check the browser permission and try again.')
      return
    }
    chunks = []
    recorder = new MediaRecorder(stream)
    recorder.ondataavailable = (event: BlobEvent) => { if (event.data.size > 0) chunks.push(event.data) }
    recorder.onstop = () => { void finish(stream) }
    recording.value = true
    elapsed.value = 0
    timer = setInterval(() => { elapsed.value += 1 }, 1000)
    autostop = setTimeout(stop, 120_000)
    recorder.start()
  }

  function stop(): void {
    if (!recording.value) return
    recording.value = false
    if (timer) { clearInterval(timer); timer = null }
    if (autostop) { clearTimeout(autostop); autostop = null }
    transcribing.value = true
    recorder?.stop()
  }

  async function finish(stream: MediaStream): Promise<void> {
    stream.getTracks().forEach((track) => track.stop())
    try {
      const blob = new Blob(chunks, { type: recorder?.mimeType || 'audio/webm' })
      const answer = await transcribeVoice(workspaceId(), blob)
      if (answer.text?.trim()) onTranscript(answer.text.trim())
      else onError('Brownie could not hear anything in that recording.')
    } catch {
      onError('Brownie could not transcribe that recording. You can type instead.')
    } finally {
      transcribing.value = false
      recorder = null
    }
  }

  onBeforeUnmount(() => {
    if (timer) clearInterval(timer)
    if (autostop) clearTimeout(autostop)
    if (recording.value) recorder?.stop()
  })

  return { supported, recording, transcribing, elapsed, start, stop }
}
