# 探店社交与 AI Agent 系统

一个基于 **Spring Boot 3 + Redis + WebSocket + AI Agent** 的本地生活项目，在大众点评类业务的基础上，扩展了 **私聊实时通信、AI 探店助手、RAG、流式输出、成本统计** 等能力。

这个项目不是单纯“接一个大模型接口”，而是围绕真实业务数据，把 **店铺检索、优惠券查询、评分排序、距离排序、历史会话、结构化卡片、知识检索、流式返回** 做成了一套完整的 Java AI 应用后端。

---

## 1. 项目亮点

- 基于 **Spring Boot 3.4 + MyBatis-Plus + MySQL + Redis** 构建本地生活后端。
- 支持 **店铺查询、优惠券、笔记、点赞、关注** 等基础业务能力。
- 基于 **WebSocket / STOMP** 扩展私聊模块，支持消息持久化、历史消息查询、在线推送。
- 自研 **AI Agent 编排链路**，支持店铺搜索、店铺详情、优惠券查询等 Tool Calling。
- 支持两套 AI 实现方式切换：
  - `spring-ai`：Spring AI 版本
  - `langchain4j`：LangChain4j 版本
- 增强了三项更偏工程化的 AI 能力：
  - `RAG`：基于业务数据构建 pgvector 向量知识库，并通过 Embedding API 检索增强回答
  - `SSE 流式输出`：支持 `/chat/stream`、`/agent/stream`
  - `成本统计`：记录 token 估算、耗时、成功率、调用模式
- Agent 返回的不只是文本，还支持 **结构化店铺卡片**，前端可直接渲染并跳转详情页。

---

## 2. 技术栈

### 后端基础

- Java 17
- Spring Boot 3.4.13
- Spring MVC
- MyBatis-Plus 3.5.9
- Spring JDBC / JdbcTemplate
- MySQL 8
- PostgreSQL + pgvector
- Flyway
- Redis
- Redisson
- Hutool
- Lombok
- OkHttp

### 实时通信

- Spring WebSocket
- STOMP

### AI 相关

- Spring AI 1.1.5
- LangChain4j 1.13.1
- DashScope / Qwen Compatible API
- OpenAI-compatible Embedding API
- SSE (`SseEmitter`)

---

## 3. 核心功能

### 3.1 探店业务

- 店铺查询
- 店铺详情
- 按类型筛选
- 按评分排序
- 按距离排序
- 优惠券查询
- 博客/探店笔记互动

### 3.2 Redis 设计与高并发处理

Redis 在这个项目里不只是“顺手做个缓存”，而是承担了 **高频读优化、附近店铺检索、登录态存储、并发控制、异步秒杀削峰** 这几类关键职责。

#### 3.2.1 店铺缓存

围绕店铺详情做了多层缓存设计：

- 使用 `cache:shop:{id}` 缓存店铺详情，降低热点店铺反复查数据库的压力。
- 通过 `CacheClient.queryWithPassThrough(...)` 缓解 **缓存穿透**：
  - 数据不存在时写入空值
  - 短 TTL 保护数据库
- 通过 `CacheClient.queryWithMutex(...)` 缓解 **缓存击穿**：
  - 热点 key 失效后只允许一个线程重建缓存
  - 其他线程短暂休眠后重试
  - 避免高并发同时打到数据库
- 通过 `CacheClient.queryWithLogicalExpire(...)` 提供 **逻辑过期 + 异步重建** 能力：
  - 热点数据即使过期，也先返回旧值
  - 后台线程异步重建缓存
  - 降低高峰期因重建带来的响应抖动

#### 3.2.2 附近店铺检索

项目使用 Redis Geo 存储店铺坐标：

- key 设计：`shop:geo:{typeId}`
- value：店铺 id + 经纬度
- 查询时先按类型命中 Geo 集合，再按距离返回分页结果

这样做相比单纯走 MySQL：

- 附近搜索更快
- 距离排序更自然
- 适合“美食 / 咖啡 / KTV / 美甲”这类本地生活场景

#### 3.2.3 查询回补与缓存一致性

项目里还做了两层一致性处理：

- **新增 / 更新店铺时**：同步写店铺详情缓存和 Geo 索引
- **查询店铺时**：如果数据库有但 Redis 没有，会自动回补 Redis

这样可以解决两类实际问题：

- 新增店铺后缓存里没有，下一次查询自动补齐
- Geo 索引或详情缓存缺失时，不会长期依赖数据库裸查

#### 3.2.4 登录态与用户状态

Redis 还承担了用户会话存储：

- 登录后把用户信息写入 Redis
- 通过拦截器刷新 token TTL
- 减少服务端 session 压力
- 更适合前后端分离项目

#### 3.2.5 秒杀与高并发下单

在优惠券秒杀场景中，Redis 解决的是“高并发写”的问题，而不仅是读缓存：

- 使用 Lua 脚本在 Redis 中原子校验：
  - 库存是否充足
  - 用户是否重复下单
- 使用 Redis 生成订单消息并写入 Stream
- 后台单线程消费者异步处理订单落库
- 使用 Redisson 分布式锁做“一人一单”兜底控制
- 使用 `RedisIdWorker` 生成全局唯一订单号

这套链路解决了几个典型并发问题：

- **超卖问题**：库存判断和扣减前置到 Redis 原子脚本，避免并发下数据库先查后改导致超卖。
- **重复下单问题**：Redis 脚本先拦截同一用户重复秒杀，后端再用分布式锁兜底。
- **瞬时流量打爆数据库问题**：通过 Redis Stream 把同步下单改成“前端快速返回 + 后台异步创建订单”。
- **分布式环境下一致性问题**：Redisson 锁保证同一用户并发请求下只有一个线程真正创建订单。

#### 3.2.6 面试里可以怎么讲 Redis

如果面试官问“你项目里 Redis 具体做了什么”，可以按这条线回答：

1. 做店铺详情缓存，解决高频读问题。  
2. 用空值缓存 + 互斥锁 / 逻辑过期，解决缓存穿透、缓存击穿和热点重建问题。  
3. 用 Redis Geo 做附近店铺检索，支撑距离排序。  
4. 用 Redis 保存登录态，适配前后端分离。  
5. 在秒杀场景里用 Lua + Stream + 分布式锁，解决超卖、重复下单和高并发削峰问题。

### 3.3 私聊系统

- 会话建模
- 历史消息查询
- WebSocket 实时推送
- 在线状态配合消息分发

### 3.4 AI Agent

- 普通聊天：`/ai/chat`
- Agent 问答：`/ai/agent`
- 历史消息恢复：`/ai/history`
- 工具调用：
  - `searchShop(keyword, sortBy, x, y)`
  - `getVoucher(shopId)`
  - `getShopDetail(shopId)`
- 结构化返回：`text + shops`
- 卡片历史持久化与页面恢复

### 3.5 AI 工程化增强

- `RAG`：店铺业务数据构建 `ai_knowledge_chunk` 向量知识库，支持 pgvector 相似度检索和轻量重排
- `Embedding`：通过兼容 `/embeddings` 协议的 API 生成文本向量，向量维度与 pgvector 表结构保持一致
- `Flyway`：独立管理 pgvector 表结构、HNSW 向量索引和迁移历史
- `流式输出`：SSE 分段返回
- `成本统计`：记录 provider、model、chat/agent 模式、token、耗时、费用估算

---

## 4. 两套 AI 实现方案

本项目保留了两种 AI Provider 方案，聚焦 Spring AI 与 LangChain4j 两种主流 Java AI 框架的工程化实现方式。

### 4.1 Spring AI 版

特点：

- 使用 `ChatClient` 作为统一调用入口。
- 使用 `SpringAiSafetyAdvisor` 做请求级安全约束增强。
- 普通聊天使用 `SpringAiRagAdvisor` 在 Advisor 链路中注入 RAG 上下文，而不是在 provider 里硬拼 prompt。
- Agent 聊天保持工具优先，不挂 RAG Advisor；调模型前会用 RAG `sourceId` 召回候选店铺，并通过业务详情接口回查成真实候选数据。
- 使用 `MessageChatMemoryAdvisor` 管理本轮请求历史。
- 使用 `SpringAiShopToolFactory` 生成带 `@Tool` 方法的工具对象，工具业务逻辑仍由 Spring Bean 注入和复用。
- Agent 回复结束前会检查 `AgentReply.shops`，如果模型没有调用工具，会优先合并前置 RAG 候选卡片，再用工具搜索兜底补齐 shopcard。
- 更贴近 Spring Boot 企业后端的工程整合风格。

适合展示：

- Spring 生态下的 AI 应用开发
- 企业 Java 后端工程化接入 AI 的方式
- Advisor 链、Spring Bean 工具、请求级增强、可观测性扩展点

### 4.2 LangChain4j 版

特点：

- 使用 `AiServices` 声明式定义 Assistant。
- 使用 `@SystemMessage`、`@UserMessage`、`@MemoryId` 表达对话接口。
- 使用 `LangChain4jPersistentChatMemoryStore` 接入持久化记忆，按用户区分 `lc4j_chat` / `lc4j_agent` 两类记忆。
- 普通聊天使用 `RetrievalAugmentor` + `LangChain4jRagContentRetriever` 接入 RAG，而不是手动拼接 system prompt。
- Agent 聊天保持工具优先，不挂 `RetrievalAugmentor`；调模型前会用 RAG `sourceId` 召回候选店铺，并通过业务详情接口回查成真实候选数据。
- Tool 直接返回 `List<Map<String, Object>>` / `Map<String, Object>` 等结构化结果，体现 LangChain4j typed tools 风格。
- Agent 回复结束前会检查 `AgentReply.shops`，如果模型没有调用工具，会优先合并前置 RAG 候选卡片，再用工具搜索兜底补齐 shopcard。
- 更贴近 Java Agent / Assistant 应用开发风格。

适合展示：

- Java Agent 应用开发能力
- LLM Tool / Memory / Streaming 的框架化使用
- 声明式 AI Service、持久化 Memory、RetrievalAugmentor、typed tools

### 4.3 差异化设计对照

| 能力 | Spring AI 版 | LangChain4j 版 |
| --- | --- | --- |
| 调用入口 | `ChatClient` fluent API | `AiServices` 声明式 Assistant |
| RAG 接入 | 普通 chat 使用 `SpringAiRagAdvisor`；agent 前置使用 RAG sourceId 召回真实店铺候选，shopcard 缺失时合并候选兜底 | 普通 chat 使用 `RetrievalAugmentor` + `ContentRetriever`；agent 前置使用 RAG sourceId 召回真实店铺候选，shopcard 缺失时合并候选兜底 |
| 记忆 | `MessageChatMemoryAdvisor` 使用请求级窗口记忆 | `ChatMemoryStore` 持久化用户长期记忆 |
| 工具 | Spring 管理的 `@Tool` 工具对象，工具输出序列化为 JSON | LangChain4j typed tools，直接返回结构化 Java 对象 |
| Agent 卡片 | 工具调用收集 `AgentReply.shops`，缺失时后端兜底补齐 | 工具调用收集 `AgentReply.shops`，缺失时后端兜底补齐 |
| 定位 | 企业 Spring Boot AI 接入 | Agent / Assistant 应用编排 |

### 4.4 当前切换方式

通过配置切换当前启用的 provider：

```yaml
ai:
  provider:
    type: spring-ai
```

可选值：

- `spring-ai`
- `langchain4j`

---

## 5. 项目结构

```text
hm-dianping
├── src/main/java/com/hmdp
│   ├── controller
│   ├── service
│   ├── dto
│   ├── entity
│   ├── mapper
│   ├── config
│   └── ai
│       ├── conversation
│       ├── prompt
│       ├── intent
│       ├── model
│       ├── reply
│       ├── tool
│       ├── provider
│       │   ├── springai
│       │   └── langchain4j
│       ├── rag
│       ├── stream
│       └── usage
├── src/main/resources
│   ├── application.yaml
│   └── db/lightnote.sql
└── docs
```

---

## 6. AI 模块设计说明

当前 AI 模块的核心设计思路是：

1. `AiController` 作为统一入口。
2. `IAiService` 作为统一服务接口。
3. `AbstractAiProviderService` 只保留用户校验、历史保存、意图识别、usage 记录等公共业务编排。
4. `conversation / prompt / intent / tool / rag / stream / usage` 负责增强模型的业务能力。
5. Spring AI / LangChain4j 在普通 chat 内使用框架原生扩展点接入 RAG，在 agent 内保持工具优先。
6. 不把 AI 理解成单个接口，而是理解成一条“消息 -> 意图 -> prompt -> 工具 -> 结果 -> 历史 -> 观测”的完整链路。

### 6.1 Agent 调用链

用户提问，例如：

> 推荐附近评分高一点、带优惠券的火锅店

后端大致执行流程：

1. 前端请求 `/ai/agent`
2. 服务层校验用户和消息内容
3. 裁剪历史消息
4. 识别意图：优惠券 / 评分 / 距离
5. 拼接 Agent Prompt
6. provider 调模型，并要求模型优先调用店铺搜索、详情、优惠券工具
7. 工具查询真实业务数据，并把结果合并到 `collectedShopMap`
8. 如果模型没有调用工具，后端用用户原句兜底搜索；仍没有卡片时，再用 RAG 命中的 `sourceId` 回查店铺详情
9. 汇总为结构化店铺卡片 `AgentReply.shops`
10. 返回自然语言 + 卡片结构
11. 落库消息历史
12. 记录 usage 成本日志

---

## 7. RAG / 流式输出 / 成本统计

### 7.1 RAG

当前项目实现的是一套轻量版 RAG：

- 知识表：PostgreSQL/pgvector 中的 `ai_knowledge_chunk`
- 来源：店铺、店铺类型、优惠券等业务数据
- 表结构：通过 Flyway 脚本 `db/migration/pgvector/V1__init_ai_knowledge_chunk.sql` 初始化
- 数据访问：`PgVectorKnowledgeRepository` 使用 pgvector 专用 `JdbcTemplate`
- 向量化：`CompatibleEmbeddingClient` 调用兼容 `/embeddings` 协议的 Embedding API，默认模型为 `text-embedding-v4`
- 检索：pgvector 余弦距离召回 `candidateK` 条候选，再按 `85%` 向量分 + `15%` 字面重叠分重排，最终返回 `topK`
- 使用方式：
  - native provider：公共层构建 system prompt 时追加 RAG 上下文
  - Spring AI 普通 chat：`SpringAiRagAdvisor` 在 Advisor 链中追加 RAG 上下文
  - LangChain4j 普通 chat：`LangChain4jRagContentRetriever` 通过 `RetrievalAugmentor` 注入 RAG 内容
  - Spring AI / LangChain4j agent：工具优先，不直接注入原始 RAG 文本；调模型前先用 RAG 命中的 `sourceId` 回查真实店铺详情，作为候选提示和 shopcard 兜底来源

管理/调试接口：

```http
POST /ai/rag/rebuild
GET /ai/rag/search?query=推荐附近火锅店&topK=3
```

其中 `/rag/rebuild` 用于重建知识库，`/rag/search` 用于查看检索命中和分数，属于管理或开发调试能力，不建议直接作为普通用户端功能暴露。

### 7.2 流式输出

支持：

```http
POST /ai/chat/stream
POST /ai/agent/stream
```

当前流式能力包括：

- 公共层 SSE 事件封装
- `start / delta / done / error` 事件协议
- Spring AI / LangChain4j 真流式支持

### 7.3 成本统计

会记录：

- 当前用户
- provider 类型
- model 名称
- chat / agent 模式
- prompt token
- completion token
- total token
- estimated cost
- latency
- success / error

支持接口：

```http
GET /ai/usage/recent?limit=10
```

---

## 8. 启动方式

### 8.1 环境要求

- JDK 17
- Maven 3.8+
- MySQL 8+
- PostgreSQL + pgvector
- Redis 6+

### 8.2 数据库准备

创建数据库：

```sql
CREATE DATABASE hmdp DEFAULT CHARACTER SET utf8mb4;
```

然后执行：

- `src/main/resources/db/lightnote.sql`

### 8.3 配置说明

当前 `application.yaml` 中包含本地开发配置，但 **上传 GitHub 前建议改成环境变量**，尤其是：

- 数据库密码
- Redis 密码
- AI API Key

推荐写法：

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}

ai:
  api-key: ${AI_API_KEY:}
  embedding:
    base-url: ${AI_EMBEDDING_BASE_URL:${ai.compatible-base-url}}
    api-key: ${AI_EMBEDDING_API_KEY:${ai.api-key}}
    model: ${AI_EMBEDDING_MODEL:text-embedding-v4}
    dimensions: ${AI_EMBEDDING_DIMENSIONS:1536}
  rag:
    pgvector:
      url: ${PGVECTOR_URL:jdbc:postgresql://127.0.0.1:5432/lightnote_rag}
      username: ${PGVECTOR_USERNAME:lightnote}
      password: ${PGVECTOR_PASSWORD:lightnote}
```

### 8.4 启动项目

启动入口：

- `com.lightnote.HmDianPingApplication`

命令行启动：

```bash
mvn spring-boot:run
```

或者打包后运行：

```bash
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

默认端口：

- `8081`

---

## 9. 典型接口示例

### 9.1 普通聊天

```http
POST /ai/chat
Content-Type: application/json
```

```json
{
  "messages": [
    {"role": "user", "content": "你好，推荐几家咖啡店"}
  ]
}
```

### 9.2 Agent 问答

```http
POST /ai/agent
Content-Type: application/json
```

```json
{
  "messages": [
    {"role": "user", "content": "推荐附近评分高一点、带优惠券的火锅店"}
  ],
  "x": 120.149993,
  "y": 30.334229
}
```

### 9.3 流式输出

```http
POST /ai/agent/stream
```

SSE 事件示意：

```text
event: start
data: {"type":"start"}

event: delta
data: {"type":"delta","content":"正在为你查找..."}

event: done
data: {"type":"done","text":"...","data":{...}}
```

### 9.4 MCP 工具服务

项目提供轻量 HTTP JSON-RPC 形式的 MCP Server，用于把 LightNote 业务能力暴露给外部 AI 客户端或 Agent：

```http
GET  /mcp
POST /mcp
```

当前支持的 MCP 方法：

- `initialize`：返回服务信息与工具能力。
- `tools/list`：列出可调用工具及 JSON Schema。
- `tools/call`：调用具体工具。

已暴露工具：

- `search_shop`：按关键词、类型、商圈或地址搜索店铺，支持评分/距离排序。
- `get_shop_detail`：查询店铺详情。
- `get_voucher`：查询店铺优惠券。
- `rag_search`：检索 pgvector RAG 知识库。
- `rebuild_rag`：重建 RAG 知识库。

如果要让 Codex 像使用内置工具一样发现这些工具，需要通过标准 stdio MCP Server 接入。项目提供了本地桥接脚本：

```text
mcp-bridge/lightnote-mcp-stdio.js
```

调用链路为：

```text
Codex MCP Client -> stdio MCP bridge -> LightNote /mcp -> 项目业务工具
```

Codex 配置示例：

```toml
[mcp_servers.lightnote]
command = "D:\\develop\\NodeJs\\node.exe"
args = ["D:\\javaproject\\LightNote\\mcp-bridge\\lightnote-mcp-stdio.js"]
env = { LIGHTNOTE_MCP_URL = "http://localhost:8081/mcp" }
```

使用前先启动 LightNote 后端，确保 `http://localhost:8081/mcp` 可访问；然后重启 Codex 或开启新会话，Codex 会通过 `initialize` 和 `tools/list` 自动发现工具。

调用示例：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "search_shop",
    "arguments": {
      "keyword": "火锅",
      "sortBy": "score_desc"
    }
  }
}
```

---

## 10. License

仅用于学习、交流与个人项目展示。
