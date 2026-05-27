package com.lightnote.controller;

import com.lightnote.mcp.McpJsonRpcRequest;
import com.lightnote.mcp.McpJsonRpcService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpJsonRpcService mcpJsonRpcService;

    /**
     * 返回 MCP 服务的基础探测信息。
     *
     * <p>这个接口给人或客户端快速确认服务是否在线，以及当前 MCP 服务采用的
     * 传输方式、请求入口和支持的核心方法。真正的 JSON-RPC 调用仍然走 POST /mcp。</p>
     *
     * @return MCP 服务名称、传输方式、入口地址和支持的方法列表
     */
    @GetMapping
    public Map<String, Object> info() {
        return Map.of(
                "name", "lightnote-mcp-server",
                "transport", "http-json-rpc",
                "endpoint", "/mcp",
                "methods", java.util.List.of("initialize", "tools/list", "tools/call")
        );
    }

    /**
     * 接收并处理 MCP JSON-RPC 请求。
     *
     * <p>Spring 会先把请求体中的 JSON 反序列化成 {@link McpJsonRpcRequest}，
     * 然后交给 {@link McpJsonRpcService} 统一分发到 initialize、tools/list、
     * tools/call 等 MCP 方法。</p>
     *
     * @param request 客户端发送的 JSON-RPC 请求对象
     * @return JSON-RPC 标准响应，包含 result 或 error
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jsonRpc(@RequestBody McpJsonRpcRequest request) {
        return mcpJsonRpcService.handle(request);
    }
}
