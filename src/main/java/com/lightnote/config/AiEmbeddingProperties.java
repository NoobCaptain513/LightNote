package com.lightnote.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.embedding")
public class AiEmbeddingProperties {
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String DEFAULT_MODEL = "text-embedding-v4";

    private String baseUrl = DEFAULT_BASE_URL;
    private String apiKey = "";
    private String model = DEFAULT_MODEL;
    private int dimensions = 1536;
    private boolean includeDimensions = true;
    private int batchSize = 10;
    private int connectTimeoutSeconds = 30;
    private int readTimeoutSeconds = 60;

    /**
     * 获取 embeddings 接口 URL
     * @return
     */
    public String embeddingsUrl() {
        String normalized = trimTrailingSlash(baseUrl);
        if (normalized.endsWith("/embeddings")) {
            return normalized;
        }
        if (normalized.endsWith("/compatible-mode")) {
            normalized = normalized + "/v1";
        }
        return normalized + "/embeddings";
    }

    /**
     * 去除 URL 后缀斜杠
     * @param value
     * @return
     */
    private String trimTrailingSlash(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_BASE_URL;
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
