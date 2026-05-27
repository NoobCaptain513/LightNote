package com.lightnote.mcp;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * MCP 工具定义模型。
 *
 * <p>tools/list 返回的每一个工具都会被描述成这个结构，外部 MCP Client
 * 根据工具名称、描述和输入 JSON Schema 判断如何调用 tools/call。</p>
 */
@Data
@AllArgsConstructor
public class McpToolDefinition {
    /** 工具名称，调用 tools/call 时 params.name 必须与它一致。 */
    private String name;

    /** 工具说明，供外部 AI 客户端理解工具用途。 */
    private String description;

    /** 工具输入参数的 JSON Schema，用于描述参数类型、必填项和可选值。 */
    private Map<String, Object> inputSchema;
}
