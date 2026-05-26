package com.lightnote.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightnote.config.AiEmbeddingProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 调用兼容 /embeddings 协议的 Embedding API，拿回向量数组，供 pgvector 做相似度检索。
 *
 * @author xuzihan
 */
@Service
@RequiredArgsConstructor
public class CompatibleEmbeddingClient implements EmbeddingClient {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final AiEmbeddingProperties properties;
    private final ObjectMapper objectMapper;

    private OkHttpClient client;

    /**
     * 初始化 OkHttpClient
     * 设置连接超时时间和读取超时时间
     */
    @PostConstruct
    public void init() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(properties.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * 调用 Embedding API 生成文本向量
     *
     * @param texts
     * @return
     * @throws IOException
     */
    @Override
    public List<float[]> embed(List<String> texts) throws IOException {
        // 如果 texts 为空，则返回空列表
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        // 检查 API Key 是否配置
        if (properties.getApiKey() == null || properties.getApiKey().trim().isEmpty()) {
            throw new IOException("Embedding API Key 未配置，请设置 ai.embedding.api-key、AI_EMBEDDING_API_KEY 或 ai.api-key");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("input", texts);
        if (properties.isIncludeDimensions() && properties.getDimensions() > 0) {
            body.put("dimensions", properties.getDimensions());
        }

        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(properties.embeddingsUrl())
                .addHeader("Authorization", "Bearer " + properties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Embedding 请求失败[" + response.code() + "]: " + responseBody);
            }
            return parseEmbeddings(responseBody, texts.size());
        }
    }

    @Override
    public String model() {
        return properties.getModel();
    }

    
    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    /**
     * 解析 Embedding API 响应
     * @param responseBody
     * @param expectedCount
     * @return
     * @throws IOException
     */
    private List<float[]> parseEmbeddings(String responseBody, int expectedCount) throws IOException {
        JsonNode data = objectMapper.readTree(responseBody).path("data");
        if (!data.isArray()) {
            throw new IOException("Embedding 响应缺少 data 数组");
        }

        List<float[]> result = new ArrayList<>(Collections.nCopies(expectedCount, null));

        int fallbackIndex = 0;
        for (JsonNode item : data) {
            int index = item.has("index") ? item.path("index").asInt() : fallbackIndex;
            float[] vector = readVector(item.path("embedding"));
            if (index >= 0 && index < expectedCount) {
                result.set(index, vector);
            }
            fallbackIndex++;
        }

        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) == null) {
                throw new IOException("Embedding 响应缺少第 " + i + " 条向量");
            }
        }
        return result;
    }

    /**
     * 从 JSON 节点读取向量
     *
     * @param embeddingNode
     * @return
     * @throws IOException
     */
    private float[] readVector(JsonNode embeddingNode) throws IOException {
        if (!embeddingNode.isArray()) {
            throw new IOException("Embedding 响应中的 embedding 不是数组");
        }

        float[] vector = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            vector[i] = (float) embeddingNode.get(i).asDouble();
        }
        if (properties.getDimensions() > 0 && vector.length != properties.getDimensions()) {
            throw new IOException("Embedding 维度不匹配，期望 " + properties.getDimensions() + "，实际 " + vector.length);
        }
        return vector;
    }
}
