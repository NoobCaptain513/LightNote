# AI Refactor Map

## Shared Components

| Package | Class | Purpose |
| --- | --- | --- |
| `com.hmdp.ai.conversation` | `AiConversationService` | Normalize messages, save history, load history, encode/decode `AgentReply` |
| `com.hmdp.ai.intent` | `AgentIntentAnalyzer` | Detect voucher/score/distance intent and resolve sort order |
| `com.hmdp.ai.model` | `AgentIntent` | Shared intent model used by all providers |
| `com.hmdp.ai.prompt` | `AiPromptService` | Build system prompts and agent prompts |
| `com.hmdp.ai.reply` | `ShopCardAssembler` | Convert `Shop`/`Voucher` to response payload and merge cards |
| `com.hmdp.ai.tool` | `ShopAgentToolService` | Execute shared business tools: search shop, voucher, detail |
| `com.hmdp.ai.tool` | `ToolResultCollector` | Parse native tool output and merge it back into shop cards |

## Native Provider

| Old Area | New Package/Class |
| --- | --- |
| HTTP model request and response parsing | `com.hmdp.ai.provider.nativeprovider.NativeAiClient` |
| Native tool schema definition | `com.hmdp.ai.provider.nativeprovider.NativeToolSchemaFactory` |
| Native provider orchestration | `com.hmdp.ai.provider.nativeprovider.AiServiceImpl` |

## Spring AI Provider

| Old Area | New Package/Class |
| --- | --- |
| Spring AI provider orchestration | `com.hmdp.ai.provider.springai.AiSpringAiServiceImpl` |
| Shared tool business logic | `com.hmdp.ai.tool.ShopAgentToolService` |
| Shared shop card merge | `com.hmdp.ai.reply.ShopCardAssembler` |

## LangChain4j Provider

| Old Area | New Package/Class |
| --- | --- |
| LangChain4j provider orchestration | `com.hmdp.ai.provider.langchain4j.AiLangChain4jServiceImpl` |
| Shared tool business logic | `com.hmdp.ai.tool.ShopAgentToolService` |
| Shared shop card merge | `com.hmdp.ai.reply.ShopCardAssembler` |
