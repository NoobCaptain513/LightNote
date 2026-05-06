package com.lightnote.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.features")
public class AiFeatureProperties {

    private Rag rag = new Rag();
    private Stream stream = new Stream();
    private Cost cost = new Cost();

    @Data
    public static class Rag {
        private int topK = 3;
        private int vectorDimension = 64;
        private boolean autoRebuildOnEmpty = true;
    }

    @Data
    public static class Stream {
        private int chunkSize = 8;
        private long chunkDelayMs = 35L;
    }

    @Data
    public static class Cost {
        private double inputPricePer1k = 0.02D;
        private double outputPricePer1k = 0.06D;
    }
}
