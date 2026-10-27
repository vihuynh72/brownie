import { describe, expect, it, vi, beforeEach } from 'vitest'
import { effectScope } from 'vue'
import { useVoiceInput } from '../useVoiceInput'

/** A MediaRecorder that remembers it was asked to start and stop, so the composable's own flow is what is under test. */
function mockRecorder() {
  const api = {
    recorder: null as any,
    stream: { getTracks: () => [{ stop: vi.fn() }] },
  }
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn(async () => api.stream) } })
  vi.stubGlobal('MediaRecorder', class {
    static last: any
    mimeType = 'audio/webm'
    ondataavailable: ((e: BlobEvent) => void) | null = null
    onstop: (() => void) | null = null
    started = false
    stopped = false
    constructor(public stream: MediaStream) {
      api.recorder = this
      ;(this.constructor as any).last = this
    }
    start() { this.started = true }
    stop() { this.stopped = true; this.ondataavailable?.(new BlobEvent('dataavailable', { data: new Blob(['x'], { type: 'audio/webm' }) })); this.onstop?.() }
  })
  vi.stubGlobal('BlobEvent', class { constructor(public type: string, public init: any) { this.data = init.data } data!: Blob })
  return api
}

describe('useVoiceInput', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    ;(globalThis as any).fetch = vi.fn(async () => ({
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: async () => ({ text: 'My name is Jordan Rivera.' }),
    }))
    document.cookie = 'XSRF-TOKEN=t'
  })

  it('records, transcribes, and hands the words to the composer', async () => {
    mockRecorder()
    const transcript = vi.fn()
    const error = vi.fn()
    const scope = effectScope()
    const voice = scope.run(() => useVoiceInput(() => 7, transcript, error))!
    await voice.start()
    expect(voice.recording.value).toBe(true)
    voice.stop()
    await vi.waitFor(() => expect(transcript).toHaveBeenCalledWith('My name is Jordan Rivera.'), { timeout: 2000 })
    expect(error).not.toHaveBeenCalled()
    scope.stop()
  })

  it('says the permission answer plainly when the microphone is refused', async () => {
    const error = vi.fn()
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn(async () => { throw new Error('denied') }) } })
    vi.stubGlobal('MediaRecorder', class {})
    const scope = effectScope()
    const voice = scope.run(() => useVoiceInput(() => 7, vi.fn(), error))!
    await voice.start()
    expect(error).toHaveBeenCalledWith(expect.stringContaining('microphone'))
    expect(voice.recording.value).toBe(false)
    scope.stop()
  })
})
