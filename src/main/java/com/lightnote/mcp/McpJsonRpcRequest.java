package com.lightnote.mcp;

import lombok.Data;

import java.util.Map;

/**
 * MCP JSON-RPC 请求体模型。
 *
 * <p>客户端调用 POST /mcp 时，请求体会被 Spring/Jackson 反序列化为这个对象。
 * 该类只承载请求数据，不负责协议校验、方法分发或工具执行。</p>
 */
@Data
public class McpJsonRpcRequest {
    /** JSON-RPC 协议版本，正常情况下为 "2.0"。 */
    private String jsonrpc;

    /** 请求 ID；服务端响应时会原样带回，用于客户端匹配请求和响应。 */
    private Object id;

    /** 要调用的 MCP/JSON-RPC 方法名，例如 initialize、tools/list、tools/call。 */
    private String method;

    /** 方法参数；不同 method 的参数结构不同，因此使用通用 Map 承载。 */
    private Map<String, Object> params;
}
