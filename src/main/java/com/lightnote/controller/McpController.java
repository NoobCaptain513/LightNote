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

    @GetMapping
    public Map<String, Object> info() {
        return Map.of(
                "name", "lightnote-mcp-server",
                "transport", "http-json-rpc",
                "endpoint", "/mcp",
                "methods", java.util.List.of("initialize", "tools/list", "tools/call")
        );
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jsonRpc(@RequestBody McpJsonRpcRequest request) {
        return mcpJsonRpcService.handle(request);
    }
}
