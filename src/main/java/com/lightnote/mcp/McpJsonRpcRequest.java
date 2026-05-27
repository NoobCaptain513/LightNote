package com.lightnote.mcp;

import lombok.Data;

import java.util.Map;

@Data
public class McpJsonRpcRequest {
    private String jsonrpc;
    private Object id;
    private String method;
    private Map<String, Object> params;
}
