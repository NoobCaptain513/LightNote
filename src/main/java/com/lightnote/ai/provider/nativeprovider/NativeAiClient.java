package com.lightnote.ai.provider.nativeprovider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightnote.dto.AiMessageDTO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "ai.provider", name = "type", havingValue = "native", matchIfMissing = true)
@RequiredArgsConstructor
public class NativeAiClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    @Value("${ai.api-key}")
    private String apiKey;

    @Value("${ai.model}")
    private String model;

    @Value("${ai.base-url}")
    private String apiUrl;

    private final ObjectMapper objectMapper;

    private OkHttpClient client;

    @PostConstruct
    public void init() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public List<Map<String, Object>> buildRequestMessages(String systemPrompt, List<AiMessageDTO> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(buildMessage("system", systemPrompt));
        for (AiMessageDTO message : history) {
            messages.add(buildMessage(message.getRole(), message.getContent()));
        }
        return messages;
    }

    public Map<String, Object> buildChatBody(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> input = new HashMap<>();
        input.put("messages", messages);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("max_tokens", 1000);
        parameters.put("result_format", "message");
        if (tools != null && !tools.isEmpty()) {
            parameters.put("tools", tools);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);
        return body;
    }

    public String callModel(Map<String, Object> body) throws IOException {
        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("AI服务请求失败[" + response.code() + "]: " + responseBody);
            }
            return responseBody;
        }
    }

    public String extractAssistantContent(String responseBody) throws IOException {
        Map<String, Object> result = parseResponse(responseBody);
        Map<String, Object> output = castMap(result.get("output"));
        if (output == null) {
            return null;
        }

        List<Map<String, Object>> choices = castListOfMap(output.get("choices"));
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> message = castMap(choices.get(0).get("message"));
            if (message != null && message.get("content") != null) {
                return String.valueOf(message.get("content"));
            }
        }

        Object text = output.get("text");
        return text == null ? null : text.toString();
    }

    public Map<String, Object> parseResponse(String responseBody) throws IOException {
        return objectMapper.readValue(responseBody, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> castListOfMap(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : null;
    }

    private Map<String, Object> buildMessage(String role, String content) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
