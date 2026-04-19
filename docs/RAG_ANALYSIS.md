# RAG 召回策略分析

## 1. 概述

DataAgent 项目采用多层次、多策略的 RAG（Retrieval-Augmented Generation）召回机制，支持向量检索、混合检索、元数据过滤等多种召回策略。

### 核心组件位置

```
data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/
├── service/
│   ├── vectorstore/                    # 向量存储服务
│   │   ├── AgentVectorStoreService.java
│   │   ├── AgentVectorStoreServiceImpl.java
│   │   └── DynamicFilterService.java
│   └── hybrid/                         # 混合检索
│       ├── retrieval/                  # 检索策略
│       │   ├── HybridRetrievalStrategy.java
│       │   ├── AbstractHybridRetrievalStrategy.java
│       │   └── impl/
│       │       ├── DefaultHybridRetrievalStrategy.java
│       │       └── ElasticsearchHybridRetrievalStrategy.java
│       ├── fusion/                     # 结果融合
│       │   ├── FusionStrategy.java
│       │   └── impl/
│       │       ├── RrfFusionStrategy.java
│       │       └── WeightedAverageStrategy.java
│       └── factory/
│           └── HybridRetrievalStrategyFactory.java
└── workflow/node/
    └── EvidenceRecallNode.java         # 证据召回节点
```

---

## 2. 召回策略类型

### 2.1 向量检索 (Vector Search)

基础召回策略，通过 embedding 模型计算语义相似度。

**实现类**：`AgentVectorStoreServiceImpl`

```java
List<Document> results = vectorStore.similaritySearch(searchRequest);
```

**特点**：
- 基于语义相似度匹配
- 支持自定义 topK 和相似度阈值
- 适用于 SimpleVectorStore、PgVector、Milvus 等

---

### 2.2 混合检索 (Hybrid Search)

同时执行向量检索和关键词检索，提升召回精准度。

#### 抽象基类

**实现类**：`AbstractHybridRetrievalStrategy`

```java
@Override
public List<Document> retrieve(HybridSearchRequest request) {
    // 异步并行执行向量搜索
    CompletableFuture<List<Document>> vectorSearchFuture = CompletableFuture.supplyAsync(() -> {
        return vectorStore.similaritySearch(vectorSearchRequest);
    }, executorService);

    // 异步并行执行关键词搜索
    CompletableFuture<List<Document>> keywordSearchFuture = CompletableFuture.supplyAsync(() -> {
        return getDocumentsByKeywords(request);
    }, executorService);

    // 融合结果
    return fusionStrategy.fuseResults(topK, vectorResults, keywordResults);
}
```

#### DefaultHybridRetrievalStrategy

适用于 **PgVector、Milvus** 等不支持关键词搜索的向量库。

```java
public class DefaultHybridRetrievalStrategy extends AbstractHybridRetrievalStrategy {
    @Override
    public List<Document> getDocumentsByKeywords(HybridSearchRequest request) {
        // 无关键词搜索能力，返回空
        return Collections.emptyList();
    }
}
```

#### ElasticsearchHybridRetrievalStrategy

适用于 **Elasticsearch**，支持完整的混合检索。

```java
@Override
public List<Document> getDocumentsByKeywords(HybridSearchRequest request) {
    // 使用 ES 的 Match Query 进行关键词搜索
    Query matchQuery = Query.of(q -> q.match(m -> m.field("content").query(queryText)));

    Query finalQuery = Query.of(q -> q.bool(b -> {
        b.must(matchQuery);
        if (StringUtils.hasText(filterString)) {
            b.filter(f -> f.queryString(qs -> qs.query(filterString)));
        }
        return b;
    }));

    SearchResponse<Document> response = client.search(searchRequest, Document.class);
    return response.hits().hits().stream().map(Hit::source).collect(Collectors.toList());
}
```

---

### 2.3 融合策略 (Fusion)

当启用混合检索时，向量搜索和关键词搜索的结果需要融合。

#### RrfFusionStrategy（默认）

**Reciprocal Rank Fusion (RRF)** 算法

```java
public class RrfFusionStrategy implements FusionStrategy {
    private int k = 60;  // RRF 参数

    @Override
    public List<Document> fuseResults(int topK, List<Document>... resultLists) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> documentMap = new HashMap<>();

        for (List<Document> resultList : resultLists) {
            for (int i = 0; i < resultList.size(); i++) {
                Document doc = resultList.get(i);
                int rank = i + 1;
                String docId = getDocumentId(doc);

                // RRF 公式：score = 1 / (k + rank)
                rrfScores.merge(docId, 1.0 / (k + rank), Double::sum);
                documentMap.putIfAbsent(docId, doc);
            }
        }

        // 按分数降序，取 topK
        return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(entry -> documentMap.get(entry.getKey()))
            .collect(Collectors.toList());
    }
}
```

**RRF 公式解释**：
- `k = 60`：默认融合参数
- `score = 1 / (k + rank)`
- 排名越靠前的文档，RRF 分数越高
- 多路检索结果累加分数，最终按分数排序

#### WeightedAverageStrategy

加权平均融合策略（**待实现**）

```java
public class WeightedAverageStrategy implements FusionStrategy {
    @Override
    public List<Document> fuseResults(int topK, List<Document>... resultLists) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
```

---

## 3. 元数据过滤 (Metadata Filtering)

### 3.1 文档类型

通过 `vectorType` 元数据字段区分不同类型文档：

| vectorType | 说明 | metadata 必需字段 |
|-----------|------|-----------------|
| `BUSINESS_TERM` | 业务术语 | agentId |
| `AGENT_KNOWLEDGE` | 智能体知识（FAQ/QA/DOCUMENT） | agentId |
| `TABLE` | 表结构 | agentId, datasourceId |
| `COLUMN` | 列结构 | agentId, datasourceId |

### 3.2 动态过滤构建

**实现类**：`DynamicFilterService`

```java
Filter.Expression filter = dynamicFilterService.buildDynamicFilter(agentId, docVectorType);
```

### 3.3 证据召回节点

**实现类**：`EvidenceRecallNode`

```java
// 获取业务知识文档
List<Document> businessTermDocuments = vectorStoreService
    .getDocumentsForAgent(agentId, query, DocumentMetadataConstant.BUSINESS_TERM);

// 获取智能体知识文档
List<Document> agentKnowledgeDocuments = vectorStoreService
    .getDocumentsForAgent(agentId, query, DocumentMetadataConstant.AGENT_KNOWLEDGE);
```

---

## 4. 查询重写增强 (Query Rewrite)

### 4.1 目的

在召回证据前，先通过 LLM 将用户问题重写为独立查询，避免依赖上下文。

### 4.2 实现

**EvidenceRecallNode.apply()**

```java
// 1. 构建查询重写提示
String prompt = PromptHelper.buildEvidenceQueryRewritePrompt(multiTurn, question);

// 2. 调用 LLM 重写查询
Flux<ChatResponse> responseFlux = llmService.callUser(prompt);

// 3. 提取独立查询
String standaloneQuery = extractStandaloneQuery(llmOutput);

// 4. 使用独立查询进行向量检索
DocumentRetrievalResult retrievalResult = retrieveDocuments(agentId, standaloneQuery);
```

**为什么不扩展为多个子查询**：
> 此时 LLM 不能理解不同公司的个性化业务知识（如 PV、KMV 等专业名词），扩展反而引入噪音。

---

## 5. 配置参数

### 5.1 application.yml

```yaml
spring:
  ai:
    vectorstore:
      type: simple                              # 向量库类型：simple, elasticsearch
      elasticsearch:
        index-name: spring-ai-document-index   # ES 索引名称
   .alibaba:
      data-agent:
        vector-store:
          enable-hybrid-search: true            # 是否启用混合搜索
          default-similarity-threshold: 0.7     # 默认相似度阈值
          default-topk-limit: 5                 # 默认召回数量
          elasticsearch-min-score: 0.5          # ES 最小分数
          batch-del-topk-limit: 100             # 批量删除上限
```

### 5.2 策略选择逻辑

**HybridRetrievalStrategyFactory**

```java
@Override
public HybridRetrievalStrategy getObject() {
    if (!dataAgentProperties.getVectorStore().isEnableHybridSearch()) {
        return null;  // 不启用混合搜索
    }
    if ("elasticsearch".equalsIgnoreCase(vectorStoreType)) {
        return new ElasticsearchHybridRetrievalStrategy(...);
    }
    return new DefaultHybridRetrievalStrategy(...);  // PgVector, Milvus 等
}
```

---

## 6. 召回流程图

```
用户问题
    │
    ▼
┌─────────────────────────┐
│  EvidenceRecallNode     │
│  ─────────────────────  │
│  1. LLM 查询重写        │
│  2. 提取 standaloneQuery│
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  AgentVectorStoreService│
│  ─────────────────────  │
│  buildDynamicFilter     │
│  HybridSearchRequest    │
└────────────┬────────────┘
             │
             ▼
    ┌────────┴────────┐
    │ 启用混合搜索?    │
    └────────┬────────┘
      YES    │    NO
      ▼      │      ▼
┌─────────┐  │   ┌─────────────────┐
│Hybrid   │  │   │Vector Search Only│
│Retrieval│  │   └────────┬────────┘
│Strategy │  │            │
└────┬────┘  │            │
     │       │            │
     ▼       │            ▼
┌────────┐   │     ┌──────────────┐
│Vector  │   │     │Filter +      │
│Search  │   │     │Similarity    │
└───┬────┘   │     └──────────────┘
    │       │
    ▼       │
┌─────────┐ │
│Keyword  │ │
│Search   │ │
└────┬────┘ │
     │      │
     ▼      ▼
┌─────────────────┐
│ RrfFusion       │
│ (RRF k=60)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 返回 topK 文档   │
└─────────────────┘
```

---

## 7. 关键代码索引

| 功能 | 文件路径 | 关键方法 |
|------|----------|----------|
| 向量检索入口 | `service/vectorstore/AgentVectorStoreServiceImpl.java` | `search()`, `getDocumentsForAgent()` |
| 混合检索基类 | `service/hybrid/retrieval/AbstractHybridRetrievalStrategy.java` | `retrieve()` |
| ES 混合检索 | `service/hybrid/retrieval/impl/ElasticsearchHybridRetrievalStrategy.java` | `getDocumentsByKeywords()` |
| 默认混合检索 | `service/hybrid/retrieval/impl/DefaultHybridRetrievalStrategy.java` | `getDocumentsByKeywords()` |
| RRF 融合 | `service/hybrid/fusion/impl/RrfFusionStrategy.java` | `fuseResults()` |
| 策略工厂 | `service/hybrid/factory/HybridRetrievalStrategyFactory.java` | `getObject()` |
| 证据召回节点 | `workflow/node/EvidenceRecallNode.java` | `apply()` |
| 动态过滤 | `service/vectorstore/DynamicFilterService.java` | `buildDynamicFilter()` |
