package com.lightnote.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.rag.pgvector")
public class PgVectorProperties {
    private String url = "jdbc:postgresql://localhost:5432/lightnote_rag";
    private String username = "lightnote";
    private String password = "lightnote";
    private int candidateK = 15;
    private double minScore = 0.05D;
}
