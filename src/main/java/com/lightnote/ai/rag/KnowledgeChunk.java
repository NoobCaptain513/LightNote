package com.lightnote.ai.rag;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class KnowledgeChunk {
    private String sourceType;
    private Long sourceId;
    private int chunkNo;
    private String title;
    private String content;
    private String metadataJson;
    private float[] embedding;
    private String embeddingModel;
    private String contentHash;
}
