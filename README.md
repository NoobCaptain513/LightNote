# 探店社交与 AI Agent 系统

一个基于 **Spring Boot 3 + Redis + WebSocket + AI Agent** 的本地生活项目，在大众点评类业务的基础上，扩展了 **私聊实时通信、AI 探店助手、RAG、流式输出、成本统计** 等能力。

这个项目不是单纯“接一个大模型接口”，而是围绕真实业务数据，把 **店铺检索、优惠券查询、评分排序、距离排序、历史会话、结构化卡片、知识检索、流式返回** 做成了一套完整的 Java AI 应用后端。

---

## 1. 项目亮点

- 基于 **Spring Boot 3.4 + MyBatis-Plus + MySQL + Redis** 构建本地生活后端。
- 支持 **店铺查询、优惠券、笔记、点赞、关注** 等基础业务能力。
- 基于 **WebSocket / STOMP** 扩展私聊模块，支持消息持久化、历史消息查询、在线推送。
- 自研 **AI Agent 编排链路**，支持店铺搜索、店铺详情、优惠券查询等 Tool Calling。
- 支持三套 AI 实现方式切换：
  - `native`：原生 HTTP / OkHttp 手写编排
  - `spring-ai`：Spring AI 版本
  - `langchain4j`：LangChain4j 版本
- 增强了三项更偏工程化的 AI 能力：
  - `RAG`：基于业务数据构建轻量知识库
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
- MySQL 8
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

- `RAG`：轻量知识库构建与检索
- `流式输出`：SSE 分段返回
- `成本统计`：记录 provider、model、chat/agent 模式、token、耗时、费用估算

---

## 4. 三套 AI 实现方案

本项目保留了三种 AI Provider 方案，便于对比不同框架的实现方式，也方便面试时展示“原理理解 + 工程化能力”。

### 4.1 Native 版

特点：

- 原生 HTTP / OkHttp 调用模型
- 手写 Prompt / Tool / Tool Result 回填
- 最能体现对 Agent 底层流程的理解

适合展示：

- 对 Tool Calling 原理的掌握
- 对模型请求结构的理解
- 对 AI 编排流程的掌控

### 4.2 Spring AI 版

特点：

- 使用 `ChatClient`
- 可结合 `Advisor`、`Memory`、`@Tool`
- 更贴近 Spring Boot 工程整合风格

适合展示：

- Spring 生态下的 AI 应用开发
- 企业 Java 后端工程化接入 AI 的方式

### 4.3 LangChain4j 版

特点：

- 使用 `AiServices`
- 支持更自然的 Tool Calling / Memory / TokenStream 表达
- 更贴近 Java AI 应用开发社区风格

适合展示：

- Java Agent 应用开发能力
- LLM Tool / Memory / Streaming 的框架化使用

### 4.4 当前切换方式

通过配置切换当前启用的 provider：

```yaml
ai:
  provider:
    type: native
```

可选值：

- `native`
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
│       │   ├── nativeprovider
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
3. `AbstractAiProviderService` 作为三套 provider 的共享编排层。
4. `conversation / prompt / intent / tool / rag / stream / usage` 负责增强模型的业务能力。
5. 不把 AI 理解成单个接口，而是理解成一条“消息 -> 意图 -> prompt -> 工具 -> 结果 -> 历史 -> 观测”的完整链路。

### 6.1 Agent 调用链

用户提问，例如：

> 推荐附近评分高一点、带优惠券的火锅店

后端大致执行流程：

1. 前端请求 `/ai/agent`
2. 服务层校验用户和消息内容
3. 裁剪历史消息
4. 识别意图：优惠券 / 评分 / 距离
5. 拼接 Agent Prompt
6. 调用 RAG 检索知识片段
7. provider 调模型
8. 模型决定是否调用工具
9. 工具查询真实业务数据
10. 汇总为结构化店铺卡片
11. 返回自然语言 + 卡片结构
12. 落库消息历史
13. 记录 usage 成本日志

---

## 7. RAG / 流式输出 / 成本统计

### 7.1 RAG

当前项目实现的是一套轻量版 RAG：

- 知识表：`tb_ai_knowledge`
- 来源：店铺、店铺类型、优惠券等业务数据
- 向量化：本地向量器 `LocalVectorizer`
- 检索：向量相似度 + 关键词重叠混合评分

支持接口：

```http
POST /ai/rag/rebuild
GET /ai/rag/search?query=推荐附近火锅店&topK=3
```

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
- Native 伪流式兜底

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

---

## 10. 面试可讲亮点

如果把这个项目用于面试，推荐重点讲这几类内容：

### 10.1 Java 后端能力

- Spring Boot 3 升级
- MyBatis-Plus 数据访问
- Redis 缓存、Geo、回补机制
- WebSocket 私聊
- 业务模块拆分与服务封装

### 10.2 AI 能力

- 手写 Native Agent 编排
- Spring AI 版实现
- LangChain4j 版实现
- Tool Calling
- RAG
- SSE 流式输出
- 成本统计

### 10.3 工程问题与修复经验

项目开发过程中重点解决过的问题包括：

- 历史消息上下文污染
- Agent 卡片刷新后丢失
- 类型词查询不到店铺
- 距离过滤误杀全部结果
- Redis 与数据库不一致时的查询回补
- 前后端联调时请求路径 / 流式消费问题

---

## 11. 后续可升级方向

当前项目已经具备较完整的 AI 应用形态，后续仍可继续升级：

- 安全收口：密钥、密码、鉴权边界
- 会话管理：多会话、会话标题、会话归档
- 向量库升级：`pgvector / Milvus / ES Vector`
- 工具编排升级：失败降级、重试、推荐理由
- 可观测性：调用统计看板、错误率、成本趋势
- 私聊 + AI 联动：聊天中 `@AI` 推荐店铺

---

## 12. 说明

本项目当前保留了较完整的 AI 实验与演进轨迹，因此仓库里可能同时存在：

- Native 原生实现
- Spring AI 实现
- LangChain4j 实现
- 文档与重构草稿

这对于学习和面试展示是优点，但如果要继续工程化，可以进一步整理目录与配置边界。

---

## 13. License

仅用于学习、交流与个人项目展示。