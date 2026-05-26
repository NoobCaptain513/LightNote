package com.lightnote.ai.embedding;

import java.io.IOException;
import java.util.List;

public interface EmbeddingClient {
    List<float[]> embed(List<String> texts) throws IOException;

    /**
     * 嵌入一个文本
     * @param text
     * @return
     * @throws IOException
     */
    default float[] embedOne(String text) throws IOException {
        List<float[]> embeddings = embed(List.of(text));
        if (embeddings.isEmpty()) {
            return new float[0];
        }
        return embeddings.get(0);
    }

    String model();

    int dimensions();
}
