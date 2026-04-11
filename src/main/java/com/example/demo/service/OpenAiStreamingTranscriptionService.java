package com.example.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Streaming Transcription Service
 *
 * Uses OkHttp to directly call OpenAI streaming transcription API (stream=True)
 * Returns SSE-format transcription results for frontend real-time display (typewriter effect)
 *
 * API docs: https://platform.openai.com/docs/guides/speech-to-text
 *
 * SSE event format:
 * - transcript.text.delta - delta text fragment
 * - transcript.text.done - transcription complete
 */
@Service
public class OpenAiStreamingTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiStreamingTranscriptionService.class);

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    @Value("${transcription.base-url}")
    private String openAiBaseUrl;

    @Value("${transcription.api-key}")
    private String openAiApiKey;

    @Value("${transcription.model:gpt-4o-transcribe}")
    private String transcriptionModel;

    public OpenAiStreamingTranscriptionService(
            @Qualifier("okHttpClient") OkHttpClient okHttpClient,
            ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Stream transcription of audio file
     *
     * @param audioResource audio file resource
     * @param filename      filename (for Content-Disposition)
     * @return Flux<SseEvent> - SSE event stream
     */
    public Flux<SseEvent> streamTranscribe(Resource audioResource, String filename) {
        return Flux.<SseEvent>create(emitter -> {
            // Execute OkHttp streaming request in separate thread
            Thread thread = new Thread(() -> {
                try {
                    doStreamTranscribe(audioResource, filename, emitter);
                } catch (Exception e) {
                    log.error("Streaming transcription failed: {}", e.getMessage(), e);
                    emitter.error(e);
                }
            });
            thread.start();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void doStreamTranscribe(Resource audioResource, String filename, reactor.core.publisher.FluxSink<SseEvent> emitter) throws IOException {
        // Build API URL
        String apiUrl = openAiBaseUrl.endsWith("/")
                ? openAiBaseUrl + "v1/audio/transcriptions"
                : openAiBaseUrl + "/v1/audio/transcriptions";

        log.info("Streaming transcription request: URL={}, Model={}", apiUrl, transcriptionModel);

        // Read audio file content
        byte[] audioBytes;
        try (InputStream is = audioResource.getInputStream()) {
            audioBytes = is.readAllBytes();
        }
        log.info("Audio file size: {} bytes, filename: {}", audioBytes.length, filename);

        // Build multipart request body
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();

        // Create request body
        MediaType mediaType = MediaType.parse("multipart/form-data; boundary=" + boundary);

        // Build entire request body to buffer first
        Buffer buffer = new Buffer();
        writeMultipartContent(buffer, audioBytes, filename, boundary);
        byte[] requestBodyBytes = buffer.readByteArray();

        RequestBody requestBody = RequestBody.create(mediaType, requestBodyBytes);

        Request request = new Request.Builder()
                .url(apiUrl)
                .post(requestBody)
                .header("Authorization", "Bearer " + openAiApiKey)
                .build();

        // Execute request
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("Transcription request failed: {} - {}", response.code(), errorBody);
                emitter.error(new RuntimeException("Transcription request failed: " + response.code() + " - " + errorBody));
                return;
            }

            ResponseBody body = response.body();
            if (body == null) {
                emitter.error(new RuntimeException("Transcription response body is null"));
                return;
            }

            // Process SSE stream
            MediaType contentType = body.contentType();
            log.info("Transcription response Content-Type: {}", contentType);

            // Use buffer source to read response
            okio.BufferedSource source = body.source();

            // Accumulate full transcription result
            StringBuilder fullTranscript = new StringBuilder();

            while (!emitter.isCancelled()) {
                // Read one line (event line)
                String line = source.readUtf8LineStrict();
                log.debug("SSE line: {}", line);

                // SSE event separator
                if (line == null || line.isEmpty()) {
                    // Empty line, may indicate end of event
                    continue;
                }

                // Parse SSE event
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();

                    if (data.equals("[DONE]")) {
                        // Transcription complete
                        log.info("Transcription complete, full content: {}", fullTranscript);
                        emitter.next(SseEvent.done(fullTranscript.toString()));
                        emitter.complete();
                        return;
                    }

                    // Parse JSON event
                    SseEvent event = parseSseEvent(data);
                    if (event != null) {
                        if (event.delta() != null) {
                            fullTranscript.append(event.delta());
                        }
                        emitter.next(event);
                    }
                }
            }

            log.info("Streaming transcription client disconnected");
            emitter.complete();

        } catch (Exception e) {
            if (e instanceof java.io.EOFException) {
                log.info("Streaming transcription ended normally (EOF)");
                emitter.complete();
            } else if (e instanceof java.io.IOException && e.getMessage() != null && e.getMessage().contains("Canceled")) {
                // Request was cancelled
                log.debug("Request was cancelled");
                emitter.complete();
            } else {
                log.error("Streaming transcription exception: {}", e.getMessage(), e);
                emitter.error(e);
            }
        }
    }

    private void writeMultipartContent(Buffer buffer, byte[] audioBytes, String filename, String boundary) throws IOException {
        String partBoundary = "--" + boundary;

        // Detect content type from file extension
        String contentType = "audio/wav"; // default
        if (filename != null) {
            if (filename.toLowerCase().endsWith(".mp3")) {
                contentType = "audio/mpeg";
            } else if (filename.toLowerCase().endsWith(".mp4")) {
                contentType = "audio/mp4";
            } else if (filename.toLowerCase().endsWith(".m4a")) {
                contentType = "audio/mp4";
            } else if (filename.toLowerCase().endsWith(".webm")) {
                contentType = "audio/webm";
            } else if (filename.toLowerCase().endsWith(".ogg")) {
                contentType = "audio/ogg";
            } else if (filename.toLowerCase().endsWith(".flac")) {
                contentType = "audio/flac";
            }
        }

        // Write file part
        buffer.writeUtf8(partBoundary).writeUtf8("\r\n");
        buffer.writeUtf8("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n");
        buffer.writeUtf8("Content-Type: " + contentType + "\r\n\r\n");
        buffer.write(audioBytes);
        buffer.writeUtf8("\r\n");

        // Write model part
        buffer.writeUtf8(partBoundary).writeUtf8("\r\n");
        buffer.writeUtf8("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
        buffer.writeUtf8(transcriptionModel);
        buffer.writeUtf8("\r\n");

        // Write stream part
        buffer.writeUtf8(partBoundary).writeUtf8("\r\n");
        buffer.writeUtf8("Content-Disposition: form-data; name=\"stream\"\r\n\r\n");
        buffer.writeUtf8("true");
        buffer.writeUtf8("\r\n");

        // End boundary
        buffer.writeUtf8(partBoundary).writeUtf8("--\r\n");
    }

    /**
     * Parse OpenAI SSE events
     * OpenAI event format: {"type":"transcript.text.delta","delta":"Hello"}
     * or: {"type":"transcript.text.done","text":"Hello world"}
     */
    private SseEvent parseSseEvent(String jsonStr) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(jsonStr);

            String type = node.has("type") ? node.get("type").asText() : null;

            if ("transcript.text.delta".equals(type)) {
                String delta = node.has("delta") ? node.get("delta").asText() : "";
                return new SseEvent("transcribed", delta, null, false);
            } else if ("transcript.text.done".equals(type)) {
                String text = node.has("text") ? node.get("text").asText() : "";
                return new SseEvent("transcribed", null, text, true);
            } else if ("error".equals(type)) {
                String error = node.has("error") ? node.get("error").asText() : "Unknown error";
                return new SseEvent("error", null, error, true);
            }

            log.debug("Unhandled SSE event type: {}", type);
            return null;

        } catch (Exception e) {
            log.error("Failed to parse SSE event: {}", e.getMessage());
            return null;
        }
    }

    /**
     * SSE event record
     */
    public record SseEvent(String type, String delta, String text, boolean done) {
        /**
         * Create delta event (for typewriter effect)
         */
        public static SseEvent delta(String content) {
            return new SseEvent("transcribed", content, null, false);
        }

        /**
         * Create done event
         */
        public static SseEvent done(String fullText) {
            return new SseEvent("transcribed", null, fullText, true);
        }

        /**
         * Create error event
         */
        public static SseEvent error(String errorMessage) {
            return new SseEvent("error", null, errorMessage, true);
        }

        /**
         * Convert to SSE format for frontend
         * Returns the JSON payload that will be wrapped by SseEmitter with "data:" prefix
         * @param objectMapper the ObjectMapper instance for JSON serialization
         */
        public String toSseFormat(ObjectMapper objectMapper) {
            try {
                if ("error".equals(type)) {
                    Map<String, Object> errorPayload = Map.of(
                        "type", "error",
                        "error", text != null ? text : ""
                    );
                    return objectMapper.writeValueAsString(errorPayload);
                }

                Map<String, Object> payload = Map.of(
                    "type", "transcribed",
                    "choices", List.of(Map.of("delta", Map.of("content", delta != null ? delta : "")))
                );
                return objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
                log.error("Failed to serialize SSE event: {}", e.getMessage());
                return "";
            }
        }
    }
}
