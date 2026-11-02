package io.github.vihuynh72.brownie.ai.openai;

import com.openai.models.audio.AudioResponseFormat;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.Transcription;
import io.github.vihuynh72.brownie.core.model.TranscriptionGateway;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;

/**
 * The OpenAI transcription half of the model boundary: one call to the
 * configured speech model with the audio the caller was holding. The file
 * name travels only so the provider sees the right content type; the bytes
 * are never written down here or anywhere else.
 */
class OpenAiTranscriptionGateway implements TranscriptionGateway {

    private final OpenAiAudioTranscriptionModel model;
    private final OpenAiAudioTranscriptionOptions options;

    OpenAiTranscriptionGateway(OpenAiAudioTranscriptionModel model, String modelName) {
        this.model = model;
        this.options = OpenAiAudioTranscriptionOptions.builder()
                .model(modelName)
                .responseFormat(AudioResponseFormat.JSON)
                .build();
    }

    @Override
    public Transcription transcribe(byte[] audio, String fileName) throws ModelTransportException {
        try {
            ByteArrayResource resource = new ByteArrayResource(audio) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
            AudioTranscriptionResponse response =
                    model.call(new AudioTranscriptionPrompt(resource, options));
            String text = response.getResult() == null ? null : response.getResult().getOutput();
            return new Transcription(text, null, null);
        } catch (RuntimeException failure) {
            throw new ModelTransportException("Transcription request failed: " + failure.getMessage(), false, failure);
        }
    }
}
