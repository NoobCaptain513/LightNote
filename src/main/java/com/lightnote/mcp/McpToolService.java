package com.lightnote.mcp;

import com.lightnote.ai.rag.AiRagService;
import com.lightnote.ai.tool.ShopAgentToolService;
import com.lightnote.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class McpToolService {

    private final ShopAgentToolService shopAgentToolService;
    private final AiRagService aiRagService;

    public List<McpToolDefinition> listTools() {
        return List.of(
                new McpToolDefinition(
                        "search_shop",
                        "Search shops by keyword, shop type, area, or address. Supports score and distance sorting.",
                        objectSchema(
                                properties(
                                        stringProperty("keyword", "Shop keyword or type, for example 火锅, 咖啡, 西餐"),
                                        enumProperty("sortBy", "Sort mode", List.of("score_desc", "distance_asc")),
                                        numberProperty("x", "User longitude, required for distance sorting"),
                                        numberProperty("y", "User latitude, required for distance sorting")
                                ),
                                List.of("keyword")
                        )
                ),
                new McpToolDefinition(
                        "get_shop_detail",
                        "Get structured detail for a shop by id.",
                        objectSchema(properties(longProperty("shopId", "Shop id")), List.of("shopId"))
                ),
                new McpToolDefinition(
                        "get_voucher",
                        "Get vouchers for a shop by id.",
                        objectSchema(properties(longProperty("shopId", "Shop id")), List.of("shopId"))
                ),
                new McpToolDefinition(
                        "rag_search",
                        "Search the pgvector RAG knowledge base and return ranked context hits.",
                        objectSchema(
                                properties(
                                        stringProperty("query", "User query"),
                                        integerProperty("topK", "Number of hits to return")
                                ),
                                List.of("query")
                        )
                ),
                new McpToolDefinition(
                        "rebuild_rag",
                        "Rebuild the RAG knowledge base from current shop, shop type, and voucher data.",
                        objectSchema(Map.of(), List.of())
                )
        );
    }

    public Object callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        return switch (name) {
            case "search_shop" -> searchShop(args);
            case "get_shop_detail" -> shopAgentToolService.getShopDetail(readLong(args.get("shopId")));
            case "get_voucher" -> shopAgentToolService.getVoucher(readLong(args.get("shopId")));
            case "rag_search" -> unwrap(aiRagService.searchKnowledge(readString(args.get("query")), readInteger(args.get("topK"))));
            case "rebuild_rag" -> unwrap(aiRagService.rebuildKnowledgeBase());
            default -> throw new IllegalArgumentException("Unknown MCP tool: " + name);
        };
    }

    private List<Map<String, Object>> searchShop(Map<String, Object> args) {
        return shopAgentToolService.searchShop(
                readString(args.get("keyword")),
                readString(args.get("sortBy")),
                readDouble(args.get("x")),
                readDouble(args.get("y"))
        );
    }

    private Object unwrap(Result result) {
        if (result == null) {
            return null;
        }
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalStateException(result.getErrorMsg());
        }
        return result.getData();
    }

    private String readString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double readDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Double.parseDouble(text);
        }
        return null;
    }

    private Long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return null;
    }

    private Integer readInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return null;
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    @SafeVarargs
    private Map<String, Object> properties(Map.Entry<String, Object>... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            properties.put(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    private Map.Entry<String, Object> stringProperty(String name, String description) {
        return Map.entry(name, Map.of("type", "string", "description", description));
    }

    private Map.Entry<String, Object> enumProperty(String name, String description, List<String> values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        schema.put("enum", values);
        return Map.entry(name, schema);
    }

    private Map.Entry<String, Object> numberProperty(String name, String description) {
        return Map.entry(name, Map.of("type", "number", "description", description));
    }

    private Map.Entry<String, Object> integerProperty(String name, String description) {
        return Map.entry(name, Map.of("type", "integer", "description", description));
    }

    private Map.Entry<String, Object> longProperty(String name, String description) {
        return integerProperty(name, description);
    }
}
