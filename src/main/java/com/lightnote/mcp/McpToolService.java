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

    /**
     * 返回当前 MCP Server 暴露给外部客户端的工具列表。
     *
     * <p>每个工具都包含工具名、描述和输入参数 JSON Schema。外部 MCP Client
     * 会通过 tools/list 读取这些定义，再决定是否调用 tools/call。</p>
     *
     * @return MCP 工具定义列表
     */
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

    /**
     * 根据工具名称执行对应的 MCP 工具。
     *
     * <p>该方法是 tools/call 的业务路由入口，只负责把工具名分发到具体服务：
     * 店铺类能力委托给 {@link ShopAgentToolService}，RAG 类能力委托给
     * {@link AiRagService}。</p>
     *
     * @param name MCP 工具名称
     * @param arguments MCP Client 传入的工具参数
     * @return 工具执行后的结构化结果
     */
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

    /**
     * 执行店铺搜索工具。
     *
     * <p>从通用参数 Map 中读取 keyword、sortBy、x、y，并转成
     * {@link ShopAgentToolService#searchShop(String, String, Double, Double)}
     * 所需的强类型参数。</p>
     *
     * @param args 工具参数 Map
     * @return 匹配到的店铺结构化列表
     */
    private List<Map<String, Object>> searchShop(Map<String, Object> args) {
        return shopAgentToolService.searchShop(
                readString(args.get("keyword")),
                readString(args.get("sortBy")),
                readDouble(args.get("x")),
                readDouble(args.get("y"))
        );
    }

    /**
     * 解包项目内部的 {@link Result} 响应。
     *
     * <p>MCP 对外只返回工具结果本身；如果内部 Result 表示失败，则转成异常，
     * 交给 JSON-RPC 层包装成 error 响应。</p>
     *
     * @param result 项目内部统一响应对象
     * @return Result 中的 data
     */
    private Object unwrap(Result result) {
        if (result == null) {
            return null;
        }
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalStateException(result.getErrorMsg());
        }
        return result.getData();
    }

    /**
     * 从通用参数值中读取字符串。
     *
     * @param value 原始参数值
     * @return 字符串值；原始值为 null 时返回 null
     */
    private String readString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 从通用参数值中读取 Double。
     *
     * <p>支持 JSON number 反序列化后的 {@link Number}，也支持非空字符串数字。</p>
     *
     * @param value 原始参数值
     * @return Double 值；参数为空或空字符串时返回 null
     */
    private Double readDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Double.parseDouble(text);
        }
        return null;
    }

    /**
     * 从通用参数值中读取 Long。
     *
     * <p>用于读取 shopId 这类整数 ID 参数，兼容 JSON number 和字符串数字。</p>
     *
     * @param value 原始参数值
     * @return Long 值；参数为空或空字符串时返回 null
     */
    private Long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return null;
    }

    /**
     * 从通用参数值中读取 Integer。
     *
     * <p>用于读取 topK 这类整型参数，兼容 JSON number 和字符串数字。</p>
     *
     * @param value 原始参数值
     * @return Integer 值；参数为空或空字符串时返回 null
     */
    private Integer readInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return null;
    }

    /**
     * 构造对象类型的 JSON Schema。
     *
     * @param properties 字段名到字段 Schema 的映射
     * @param required 必填字段名列表
     * @return MCP 工具 inputSchema 使用的 object schema
     */
    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 按传入顺序构造 properties Map。
     *
     * <p>使用 {@link LinkedHashMap} 保持字段声明顺序，便于 tools/list 返回的
     * JSON Schema 更稳定、可读。</p>
     *
     * @param entries 字段名和字段 Schema 组成的条目
     * @return 有序的 properties Map
     */
    @SafeVarargs
    private Map<String, Object> properties(Map.Entry<String, Object>... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            properties.put(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    /**
     * 构造 string 类型字段的 JSON Schema。
     *
     * @param name 字段名
     * @param description 字段说明
     * @return 字段名和字段 Schema 条目
     */
    private Map.Entry<String, Object> stringProperty(String name, String description) {
        return Map.entry(name, Map.of("type", "string", "description", description));
    }

    /**
     * 构造带枚举值限制的 string 类型字段 JSON Schema。
     *
     * @param name 字段名
     * @param description 字段说明
     * @param values 允许的枚举值
     * @return 字段名和字段 Schema 条目
     */
    private Map.Entry<String, Object> enumProperty(String name, String description, List<String> values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        schema.put("enum", values);
        return Map.entry(name, schema);
    }

    /**
     * 构造 number 类型字段的 JSON Schema。
     *
     * @param name 字段名
     * @param description 字段说明
     * @return 字段名和字段 Schema 条目
     */
    private Map.Entry<String, Object> numberProperty(String name, String description) {
        return Map.entry(name, Map.of("type", "number", "description", description));
    }

    /**
     * 构造 integer 类型字段的 JSON Schema。
     *
     * @param name 字段名
     * @param description 字段说明
     * @return 字段名和字段 Schema 条目
     */
    private Map.Entry<String, Object> integerProperty(String name, String description) {
        return Map.entry(name, Map.of("type", "integer", "description", description));
    }

    /**
     * 构造 Long/ID 参数使用的 integer 类型 JSON Schema。
     *
     * <p>JSON Schema 没有 Java Long 类型，这里统一用 integer 表示。</p>
     *
     * @param name 字段名
     * @param description 字段说明
     * @return 字段名和字段 Schema 条目
     */
    private Map.Entry<String, Object> longProperty(String name, String description) {
        return integerProperty(name, description);
    }
}
