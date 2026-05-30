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

//RAG 检索的桥接适配器，作用是把业务侧的知识库检索结果，转换成 LangChain4j 框架能理解的格式，注入到模型上下文里
@Component
@RequiredArgsConstructor
@Slf4j
public class LangChain4jRagContentRetriever implements ContentRetriever {

    private final AiRagService aiRagService;
    private final AiFeatureProperties aiFeatureProperties;

    /**
     * 根据 LangChain4j 查询对象检索业务 RAG 知识，并转换为 LangChain4j 可注入的 Content。
     *
     * @param query LangChain4j RAG 查询对象
     * @return 可注入到模型上下文的内容列表；查询为空或检索失败时返回空列表
     */
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

    /**
     * 把公共 RAG 命中结果转换为 LangChain4j Content，并携带分数和来源元数据。
     *
     * @param hit 公共 RAG 检索命中
     * @return LangChain4j Content 对象
     */
    private Content toContent(Map<String, Object> hit) {
        String title = stringValue(hit.get("title"));
        String content = stringValue(hit.get("content"));
        String text = StringUtils.hasText(title) ? "标题：" + title + "\n内容：" + content : content;

        //TextSegment 是 LangChain4j 里的基础文本单元
        TextSegment segment = TextSegment.from(text, Metadata.from(metadata(hit)));
        Map<ContentMetadata, Object> contentMetadata = new LinkedHashMap<>();
        Double score = doubleValue(hit.get("score"));
        if (score != null) {
            contentMetadata.put(ContentMetadata.SCORE, score);
        }
        return Content.from(segment, contentMetadata);
    }

    /**
     * 提取模型回答和后续兜底可能需要的 RAG 来源元数据。
     *
     * @param hit 公共 RAG 检索命中
     * @return LangChain4j TextSegment 元数据
     */
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

    /**
     * 当元数据值存在时写入 Map，并把数值类型统一转成字符串。
     *
     * @param metadata 目标元数据 Map
     * @param key 元数据键
     * @param value 元数据值
     */
    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            metadata.put(key, stringValue);
            return;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double) {
            metadata.put(key, String.valueOf(value));
        }
    }

    /**
     * 将任意对象安全转为字符串。
     *
     * @param value 待转换对象
     * @return 对象字符串；空值返回空字符串
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 将数值对象转成 Double。
     *
     * @param value 待转换对象
     * @return Double 值；非数值时返回 null
     */
    private Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
