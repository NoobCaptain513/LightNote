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

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> params) throws JsonProcessingException {
        if (params == null || params.get("name") == null) {
            throw new IllegalArgumentException("Missing tool name");
        }
        String name = String.valueOf(params.get("name"));
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        Object toolResult = mcpToolService.callTool(name, arguments);
        String text = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolResult);
        return Map.of(
                "content", java.util.List.of(Map.of("type", "text", "text", text)),
                "isError", false
        );
    }

    private Map<String, Object> response(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.put("id", id);
        response.put("result", result);
        return response;
    }

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
