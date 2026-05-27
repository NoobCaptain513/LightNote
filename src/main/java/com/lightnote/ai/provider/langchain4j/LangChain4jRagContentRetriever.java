package com.lightnote.ai.provider.langchain4j;

import com.lightnote.ai.rag.AiRagService;
import com.lightnote.config.AiFeatureProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class LangChain4jRagContentRetriever implements ContentRetriever {

    private final AiRagService aiRagService;
    private final AiFeatureProperties aiFeatureProperties;

    @Override
    public List<Content> retrieve(Query query) {
        String queryText = query == null ? "" : query.text();
        if (!StringUtils.hasText(queryText)) {
            return List.of();
        }

        try {
            List<Map<String, Object>> hits = aiRagService.searchKnowledgeHits(queryText, aiFeatureProperties.getRag().getTopK());
            List<Content> contents = new ArrayList<>(hits.size());
            for (Map<String, Object> hit : hits) {
                contents.add(toContent(hit));
            }
            return contents;
        } catch (Exception e) {
            log.warn("LangChain4j RAG检索失败，已降级为空上下文", e);
            return List.of();
        }
    }

    private Content toContent(Map<String, Object> hit) {
        String title = stringValue(hit.get("title"));
        String content = stringValue(hit.get("content"));
        String text = StringUtils.hasText(title) ? "标题：" + title + "\n内容：" + content : content;

        TextSegment segment = TextSegment.from(text, Metadata.from(metadata(hit)));
        Map<ContentMetadata, Object> contentMetadata = new LinkedHashMap<>();
        Double score = doubleValue(hit.get("score"));
        if (score != null) {
            contentMetadata.put(ContentMetadata.SCORE, score);
        }
        return Content.from(segment, contentMetadata);
    }

    private Map<String, Object> metadata(Map<String, Object> hit) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "title", hit.get("title"));
        putIfPresent(metadata, "sourceType", hit.get("sourceType"));
        putIfPresent(metadata, "sourceId", hit.get("sourceId"));
        putIfPresent(metadata, "score", hit.get("score"));
        putIfPresent(metadata, "vectorScore", hit.get("vectorScore"));
        putIfPresent(metadata, "lexicalScore", hit.get("lexicalScore"));
        return metadata;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            metadata.put(key, stringValue);
            return;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double) {
            metadata.put(key, String.valueOf(value));
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
