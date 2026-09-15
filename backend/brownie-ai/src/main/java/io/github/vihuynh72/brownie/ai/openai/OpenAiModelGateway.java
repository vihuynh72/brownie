package io.github.vihuynh72.brownie.ai.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelMessage;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * Calls OpenAI's Chat Completions API through Spring AI's {@link
 * ChatModel}, using its low-level {@code call(Prompt)} entry point rather
 * than {@code ChatClient} -- there is no {@code ToolCallingAdvisor}
 * anywhere in this class, no tool ever registered on a request, so tool
 * execution is disabled by construction, not by a flag that could be
 * flipped. {@code model} is fixed by configuration (see {@code
 * application.yml}), never by anything in a {@link ModelRequest} -- a
 * request can never select a different, unevaluated model. Every request
 * asks for strict JSON-Schema-constrained output and disables provider-
 * side response storage.
 *
 * <p>The exception mapping and response interpretation below were built by
 * decompiling the exact pinned versions of {@code spring-ai-openai} and its
 * underlying {@code com.openai:openai-java-core} client (Spring AI 2.0.1
 * wraps OpenAI's own official Java SDK rather than a hand-rolled HTTP
 * client), then confirmed against real wire traffic in a WireMock-backed
 * test, not guessed from documentation: a 4xx/5xx reply throws one of
 * {@code OpenAIServiceException}'s subtypes, a network failure throws
 * {@code OpenAIIoException}, and a completed reply's refusal text (if any)
 * arrives on the assistant message's own metadata map under the key
 * {@code "refusal"}, not the generation's metadata that {@code
 * finishReason} lives on.
 */
class OpenAiModelGateway implements ModelGateway {

    // A private, unmanaged mapper: this class only ever asks it "does this
    // parse as JSON at all," a schema-agnostic syntax check that has
    // nothing to do with the application's own globally configured
    // Jackson customizations (date formats, naming strategy, and so on),
    // so there is no reason to depend on that shared, Spring-managed bean.
    private static final ObjectMapper JSON_SYNTAX_CHECK = new ObjectMapper();

    private final ChatModel chatModel;
    private final String model;

    OpenAiModelGateway(ChatModel chatModel, String model) {
        this.chatModel = chatModel;
        this.model = model;
    }

    @Override
    public ModelCompletion complete(ModelRequest request) throws ModelTransportException {
        Prompt prompt = new Prompt(toMessages(request.messages()), buildOptions(request));
        try {
            return interpret(chatModel.call(prompt));
        } catch (BadRequestException e) {
            // A ModelCompletion case, not a thrown failure: retrying an
            // identical rejected request can never succeed, and the
            // caller's own generation-run bookkeeping needs to record
            // this the same way it records a refusal or malformed reply.
            return new ModelCompletion.UnsupportedParameters(describeRejection(e));
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new ModelTransportException("The model provider rejected this process's credential.", false, e);
        } catch (OpenAIIoException e) {
            throw new ModelTransportException("Network failure calling the model provider.", true, e);
        } catch (RateLimitException | InternalServerException | OpenAIRetryableException e) {
            throw new ModelTransportException("Transient model provider failure.", true, e);
        } catch (OpenAIServiceException e) {
            throw new ModelTransportException("The model provider rejected the request (status " + e.statusCode() + ").", false, e);
        } catch (OpenAIException e) {
            throw new ModelTransportException("Unrecognized model provider failure.", false, e);
        }
    }

    private OpenAiChatOptions buildOptions(ModelRequest request) {
        OpenAiChatModel.ResponseFormat responseFormat = OpenAiChatModel.ResponseFormat.builder()
                .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(request.responseSchema().schemaJson())
                .strict(true)
                .build();
        return OpenAiChatOptions.builder()
                .model(model)
                // Not maxTokens(): confirmed by a real API call, not assumed,
                // that gpt-5.4-mini-2026-03-17 rejects the classic max_tokens
                // parameter outright ("Unsupported parameter: 'max_tokens' is
                // not supported with this model. Use 'max_completion_tokens'
                // instead.") -- the newer GPT-5-class parameter name.
                .maxCompletionTokens(request.maxOutputTokens())
                .store(false)
                .responseFormat(responseFormat)
                .build();
    }

    private static List<Message> toMessages(List<ModelMessage> messages) {
        return messages.stream()
                .<Message>map(message -> switch (message.role()) {
                    case SYSTEM -> new SystemMessage(message.content());
                    case USER -> new UserMessage(message.content());
                })
                .toList();
    }

    private ModelCompletion interpret(ChatResponse response) {
        Generation result = response.getResult();
        ModelUsage usage = toUsage(response);
        // The refusal text lives on the assistant message's own metadata
        // map, not the generation's metadata -- confirmed by decompiling
        // OpenAiChatModel's real conversion (it populates "finishReason",
        // "refusal", and "annotations" together into the message's own
        // metadata), and then by a real WireMock test that first asserted
        // the wrong location, failed, and was corrected to this one.
        Object refusal = result.getOutput().getMetadata().get("refusal");
        if (refusal instanceof String refusalText && !refusalText.isBlank()) {
            return new ModelCompletion.Refusal(refusalText, usage);
        }
        String finishReason = result.getMetadata().getFinishReason();
        if ("CONTENT_FILTER".equals(finishReason)) {
            return new ModelCompletion.Refusal("The model provider's content filter blocked this reply.", usage);
        }
        String content = result.getOutput().getText();
        if ("LENGTH".equals(finishReason)) {
            return new ModelCompletion.IncompleteOutput(
                    content == null ? "" : content, "The reply was truncated at its configured output limit.", usage);
        }
        if (content == null || content.isBlank()) {
            return new ModelCompletion.MalformedOutput(content == null ? "" : content, "The reply had no content.", usage);
        }
        if (!isWellFormedJson(content)) {
            return new ModelCompletion.MalformedOutput(content, "The reply did not parse as JSON.", usage);
        }
        return new ModelCompletion.Success(content, usage);
    }

    private static boolean isWellFormedJson(String content) {
        try {
            JSON_SYNTAX_CHECK.readTree(content);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private static ModelUsage toUsage(ChatResponse response) {
        var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return new ModelUsage(0, 0);
        }
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        return new ModelUsage(promptTokens == null ? 0 : promptTokens, completionTokens == null ? 0 : completionTokens);
    }

    private static String describeRejection(BadRequestException e) {
        return "The model provider rejected a request parameter (code " + e.code().orElse("unknown") + ").";
    }
}
