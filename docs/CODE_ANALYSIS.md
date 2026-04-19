# DataAgent 代码架构分析报告

**分析日期**: 2026-04-18  
**分析范围**: 后端核心架构 (data-agent-management)

---

## 一、整体架构概览

**DataAgent** 是一个基于 **Spring AI Alibaba Graph** 构建的企业级智能数据分析 Agent 系统，采用经典的分层架构设计：

**项目模块划分：**
- **data-agent-management**（后端）：Spring Boot 3.4.8 + Java 17，核心业务逻辑
- **data-agent-frontend**（前端）：React + TypeScript，用户交互界面

---

## 二、核心架构设计

### 1. StateGraph 工作流引擎

系统核心采用 **StateGraph** 状态图编排数据分析流程，包含以下关键节点：

| 节点 | 职责 |
|------|------|
| `IntentRecognitionNode` | 用户意图识别 |
| `EvidenceRecallNode` | RAG 证据召回 |
| `QueryEnhanceNode` | 查询重写增强 |
| `SchemaRecallNode` / `TableRelationNode` | 表结构召回与关系推断 |
| `FeasibilityAssessmentNode` | 可行性评估 |
| `PlannerNode` | 分析计划生成 |
| `SqlGenerateNode` / `SqlExecuteNode` | Text-to-SQL 生成与执行 |
| `PythonGenerateNode` / `PythonExecuteNode` | Python 代码生成与执行 |
| `ReportGeneratorNode` | 智能报告生成 |
| `HumanFeedbackNode` | 人工反馈介入点 |

### 2. Python 执行引擎

`CodePoolExecutorService` 提供三种执行器：

- **DockerCodePoolExecutorService**：容器化隔离执行（生产推荐）
- **LocalCodePoolExecutorService**：本地进程执行（开发调试）
- **AiSimulationCodeExecutorService**：AI 模拟执行（降级方案）

### 3. 多数据库连接器

`connector` 包支持多种数据库：

- MySQL、PostgreSQL、Oracle、SQL Server
- 达梦 (Dameng)、Hive、H2

采用 **Accessor + Ddl + ConnectionPool** 三层抽象，便于扩展新数据库。

### 4. MCP 服务器集成

通过 `McpServerService` 提供标准 MCP 工具：

- `nl2SqlToolCallback`：自然语言转 SQL
- `listAgentsToolCallback`：智能体列表管理

---

## 三、关键特性实现

### 1. Human-in-the-Loop 机制

`GraphServiceImpl` 中通过 `interruptBefore(HUMAN_FEEDBACK_NODE)` 实现：

- 用户可在 `PlannerNode` 生成计划后暂停审核
- 支持批准继续或拒绝重新规划
- 通过 `threadId` 恢复执行状态

### 2. 多模型调度

`AiModelRegistry` + `DynamicModelFactory` 实现：

- 运行时动态切换 Chat/Embedding 模型
- 支持 OpenAI 接口规范的厂商（Qwen、DeepSeek 等）
- 同一时间每类模型仅一个激活实例

### 3. RAG 向量检索

`AgentVectorStoreService` 提供统一检索接口：

- 支持业务知识、智能体知识元数据过滤
- 可选混合检索（向量 + 关键词 ES）
- 可插拔向量库（默认 SimpleVectorStore，支持 PGVector、Milvus、ES）

#### 3.1 RAG 召回策略

##### 向量库类型

| 类型 | 配置值 | 说明 |
|------|--------|------|
| SimpleVectorStore | `simple` | 本地 JSON 文件存储（默认） |
| Elasticsearch | `elasticsearch` | 支持混合检索 |
| PGVector | `pgvector` | PostgreSQL 向量扩展 |
| Milvus | `milvus` | 云原生向量数据库 |

##### 检索模式

| 模式 | 说明 |
|------|------|
| 纯向量检索 | 仅使用向量相似度搜索（默认） |
| 混合检索 | 向量搜索 + 关键词搜索并行，结果通过 RRF 融合 |

##### 混合检索实现

| 实现类 | 适用场景 | 向量搜索 | 关键词搜索 | 融合 |
|--------|----------|----------|------------|------|
| `DefaultHybridRetrievalStrategy` | PgVector、Milvus | ✅ 异步 | ❌ 无（返回空） | RRF |
| `ElasticsearchHybridRetrievalStrategy` | ES | ✅ 异步 | ✅ ES Match Query | RRF |

##### 结果融合策略

| 策略 | 类 | 公式 | 状态 |
|------|-----|------|------|
| RRF (Reciprocal Rank Fusion) | `RrfFusionStrategy` | score = Σ 1/(k+rank) | ✅ 已实现 |
| 加权平均 | `WeightedAverageStrategy` | - | ❌ 未实现 |

##### 知识类型

| 类型 | 元数据标识 | 说明 |
|------|------------|------|
| BUSINESS_TERM | `BUSINESS_TERM` | 业务术语知识 |
| AGENT_KNOWLEDGE | `AGENT_KNOWLEDGE` | 智能体知识（含 FAQ、QA、文档） |

#### 3.2 关键代码位置

```
service/
├── vectorstore/
│   ├── AgentVectorStoreService.java        # 向量检索统一接口
│   └── AgentVectorStoreServiceImpl.java    # 实现
├── hybrid/
│   ├── retrieval/
│   │   ├── HybridRetrievalStrategy.java           # 检索策略接口
│   │   ├── AbstractHybridRetrievalStrategy.java   # 模板方法（异步并行检索+融合）
│   │   └── impl/
│   │       ├── DefaultHybridRetrievalStrategy.java        # 无关键词能力实现
│   │       └── ElasticsearchHybridRetrievalStrategy.java  # ES关键词实现
│   ├── fusion/
│   │   ├── FusionStrategy.java              # 融合策略接口
│   │   └── impl/
│   │       ├── RrfFusionStrategy.java      # RRF融合实现
│   │       └── WeightedAverageStrategy.java # 加权平均（未实现）
│   └── factory/
│       └── HybridRetrievalStrategyFactory.java  # 策略工厂
```

#### 3.3 核心配置项

```yaml
spring:
  ai:
    vectorstore:
      type: simple  # 向量库类型: simple/elasticsearch/pgvector/milvus
    alibaba:
      data-agent:
        vector-store:
          default-similarity-threshold: 0.4   # 相似度阈值
          default-topk-limit: 8               # 返回文档数
          enable-hybrid-search: false          # 是否启用混合检索
          elasticsearch-min-score: 0.5         # ES关键词搜索最小分数
          file-path: ./vectorstore/vectorstore.json  # SimpleVectorStore路径
```

### 4. API Key 管理

`AgentController` + `AgentService` 实现：

- API Key 生成、重置、启用/禁用
- 请求头 `X-API-Key` 鉴权（需自行实现拦截器）

---

## 四、数据流

```
用户请求 → GraphController(SSE) → GraphServiceImpl → StateGraph
              ↓
    MultiTurnContextManager（多轮对话管理）
              ↓
    LlmService → AiModelRegistry → Chat/Embedding Model
              ↓
    AgentVectorStoreService → VectorStore
              ↓
    CodePoolExecutorService → Docker/Local Executor
              ↓
    ReportGeneratorNode → SSE 流式返回
```

---

## 五、扩展点

1. **新增数据库**：扩展 `Accessor`/`Ddl`/`ConnectionPool` 实现类
2. **自定义向量库**：引入 Spring AI Vector Store Starter 并配置
3. **新增工作流节点**：实现 `GraphNode` 接口并注册到 StateGraph
4. **Prompt 优化**：通过 `PromptConfigController` 动态配置各节点 Prompt

---

## 六、技术选型评价

| 决策 | 优势 | 权衡 |
|------|------|------|
| Spring AI Alibaba Graph | 统一 Agent 编排范式 | 学习曲线 |
| Docker 容器执行 Python | 安全隔离 | 资源开销 |
| OpenAI 接口兼容 | 多厂商灵活切换 | 需适配非标准 API |
| SSE 流式输出 | 实时反馈体验 | 连接状态管理复杂 |

---

## 七、核心包结构

```
data-agent-management/
├── agent/                    # Agent 核心逻辑
│   ├── planner/             # 计划生成
│   ├── sql/                 # SQL 生成与执行
│   ├── python/              # Python 代码执行
│   ├── rag/                 # RAG 检索增强
│   ├── report/              # 报告生成
│   └── feedback/            # 人工反馈
├── connector/                # 数据库连接器
│   ├── accessor/            # 数据库访问抽象
│   ├── ddl/                 # DDL 生成抽象
│   ├── pool/                # 连接池管理
│   └── impls/               # 各数据库实现
│       ├── mysql/
│       ├── postgres/
│       ├── oracle/
│       └── ...
├── config/                   # 配置类
├── controller/               # API 控制器
├── service/                  # 业务服务层
├── repository/               # 数据访问层
├── model/                    # 数据模型
│   ├── entity/              # JPA 实体
│   ├── dto/                 # 数据传输对象
│   └── vo/                  # 视图对象
└── constant/                 # 常量定义
```

---

## 八、总结

DataAgent 是一个设计良好的企业级 Agent 系统，采用 StateGraph 统一编排分析流程，通过清晰的分层和接口抽象实现高度可扩展性。核心价值在于将 Text-to-SQL、Python 深度分析、RAG 检索和人工反馈有机整合，形成完整的数据分析闭环。

### 架构亮点

1. **清晰的分层架构**：Controller → Service → Repository 经典分层
2. **可扩展的连接器设计**：新增数据库只需实现三个接口
3. **灵活的模型调度**：支持运行时动态切换多家厂商模型
4. **安全隔离的执行环境**：Docker 容器化执行用户生成的代码
5. **人性化设计**：Human-in-the-loop 机制让用户参与决策

### 建议关注点

1. **错误处理**：分布式场景下的错误恢复机制
2. **性能优化**：大规模并发下的连接池管理
3. **安全加固**：SQL 注入防护、代码沙箱隔离
4. **监控可观测性**：OpenTelemetry 集成已配置，建议完善埋点
