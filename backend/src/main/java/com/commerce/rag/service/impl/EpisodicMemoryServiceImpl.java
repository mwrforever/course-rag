package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.config.MilvusCollectionInitializer;
import com.commerce.rag.constants.EpisodicTypes;
import com.commerce.rag.entity.UserEpisodicMemory;
import com.commerce.rag.enums.EpisodicActionType;
import com.commerce.rag.mapper.UserEpisodicMemoryMapper;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.properties.MilvusProperties;
import com.commerce.rag.record.EpisodicAction;
import com.commerce.rag.record.EpisodicExtractionResult;
import com.commerce.rag.record.EpisodicMemoryExtraction;
import com.commerce.rag.record.EpisodicMemoryRef;
import com.commerce.rag.service.EpisodicDecisionEngine;
import com.commerce.rag.service.IEpisodicMemoryService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用户经历记忆服务实现 —— 决策执行/落库 + Milvus 索引同步 + 召回（spec §8.5/§8.6/§8.7）
 *
 * <p>本 service 主表操作走内置链式（this.lambdaQuery/lambdaUpdate/save），按需取列；
 * 软删走 @TableLogic（removeById 置 deleted=1，审计保留物理行）。
 *
 * <p>批处理语义（spec §8.5 单批原子）：applyExtraction 先按 distinct type 一次批量取既有行
 * （防 N+1），再逐条「决策 → 执行」——执行后同步批内内存视图，使同批后序条目可见前序写入
 * （同批去重/merge_target 定位/version 演算全部基于最新视图，防批内脏读）。
 *
 * <p>Milvus 仅索引（spec §8.5）：事务内只做 DB 写并登记索引同步目标（旧行流转直接复用批内
 * 视图行，无提交后反查），事务提交后（TransactionSynchronization afterCommit）再 best-effort
 * 批量同步（逐行 embed + 单次 upsert 合并）——远程 embedding/Milvus 调用不持有 DB 连接；
 * 索引同步失败仅记日志不回滚 DB（PG 为事实源，召回 Milvus 定位 → PG 取数，
 * Milvus 故障降级返回空召回，是检索体验降级非数据破坏）。
 *
 * <p>测试注意（计划 4/5 实证）：this.lambdaQuery() 不可 Mockito 直测，SQL 段由集成测试覆盖；
 * 纯规则段（toExistingMemoriesText/toWriteRow/buildUpsert/syncIndexBatchBestEffort）
 * 下沉 public 纯函数直测。
 *
 * <p>SDK 适配（与计划简报差异，均以 milvus-sdk-java 2.6.11 v2 实际签名核对为准）：
 * ① UpsertReq.setData 接收 {@code List<JsonObject>}（Gson 行式，同 EtlPipeline.buildMilvusRow），
 *    简报的 {@code List.of(List.of(...))} 编译不过，改按 Gson JsonObject 组装（见 buildMemoryUpsert）；
 * ② SearchReq 单向量检索 {@code data()} 接收查询向量 {@code BaseVector}（FloatVec），且字段
 *    {@code outputFields/searchParams/annsField/filter} 均直接挂在 SearchReq——AnnSearchReq 仅用于
 *    HybridSearchReq，简报的 {@code data(List.of(annReq))} 与 {@code outFields(...)} 均编译不过；
 * ③ 主键批量取数不用内置 listByIds/getById（全字段 SELECT 会在 TIMESTAMPTZ→LocalDateTime 整行映射时
 *    抛转换异常，真实 PG 集成实测），改链式 in / 按需取列（见 recall），召回排序过滤
 *    下沉 public buildRefs 纯函数直测。
 *
 * @author commerce-rag
 */
@Slf4j
@Service
public class EpisodicMemoryServiceImpl extends ServiceImpl<UserEpisodicMemoryMapper, UserEpisodicMemory>
        implements IEpisodicMemoryService {

    private final EpisodicDecisionEngine decisionEngine;
    private final MilvusClientV2 milvusClientV2;
    private final EmbeddingModel embeddingModel;
    private final MemoryProperties properties;
    /** HNSW 检索 ef 参数（配置键 milvus.hnsw-ef，与 MilvusCollectionInitializer 索引参数同源配置化） */
    private final int hnswEf;

    /**
     * 手写构造器（非 @RequiredArgsConstructor）：hnswEf 属 Milvus 阈值配置，经
     * {@link MilvusProperties} 强类型注入（BUG-12 @Value 收敛，宪法 A.2.2），故不交给 Lombok 生成。
     *
     * @param decisionEngine 经历记忆决策引擎（纯规则）
     * @param milvusClientV2 Milvus v2 客户端（索引同步/召回定位）
     * @param embeddingModel 向量模型（索引 embedding + 召回查询向量）
     * @param properties     记忆体系配置（episodic 段：阈值/权重/召回）
     * @param milvusProperties Milvus 配置（hnsw-ef 检索 ef 参数，默认 64）
     */
    public EpisodicMemoryServiceImpl(
            EpisodicDecisionEngine decisionEngine,
            MilvusClientV2 milvusClientV2,
            EmbeddingModel embeddingModel,
            MemoryProperties properties,
            MilvusProperties milvusProperties) {
        this.decisionEngine = decisionEngine;
        this.milvusClientV2 = milvusClientV2;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.hnswEf = milvusProperties.hnswEf();
    }

    @Override
    @Transactional
    public int applyExtraction(Long userId, Long sourceSessionId, EpisodicExtractionResult result) {
        if (userId == null || result == null) {
            return 0;
        }
        if (result.memories() == null || result.memories().isEmpty()) {
            return 0;
        }
        // 批量取行：本批涉及的全部 type 一次 in 查询（防 N+1），按 type 内存分组——批前快照
        Set<String> types =
                result.memories().stream().map(EpisodicMemoryExtraction::type).collect(Collectors.toSet());
        List<UserEpisodicMemory> allRows = this.lambdaQuery()
                .select(
                        UserEpisodicMemory::getId,
                        UserEpisodicMemory::getUserId,
                        UserEpisodicMemory::getType,
                        UserEpisodicMemory::getContent,
                        UserEpisodicMemory::getSummary,
                        UserEpisodicMemory::getValidity,
                        UserEpisodicMemory::getVersion)
                .eq(UserEpisodicMemory::getUserId, userId)
                .in(UserEpisodicMemory::getType, types)
                .list();
        Map<String, List<UserEpisodicMemory>> rowsByType = allRows.stream()
                .collect(Collectors.groupingBy(UserEpisodicMemory::getType, Collectors.toCollection(ArrayList::new)));

        // 索引同步目标登记表：事务内只收集（含远程 embedding 的构建与写入延后到提交后执行，
        // 不持有 DB 连接；旧行流转直接复用批内视图行，无需提交后反查 PG）——BUG-06 修复后
        // 再合并为单次批量 upsert（报告 2-2 方案 2），远程调用次数 O(动作数) → O(1)
        List<IndexSyncTarget> indexSyncTargets = new ArrayList<>();
        // 逐条「决策 → 执行」：决策基于批前快照 + 批内已执行写入的内存视图（执行后同步视图，
        // 同批去重/merge_target 定位/version 演算全部可见前序写入，防批内脏读）
        int written = 0;
        for (EpisodicMemoryExtraction memory : result.memories()) {
            List<UserEpisodicMemory> rows = rowsByType.computeIfAbsent(memory.type(), k -> new ArrayList<>());
            EpisodicAction action = decisionEngine.decide(memory, rows);
            written += execute(userId, sourceSessionId, action, rows, indexSyncTargets);
        }
        // 事务提交后统一执行索引同步（PG 写已可见；回滚则不执行，索引与事实源保持一致）
        runIndexSyncAfterCommit(indexSyncTargets);
        if (written > 0) {
            log.info(
                    "经历记忆落库: userId={}, 生效动作={}, 条目={}条",
                    userId,
                    written,
                    result.memories().size());
        }
        return written;
    }

    /**
     * 执行单个决策动作（PG 写 + 批内视图同步 + 登记索引同步目标）
     *
     * @param view              该 type 的批内内存视图（执行后同步，使同批后序决策可见前序写入；仅在
     *                          applyExtraction 批处理链路上传入）
     * @param indexSyncTargets  索引同步目标登记表（事务提交后批量同步，见 {@link #runIndexSyncAfterCommit}）
     * @return 1=生效写操作 / 0=IGNORE 无操作
     */
    private int execute(
            Long userId,
            Long sourceSessionId,
            EpisodicAction action,
            List<UserEpisodicMemory> view,
            List<IndexSyncTarget> indexSyncTargets) {
        switch (action.type()) {
            case CREATE -> {
                // 新事实：写 active 新行（version=1），随后登记索引同步
                UserEpisodicMemory row = toWriteRow(userId, sourceSessionId, action, "active");
                save(row);
                view.add(row);
                indexSyncTargets.add(new IndexSyncTarget(row, "active"));
            }
            case UPDATE, MERGE -> {
                // 旧行状态流转（spec §8.6：UPDATE→superseded，MERGE→merged）后新建 active 行 version+1
                String oldValidity = action.type() == EpisodicActionType.UPDATE ? "superseded" : "merged";
                // 旧行直接复用批内视图行（决策阶段已取回，含组装 embedding 所需的 summary/content/userId），
                // 登记索引同步目标时不需再按 id 反查 PG（报告 2-2 方案 3：消除每动作一次反查）
                UserEpisodicMemory oldRow = findTargetRow(view, action);
                this.lambdaUpdate()
                        .eq(UserEpisodicMemory::getId, action.targetRowId())
                        .set(UserEpisodicMemory::getValidity, oldValidity)
                        .set(UserEpisodicMemory::getUpdatedAt, LocalDateTime.now())
                        .update();
                // 新行 active，version = 旧行 + 1
                UserEpisodicMemory row = toWriteRow(userId, sourceSessionId, action, "active");
                row.setVersion(action.version());
                save(row);
                // 视图同步：旧行退出 active 视图、新行加入（后序决策基于最新状态）
                view.removeIf(r -> r.getId().equals(action.targetRowId()));
                view.add(row);
                // 旧行索引置历史态 + 新行索引写入（各 best-effort，失败不影响 DB）
                if (oldRow != null) {
                    indexSyncTargets.add(new IndexSyncTarget(oldRow, oldValidity));
                }
                indexSyncTargets.add(new IndexSyncTarget(row, "active"));
            }
            case INVALIDATE -> {
                // 用户明确否定：目标行 validity=invalidated（无新行）
                UserEpisodicMemory oldRow = findTargetRow(view, action);
                this.lambdaUpdate()
                        .eq(UserEpisodicMemory::getId, action.targetRowId())
                        .set(UserEpisodicMemory::getValidity, "invalidated")
                        .set(UserEpisodicMemory::getUpdatedAt, LocalDateTime.now())
                        .update();
                view.removeIf(r -> r.getId().equals(action.targetRowId()));
                if (oldRow != null) {
                    indexSyncTargets.add(new IndexSyncTarget(oldRow, "invalidated"));
                }
            }
            case IGNORE -> {
                log.debug("经历记忆忽略: type={}, content={}", action.memoryType(), action.content());
                return 0;
            }
        }
        return 1;
    }

    /**
     * 在批内视图按 targetRowId 定位旧行（决策阶段已取回该行，供索引同步复用）
     *
     * <p>targetRowId 由决策引擎从 view 行中选出，正常必在视图内；防御性为空时仅跳过旧行索引同步
     * （best-effort 语义），不阻断 PG 写。
     *
     * @param view   该 type 的批内内存视图
     * @param action 决策动作（targetRowId 定位目标行）
     * @return 旧行对象；不在视图内返回 null
     */
    private UserEpisodicMemory findTargetRow(List<UserEpisodicMemory> view, EpisodicAction action) {
        if (action.targetRowId() == null) {
            return null;
        }
        for (UserEpisodicMemory r : view) {
            if (r.getId().equals(action.targetRowId())) {
                return r;
            }
        }
        log.warn(
                "经历记忆索引同步跳过旧行（targetRowId 不在批内视图）: type={}, targetRowId={}", action.memoryType(), action.targetRowId());
        return null;
    }

    /**
     * 事务提交后执行索引同步（BUG-06 修复：远程 embedding/Milvus 调用不持有 DB 连接；
     * 报告 2-2 方案 2：全批合并为单次 upsert）。
     *
     * <p>事务活跃（applyExtraction 经 Spring 代理）时注册 afterCommit 回调，提交后执行；事务回滚
     * 则不执行（索引与事实源保持一致）；非事务环境（单测直调等）直接执行，保持与事务语义等价。
     *
     * @param targets 登记的索引同步目标（空则无操作）
     */
    private void runIndexSyncAfterCommit(List<IndexSyncTarget> targets) {
        if (targets.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    syncIndexBatchBestEffort(targets);
                }
            });
        } else {
            // 非事务环境：直接同步（与事务语义等价，方法内逐行/整批 best-effort 防异常外泄）
            syncIndexBatchBestEffort(targets);
        }
    }

    /**
     * 待同步索引的（记忆行, 目标有效态）对 —— 事务内登记、提交后统一批量同步；
     * public 嵌套 record：{@link #syncIndexBatchBestEffort} 为 public 直测入口，参数类型须可访问
     *
     * @param row      记忆行（新增行或批内视图旧行，含组装 embedding 所需的全部字段）
     * @param validity 该行在 Milvus 索引中的目标 validity（active/历史态）
     */
    public record IndexSyncTarget(UserEpisodicMemory row, String validity) {}

    // ========================================================================
    // 召回（spec §8.7）
    // ========================================================================

    @Override
    public List<EpisodicMemoryRef> recall(Long userId, float[] queryVector, boolean recallHistory, int topK) {
        if (userId == null || queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        int prefetch = properties.getEpisodic().getPrefetchTopK();
        try {
            // 查询向量由 RetrieveNode 预嵌入传入（方案 3-1-a：与知识检索首条共用一次远程调用），此处不再 embed
            // 动态 validity 过滤（spec §8.7）：recall_history=false 默认只召 active；true 全量含历史
            String filter = MilvusCollectionInitializer.FIELD_MEMORY_USER_ID + " == \"" + userId + "\""
                    + (recallHistory
                            ? ""
                            : " and " + MilvusCollectionInitializer.FIELD_MEMORY_VALIDITY + " == \"active\"");
            // 单向量 dense 检索（v2 SearchReq.data 直接携带查询向量 FloatVec——AnnSearchReq 仅用于
            // HybridSearchReq，见 SearchKnowledgeTool；SQL 同 SDK 实际签名，简报差异见本模块类注释）
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(MilvusCollectionInitializer.COLLECTION_MEMORY)
                    .data(List.of(new FloatVec(queryVector)))
                    .annsField(MilvusCollectionInitializer.FIELD_MEMORY_EMBEDDING)
                    .metricType(IndexParam.MetricType.COSINE)
                    .searchParams(Map.of("ef", hnswEf))
                    .limit(prefetch)
                    .outputFields(List.of(MilvusCollectionInitializer.FIELD_MEMORY_ID))
                    .filter(filter)
                    .build();
            SearchResp resp = milvusClientV2.search(searchReq);
            if (resp == null
                    || resp.getSearchResults() == null
                    || resp.getSearchResults().isEmpty()) {
                return List.of();
            }
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            if (results == null || results.isEmpty()) {
                return List.of();
            }
            // Milvus 定位 → PG 主键批量取数（spec §8.5）；score 按 memory_id 暂存供排序/过滤
            List<Long> ids = new ArrayList<>();
            Map<Long, Double> scoreById = new HashMap<>();
            for (SearchResp.SearchResult sr : results) {
                Object idObj =
                        sr.getEntity() == null ? null : sr.getEntity().get(MilvusCollectionInitializer.FIELD_MEMORY_ID);
                if (idObj == null) {
                    continue;
                }
                try {
                    Long id = Long.parseLong(String.valueOf(idObj));
                    ids.add(id);
                    scoreById.put(id, sr.getScore() == null ? 0.0 : sr.getScore());
                } catch (NumberFormatException e) {
                    // 恶意/脏 memory_id 不阻断召回，仅丢弃该条
                    log.warn("经历记忆召回: memory_id 非数字跳过: {}", idObj);
                }
            }
            if (ids.isEmpty()) {
                return List.of();
            }
            // PG 主键批量取数（按需取列，spec 按需取列铁律 + 实体 TIMESTAMPTZ→LocalDateTime 整行映射失败实测）：
            // 不用内置 listByIds（全字段 SELECT 会映射 created_at/updated_at 抛异常），改链式 in 查询只取召回所需列；
            // 追加 user_id 等值过滤（spec §10-6 硬隔离字面满足 + 纵深防御，防索引陈旧交叉取到他人记忆）
            List<UserEpisodicMemory> rows = this.lambdaQuery()
                    .select(
                            UserEpisodicMemory::getId,
                            UserEpisodicMemory::getType,
                            UserEpisodicMemory::getContent,
                            UserEpisodicMemory::getSummary,
                            UserEpisodicMemory::getValidity)
                    .eq(UserEpisodicMemory::getUserId, userId)
                    .in(UserEpisodicMemory::getId, ids)
                    .list();
            return buildRefs(
                    rows,
                    scoreById,
                    recallHistory,
                    topK,
                    properties.getEpisodic().getRecallMinScore());
        } catch (RuntimeException e) {
            // Milvus 故障/embedding 异常：降级返回空召回（spec §8.5 PG 为事实源，索引陈旧仅漏召回）。
            // 收窄 RuntimeException 规避 SpotBugs REC_CATCH_EXCEPTION（Milvus/embedding 均为运行时异常）
            log.warn("经历记忆召回失败，降级空: userId={}, error={}", userId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public String findActiveMemoriesText(Long userId) {
        // 有界截断：{existing} 仅作 merge_target 原文引用参考，取最近更新的前 N 行即可
        // （记忆量增长后 prompt 无界膨胀会导致提取超时/降级，spec §8.4；非分页 .list() 不受
        // PaginationInnerInterceptor maxLimit 保护，故显式 LIMIT）
        List<UserEpisodicMemory> active = this.lambdaQuery()
                .select(UserEpisodicMemory::getType, UserEpisodicMemory::getContent)
                .eq(UserEpisodicMemory::getUserId, userId)
                .eq(UserEpisodicMemory::getValidity, "active")
                .orderByDesc(UserEpisodicMemory::getUpdatedAt)
                .last("LIMIT " + Math.max(1, properties.getEpisodic().getExistingTextLimit()))
                .list();
        return toExistingMemoriesText(active);
    }

    // ========================================================================
    // 纯函数（public 供单测直测；SQL 段不可 Mockito，见类注释）
    // ========================================================================

    /** active 记忆行 → 「标签:内容」逐行文本（提取 prompt merge_target 引用输入，spec §8.4） */
    public String toExistingMemoriesText(List<UserEpisodicMemory> active) {
        if (active == null || active.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (UserEpisodicMemory row : active) {
            String label = EpisodicTypes.LABELS.getOrDefault(row.getType(), row.getType());
            sb.append(label).append(": ").append(row.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 召回引用组装纯函数（spec §8.7：分数阈值过滤 + recallHistory=false 时 PG 侧 active 兜底过滤
     * （防索引一致滞后漏进历史）+ 按分降序 + topK 截断）——下沉 public 供单测直测（SQL 段在集成覆盖）
     *
     * @param rows          PG 主键批量取到的行（按需取列）
     * @param scoreById     memory_id → Milvus COSINE 召回分
     * @param recallHistory 是否含历史（true=放行 superseded/merged 等历史态行）
     * @param topK          返回条数上限
     * @param minScore      召回最低分（低于阈值不注入，spec §8.8）
     * @return 按召回分降序的引用列表（空输入返回空列表）
     */
    public List<EpisodicMemoryRef> buildRefs(
            List<UserEpisodicMemory> rows,
            Map<Long, Double> scoreById,
            boolean recallHistory,
            int topK,
            double minScore) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(r -> !recallHistory ? "active".equals(r.getValidity()) : true)
                .filter(r -> scoreById.getOrDefault(r.getId(), 0.0) >= minScore)
                .sorted((a, b) ->
                        Double.compare(scoreById.getOrDefault(b.getId(), 0.0), scoreById.getOrDefault(a.getId(), 0.0)))
                .limit(Math.max(1, topK))
                .map(r -> new EpisodicMemoryRef(
                        r.getId(),
                        r.getType(),
                        r.getContent(),
                        r.getSummary(),
                        r.getValidity(),
                        scoreById.getOrDefault(r.getId(), 0.0)))
                .toList();
    }

    /** 由动作构造待写入的记忆行（CREATE/UPDATE/MERGE 新行用） */
    public UserEpisodicMemory toWriteRow(Long userId, Long sourceSessionId, EpisodicAction action, String validity) {
        UserEpisodicMemory row = new UserEpisodicMemory();
        row.setUserId(userId);
        row.setType(action.memoryType());
        row.setContent(action.content());
        row.setSummary(action.summary());
        row.setStructuredFacts(action.structuredFacts());
        row.setImportance(bd(action.importance()));
        row.setConfidence(bd(action.confidence()));
        row.setValidity(validity);
        row.setVersion(action.version());
        row.setSourceSessionId(sourceSessionId);
        return row;
    }

    /**
     * 生成单行 Milvus 索引 upsert 请求（embedding = summary+content 合并，spec §8.4；
     * 供 {@link #syncIndexBatchBestEffort} 逐行调用后并入批量列表，public 供单测直测）
     *
     * @return UpsertReq；embedding 空向量时返回 null（索引同步跳过，避免写入无效空向量）
     */
    public UpsertReq buildUpsert(UserEpisodicMemory row, String validity) {
        String text = (row.getSummary() == null ? "" : row.getSummary()) + "\n"
                + (row.getContent() == null ? "" : row.getContent());
        float[] vector = embeddingModel.embed(text);
        if (vector == null || vector.length == 0) {
            log.warn("经历记忆索引同步跳过（embedding 空向量）: memoryId={}", row.getId());
            return null;
        }
        JsonObject jsonRow = buildMemoryUpsertRow(
                String.valueOf(row.getId()), String.valueOf(row.getUserId()), row.getType(), validity, vector);
        return UpsertReq.builder()
                .collectionName(MilvusCollectionInitializer.COLLECTION_MEMORY)
                .data(List.of(jsonRow))
                .build();
    }

    /**
     * 批量同步 memory_chunks 索引（spec §8.5 best-effort：失败仅记日志不回滚 DB）。
     *
     * <p>实现（报告 2-2 方案 2）：逐行 buildUpsert（embed 远程调用不可避免），单行 embedding
     * 失败/空向量仅跳过该行不拖垮整批；全部成功行合并为<b>单次</b> upsert 请求
     * （UpsertReq.data 支持多行，gRPC 往返次数 O(动作数) → O(1)）。public 供单测直测。
     *
     * @param targets 待同步目标列表（空/null 则无操作）
     */
    public void syncIndexBatchBestEffort(List<IndexSyncTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        List<JsonObject> rows = new ArrayList<>();
        for (IndexSyncTarget target : targets) {
            try {
                // 每行生成单行 upsert 请求（含 embed，空向量返回 null 跳过），行数据并入批量列表
                UpsertReq req = buildUpsert(target.row(), target.validity());
                if (req != null && req.getData() != null) {
                    rows.addAll(req.getData());
                }
            } catch (RuntimeException e) {
                // 单行 embedding 异常仅跳过该行（best-effort 语义，不拖垮整批）
                log.warn(
                        "经历记忆索引同步跳过单行（embedding 异常）: memoryId={}, error={}",
                        target.row().getId(),
                        e.getMessage());
            }
        }
        if (rows.isEmpty()) {
            log.debug("经历记忆索引同步跳过：本批全部行无有效向量，不发起 upsert");
            return;
        }
        try {
            milvusClientV2.upsert(UpsertReq.builder()
                    .collectionName(MilvusCollectionInitializer.COLLECTION_MEMORY)
                    .data(rows)
                    .build());
        } catch (RuntimeException e) {
            // 索引同步失败仅降级（Milvus 为运行时异常），不影响 DB 事务写（spec §8.5 PG 为事实源）
            log.warn("Milvus memory_chunks 批量索引同步失败（忽略，不影响 DB 写）: {}", e.getMessage());
        }
    }

    /**
     * 组装 memory_chunks 单行 Gson JsonObject（6 字段与 MilvusCollectionInitializer 常量严格一致，
     * spec §8.5：memory_id 主键 + user_id/type/validity 过滤键 + embedding 向量 + updated_at）
     *
     * <p>说明：milvus-sdk-java 2.6.11 v2 的 {@link UpsertReq#setData} 接收
     * {@code List<com.google.gson.JsonObject>}（Gson 行式，同 EtlPipeline 既有用法），
     * 标量字段 addProperty、向量字段 add(JsonArray)，与简报的 {@code List.of(List.of(...))} 不符，
     * 以 SDK 实际签名为准（见本模块实现）。
     *
     * @return 单行行式 JsonObject（供单行 upsert 或批量合并 {@code data} 列表复用）
     */
    private JsonObject buildMemoryUpsertRow(
            String memoryId, String userId, String type, String validity, float[] vector) {
        JsonObject row = new JsonObject();
        row.addProperty(MilvusCollectionInitializer.FIELD_MEMORY_ID, memoryId);
        row.addProperty(MilvusCollectionInitializer.FIELD_MEMORY_USER_ID, userId);
        row.addProperty(MilvusCollectionInitializer.FIELD_MEMORY_TYPE, type);
        row.addProperty(MilvusCollectionInitializer.FIELD_MEMORY_VALIDITY, validity);
        // embedding 向量：float[] → Gson JsonArray（标量/向量行式结构，同 EtlPipeline.buildMilvusRow）
        JsonArray vecArray = new JsonArray();
        for (float f : vector) {
            vecArray.add(f);
        }
        row.add(MilvusCollectionInitializer.FIELD_MEMORY_EMBEDDING, vecArray);
        row.addProperty(
                MilvusCollectionInitializer.FIELD_MEMORY_UPDATED_AT,
                Instant.now().getEpochSecond());
        return row;
    }

    /** double → BigDecimal（保留 3 位小数，与 NUMERIC(4,3) 一致） */
    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }
}
