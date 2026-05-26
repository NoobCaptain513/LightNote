package com.lightnote.ai.rag;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class RagHit {
    private Long id;
    private String title;
    private String content;
    private String sourceType;
    private Long sourceId;
    private double score;
}
