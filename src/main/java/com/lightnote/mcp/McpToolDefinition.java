package com.lightnote.mcp;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class McpToolDefinition {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;
}
