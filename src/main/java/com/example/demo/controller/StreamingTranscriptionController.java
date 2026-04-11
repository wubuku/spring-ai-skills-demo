package com.example.demo.controller;

import com.example.demo.service.OpenAiStreamingTranscriptionService;
import com.example.demo.service.OpenAiStreamingTranscriptionService.SseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Streaming Transcription Controller
 *
 * Provides SSE-format streaming transcription endpoint
 * Frontend can display transcription results in real-time (typewriter effect)
 *
 * SSE format:
 * - data:{"type":"transcribed","choices":[{"delta":{"content":"xxx"}}]}
 * - data:{"type":"error","error":"error message"} (on error)
 * - data:[DONE] (on completion)
 */
@RestController
@RequestMapping("/api/transcribe")
public class StreamingTranscriptionController {

    private static final Logger log = LoggerFactory.getLogger(StreamingTranscriptionController.class);

    private final OpenAiStreamingTranscriptionService streamingTranscriptionService;
    private final ObjectMapper objectMapper;

    public StreamingTranscriptionController(
            @Autowired OpenAiStreamingTranscriptionService streamingTranscriptionService,
            ObjectMapper objectMapper) {
        this.streamingTranscriptionService = streamingTranscriptionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Streaming transcription endpoint
     *
     * Receives audio file and returns SSE streaming transcription result
     *
     * @param audio audio file
     * @return SseEmitter - SSE event stream
     */
    @PostMapping(value = "/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter transcribeStream(
            @RequestParam("audio") MultipartFile audio) {

        SseEmitter emitter = new SseEmitter(0L); // no timeout

        final File[] audioTempHolder = {null};

        try {
            // Save audio file to temp file
            File audioTemp = File.createTempFile("transcribe-", "-" + audio.getOriginalFilename());
            audio.transferTo(audioTemp);
            audioTempHolder[0] = audioTemp;

            String filename = audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "audio.wav";
            FileSystemResource audioResource = new FileSystemResource(audioTemp);

            log.info("Starting streaming transcription: file={}, size={}", filename, audioTemp.length());

            Flux<SseEvent> eventFlux = streamingTranscriptionService.streamTranscribe(audioResource, filename);

            eventFlux
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signalType -> {
                    // Cleanup temp file
                    cleanupTempFile(audioTempHolder[0]);
                })
                .subscribe(
                    event -> {
                        try {
                            if ("error".equals(event.type())) {
                                // Send error event
                                Map<String, Object> errorPayload = Map.of(
                                    "type", "error",
                                    "error", event.text() != null ? event.text() : ""
                                );
                                String errorData = "data:" + objectMapper.writeValueAsString(errorPayload) + "\n\n";
                                emitter.send(SseEmitter.event().data(errorData));
                            } else {
                                // Send standard SSE format
                                emitter.send(SseEmitter.event()
                                    .data(event.toSseFormat(objectMapper)));
                            }
                        } catch (IOException e) {
                            log.error("发送 SSE 事件失败: {}", e.getMessage());
                            emitter.completeWithError(e);
                        }
                    },
                    error -> {
                        log.error("流式转写错误: {}", error.getMessage());
                        emitter.completeWithError(error);
                    },
                    () -> {
                        try {
                            emitter.send(SseEmitter.event().data("data:[DONE]\n\n"));
                            emitter.complete();
                        } catch (IOException e) {
                            log.error("发送完成事件失败: {}", e.getMessage());
                            emitter.completeWithError(e);
                        }
                    }
                );

        } catch (Exception e) {
            log.error("流式转写请求处理失败: {}", e.getMessage(), e);
            cleanupTempFile(audioTempHolder[0]);
            emitter.completeWithError(e);
        }

        emitter.onTimeout(() -> {
            log.warn("流式转写超时");
            emitter.complete();
        });

        emitter.onError(e -> {
            log.error("流式转写连接错误: {}", e.getMessage());
            cleanupTempFile(audioTempHolder[0]);
        });

        return emitter;
    }

    private void cleanupTempFile(File tempFile) {
        if (tempFile != null && tempFile.exists()) {
            if (tempFile.delete()) {
                log.debug("临时文件已删除: {}", tempFile.getAbsolutePath());
            } else {
                log.warn("临时文件删除失败: {}", tempFile.getAbsolutePath());
            }
        }
    }
}
