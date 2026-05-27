package com.lightnote.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class McpJsonRpcService {

    private static final String JSON_RPC_VERSION = "2.0";
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpToolService mcpToolService;
    private final ObjectMapper objectMapper;


    /**
     * 处理一次 MCP JSON-RPC 请求。
     *
     * <p>这是 POST /mcp 进入服务层后的总入口：先校验请求对象是否为空，
     * 再根据 method 分发执行，最后把执行结果包装成 JSON-RPC 的 result
     * 响应；如果过程中出现异常，则包装成 JSON-RPC 的 error 响应。</p>
     *
     * @param request 客户端发送的 JSON-RPC 请求对象
     * @return JSON-RPC 响应对象；notification 请求没有 id 时返回空 Map
     */
    public Map<String, Object> handle(McpJsonRpcRequest request) {
        if (request == null) {
            return error(null, -32600, "Invalid JSON-RPC request");
        }
        try {
            Object result = dispatch(request);
            if (request.getId() == null) {
                return Map.of();
            }
            return response(request.getId(), result);
        } catch (IllegalArgumentException e) {
            return error(request.getId(), -32601, e.getMessage());
        } catch (Exception e) {
            return error(request.getId(), -32603, e.getMessage());
        }
    }

    /**
     * 根据 JSON-RPC method 分发到具体 MCP 方法。
     *
     * <p>当前支持 initialize、tools/list、tools/call 和
     * notifications/initialized。其他 method 会被视为不支持的方法。</p>
     *
     * @param request 已解析的 JSON-RPC 请求
     * @return 对应 MCP 方法的业务结果
     * @throws JsonProcessingException 工具调用结果序列化为文本内容失败时抛出
     */
    private Object dispatch(McpJsonRpcRequest request) throws JsonProcessingException {
        String method = request.getMethod();
        if ("initialize".equals(method)) {
            return initializeResult();
        }
        if ("tools/list".equals(method)) {
            return Map.of("tools", mcpToolService.listTools());
        }
        if ("tools/call".equals(method)) {
            return callTool(request.getParams());
        }
        if ("notifications/initialized".equals(method)) {
            return Map.of();
        }
        throw new IllegalArgumentException("Unsupported MCP method: " + method);
    }

    /**
     * 构造 MCP initialize 方法的返回结果。
     *
     * <p>initialize 用于告诉客户端当前服务的协议版本、服务名称、服务版本以及支持的能力。
     * 这里声明了 tools 能力，并标记工具列表不会动态变化。</p>
     *
     * @return MCP 初始化响应中的 result 内容
     */
    private Map<String, Object> initializeResult() {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "lightnote-mcp-server");
        serverInfo.put("version", "0.1.0");

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("serverInfo", serverInfo);
        result.put("capabilities", capabilities);
        return result;
    }

    /**
     * 执行 MCP tools/call 方法。
     *
     * <p>该方法从 params 中读取工具名称 name 和工具参数 arguments，
     * 调用 {@link McpToolService} 执行真实业务工具，并把工具返回值序列化成
     * MCP content 数组中的 text 内容。</p>
     *
     * @param params tools/call 的参数，包含 name 和 arguments
     * @return MCP tools/call 的 result 内容
     * @throws JsonProcessingException 工具结果转 JSON 文本失败时抛出
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> params) throws JsonProcessingException {
        if (params == null || params.get("name") == null) {
            throw new IllegalArgumentException("Missing tool name");
        }
        String name = String.valueOf(params.get("name"));
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        //MCP 协议层 -> 项目业务工具层
        Object toolResult = mcpToolService.callTool(name, arguments);
        String text = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolResult);
        return Map.of(
                "content", java.util.List.of(Map.of("type", "text", "text", text)),
                "isError", false
        );
    }

    /**
     * 构造 JSON-RPC 成功响应。
     *
     * @param id 客户端请求中的 id，响应时原样带回
     * @param result 方法执行结果
     * @return 符合 JSON-RPC 2.0 格式的成功响应
     */
    private Map<String, Object> response(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    /**
     * 构造 JSON-RPC 错误响应。
     *
     * @param id 客户端请求中的 id；请求无效到无法解析 id 时可为 null
     * @param code JSON-RPC 错误码，例如 -32600、-32601、-32603
     * @param message 错误说明
     * @return 符合 JSON-RPC 2.0 格式的错误响应
     */
    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "MCP request failed" : message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.put("id", id);
        response.put("error", error);
        return response;
    }
}
