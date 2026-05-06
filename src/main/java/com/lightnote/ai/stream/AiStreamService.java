package com.lightnote.ai.stream;

import com.lightnote.config.AiFeatureProperties;
import com.lightnote.dto.AgentReply;
import com.lightnote.dto.Result;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

@Service
public class AiStreamService {

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Resource
    private AiFeatureProperties aiFeatureProperties;

    public SseEmitter stream(Supplier<Result> supplier) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> doPseudoStream(emitter, supplier), executorService);
        return emitter;
    }

    public SseEmitter stream(StreamTask task) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                task.run(emitter);
            } catch (Exception e) {
                try {
                    sendError(emitter, e.getMessage());
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        }, executorService);
        return emitter;
    }

    public SseEmitter errorEmitter(String message) {
        return stream(emitter -> {
            sendStart(emitter);
            sendError(emitter, message);
            emitter.complete();
        });
    }

    private void doPseudoStream(SseEmitter emitter, Supplier<Result> supplier) {
        try {
            sendStart(emitter);
            Result result = supplier.get();
            if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
                String errorMessage = result == null ? "AI 请求失败" : result.getErrorMsg();
                sendError(emitter, errorMessage);
                emitter.complete();
                return;
            }

            String finalText = resolveText(result.getData());
            int chunkSize = Math.max(1, aiFeatureProperties.getStream().getChunkSize());
            long delayMs = Math.max(0L, aiFeatureProperties.getStream().getChunkDelayMs());
            for (int start = 0; start < finalText.length(); start += chunkSize) {
                int end = Math.min(finalText.length(), start + chunkSize);
                sendDelta(emitter, finalText.substring(start, end));
                if (delayMs > 0L) {
                    Thread.sleep(delayMs);
                }
            }

            sendDone(emitter, finalText, result.getData());
            emitter.complete();
        } catch (Exception e) {
            try {
                sendError(emitter, e.getMessage());
            } catch (Exception ignored) {
            }
            emitter.completeWithError(e);
        }
    }

    public void sendStart(SseEmitter emitter) throws IOException {
        sendEvent(emitter, "start", Map.of("type", "start"));
    }

    public void sendDelta(SseEmitter emitter, String content) throws IOException {
        sendEvent(emitter, "delta", Map.of("type", "delta", "content", content == null ? "" : content));
    }

    public void sendDone(SseEmitter emitter, String finalText, Object data) throws IOException {
        Map<String, Object> donePayload = new LinkedHashMap<>();
        donePayload.put("type", "done");
        donePayload.put("text", finalText == null ? "" : finalText);
        donePayload.put("data", data);
        sendEvent(emitter, "done", donePayload);
    }

    public void sendError(SseEmitter emitter, String message) throws IOException {
        sendEvent(emitter, "error", Map.of("type", "error", "message", message == null ? "AI 请求失败" : message));
    }

    private String resolveText(Object data) {
        if (data instanceof String text) {
            return text;
        }
        if (data instanceof AgentReply reply) {
            return reply.getText() == null ? "" : reply.getText();
        }
        return data == null ? "" : String.valueOf(data);
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    @FunctionalInterface
    public interface StreamTask {
        void run(SseEmitter emitter) throws Exception;
    }
}
