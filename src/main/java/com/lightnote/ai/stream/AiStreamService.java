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


    /**
     * 使用Supplier创建一个SseEmitter。
     * @param supplier 要执行的Supplier，返回一个Result对象。
     * @return 包含Supplier执行结果的SseEmitter。
     */
    public SseEmitter stream(Supplier<Result> supplier) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> doPseudoStream(emitter, supplier), executorService);
        return emitter;
    }

    /**
     * 使用StreamTask创建一个SseEmitter。
     * @param task 要执行的StreamTask。
     * @return 包含StreamTask执行结果的SseEmitter。
     */
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
                emitter.complete();
            }
        }, executorService);
        return emitter;
    }


    /**
     * 创建一个包含错误信息的SseEmitter。
     * @param message 错误信息。
     * @return 包含错误信息的SseEmitter。
     */
    public SseEmitter errorEmitter(String message) {
        return stream(emitter -> {
            sendStart(emitter);
            sendError(emitter, message);
            emitter.complete();
        });
    }

    /**
     * 执行伪流操作，将Supplier的结果发送到SseEmitter。
     * @param emitter 要发送结果的SseEmitter。
     * @param supplier 要执行的Supplier，返回一个Result对象。
     */
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
            emitter.complete();
        }
    }

    /**
     * 发送流开始事件。
     * @param emitter 要发送事件的SseEmitter。
     * @throws IOException 如果发送事件时发生IO错误。
     */
    public void sendStart(SseEmitter emitter) throws IOException {
        sendEvent(emitter, "start", Map.of("type", "start"));
    }

    /**
     * 发送流增量事件。
     * @param emitter 要发送事件的SseEmitter。
     * @param content 增量内容。
     * @throws IOException 如果发送事件时发生IO错误。
     */
    public void sendDelta(SseEmitter emitter, String content) throws IOException {
        sendEvent(emitter, "delta", Map.of("type", "delta", "content", content == null ? "" : content));
    }

    /**
     * 发送流完成事件。
     * @param emitter 要发送事件的SseEmitter。
     * @param finalText 最终文本内容。
     * @param data 关联数据。
     * @throws IOException 如果发送事件时发生IO错误。
     */
    public void sendDone(SseEmitter emitter, String finalText, Object data) throws IOException {
        Map<String, Object> donePayload = new LinkedHashMap<>();
        donePayload.put("type", "done");
        donePayload.put("text", finalText == null ? "" : finalText);
        donePayload.put("data", data);
        sendEvent(emitter, "done", donePayload);
    }

    /**
     * 发送流错误事件。
     * @param emitter 要发送事件的SseEmitter。
     * @param message 错误信息。
     * @throws IOException 如果发送事件时发生IO错误。
     */
    public void sendError(SseEmitter emitter, String message) throws IOException {
        sendEvent(emitter, "error", Map.of("type", "error", "message", message == null ? "AI 请求失败" : message));
    }


    /**
     * 解析数据中的文本内容。
     * @param data 包含文本内容的数据对象。
     * @return 解析后的文本内容。
     */
    private String resolveText(Object data) {
        if (data instanceof String text) {
            return text;
        }
        if (data instanceof AgentReply reply) {
            return reply.getText() == null ? "" : reply.getText();
        }
        return data == null ? "" : String.valueOf(data);
    }

    /**
     * 发送SSE事件。
     * @param emitter 要发送事件的SseEmitter。
     * @param eventName 事件名称。
     * @param data 事件数据。
     * @throws IOException 如果发送事件时发生IO错误。
     */
    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    /**
     * 流处理任务接口。
     */
    @FunctionalInterface
    public interface StreamTask {
        void run(SseEmitter emitter) throws Exception;
    }
}
