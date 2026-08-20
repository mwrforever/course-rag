package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.config.MilvusCollectionInitializer;
import com.commerce.rag.constants.EpisodicTypes;
import com.commerce.rag.entity.UserEpisodicMemory;
import com.commerce.rag.enums.EpisodicActionType;
import com.commerce.rag.mapper.UserEpisodicMemoryMapper;
import com.commerce.rag.properties.MemoryProperties;
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
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户经历记忆服务实现 —— 决策执行/落库 + Milvus 索引同步 + 召回（spec §8.5/§8.6/§8.7）
 *
 * <p>本 service 主表操作走内置链式（this.lambdaQuery/lambdaUpdate/save），按需取列；
 * 软删走 @TableLogic（removeById 置 deleted=1，审计保留物理行）。
 *
 * <p>Milvus 仅索引（spec §8.5）：applyExtraction 内 DB 写为事务原子，索引同步为 best-effort
 * （异常捕获记录日志，不回滚 DB）；召回链路 Milvus 定位 → PG 主键批量取数，Milvus 故障降级
 * 返回空召回（记忆缺失是检索体验降级，非数据破坏）。
 *
 * <p>测试注意（计划 4/5 实证）：this.lambdaQuery() 不可 Mockito 直测，SQL 段由集成测试覆盖；
 * 纯规则段（toExistingMemoriesText/toWriteRow/buildUpsert/buildUpsertById/syncIndexBestEffort）
 * 下沉 public 纯函数直测。
 *
 * <p>SDK 适配（与计划简报差异，均以 milvus-sdk-java 2.6.11 v2 实际签名核对为准）：
 * ① UpsertReq.setData 接收 {@code List<JsonObject>}（Gson 行式，同 EtlPipeline.buildMilvusRow），
 *    简报的 {@code List.of(List.of(...))} 编译不过，改按 Gson JsonObject 组装（见 buildMemoryUpsert）；
 * ② SearchReq 单向量检索 {@code data()} 接收查询向量 {@code BaseVector}（FloatVec），且字段
 *    {@code outputFields/searchParams/annsField/filter} 均直接挂在 SearchReq——AnnSearchReq 仅用于
 *    HybridSearchReq，简报的 {@code data(List.of(annReq))} 与 {@code outFields(...)} 均编译不过；
 * ③ 主键批量取数不用内置 listByIds/getById（全字段 SELECT 会在 TIMESTAMPTZ→LocalDateTime 整行映射时
 *    抛转换异常，真实 PG 集成实测），改链式 in / 按需取列（见 recall/buildUpsertById），召回排序过滤
 *    下沉 public buildRefs 纯函数直测。
 *
 * @author commerce-rag
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodicMemoryServiceImpl extends ServiceImpl<UserEpisodicMemoryMapper, UserEpisodicMemory>
        implements IEpisodicMemoryService {

    private final EpisodicDecisionEngine decisionEngine;
    private final MilvusClientV2 milvusClientV2;
    private final EmbeddingModel embeddingModel;
    private final MemoryProperties properties;

    @Override
    @Transactional
    public int applyExtraction(Long userId, Long sourceSessionId, EpisodicExtractionResult result) {
        if (userId == null || result == null) {
            return 0;
        }
        if (result.memories() == null || result.memories().isEmpty()) {
            return 0;
        }
        List<EpisodicAction> actions = new ArrayList<>();
        // 逐记忆决策：取该 (user_id, type) 既有 active 行——决策与执行同一事务内，避免脏读
        for (EpisodicMemoryExtraction memory : result.memories()) {
            List<UserEpisodicMemory> rows = this.lambdaQuery()
                    .select(
                            UserEpisodicMemory::getId,
                            UserEpisodicMemory::getType,
                            UserEpisodicMemory::getContent,
                            UserEpisodicMemory::getSummary,
                            UserEpisodicMemory::getValidity,
                            UserEpisodicMemory::getVersion)
                    .eq(UserEpisodicMemory::getUserId, userId)
                    .eq(UserEpisodicMemory::getType, memory.type())
                    .list();
            actions.add(decisionEngine.decide(memory, rows));
        }
        // 逐个执行动作（事务内，异常整体回滚；IGNORE 不计）
        int written = 0;
        for (EpisodicAction action : actions) {
            written += execute(userId, sourceSessionId, action);
        }
        if (written > 0) {
            log.info("经历记忆落库: userId={}, 生效动作={}, 条目={}条", userId, written, actions.size());
        }
        return written;
    }

    /**
     * 执行单个决策动作（PG 写 + Milvus 索引 best-effort 同步）
     *
     * @return 1=生效写操作 / 0=IGNORE 无操作
     */
    private int execute(Long userId, Long sourceSessionId, EpisodicAction action) {
        switch (action.type()) {
            case CREATE -> {
                // 新事实：写 active 新行（version=1），随后同步索引
                UserEpisodicMemory row = toWriteRow(userId, sourceSessionId, action, "active");
                save(row);
                syncIndexBestEffort(() -> buildUpsert(row, "active"));
            }
            case UPDATE, MERGE -> {
                // 旧行状态流转（spec §8.6：UPDATE→superseded，MERGE→merged）后新建 active 行 version+1
                String oldValidity = action.type() == EpisodicActionType.UPDATE ? "superseded" : "merged";
                this.lambdaUpdate()
                        .eq(UserEpisodicMemory::getId, action.targetRowId())
                        .set(UserEpisodicMemory::getValidity, oldValidity)
                        .set(UserEpisodicMemory::getUpdatedAt, LocalDateTime.now())
                        .update();
                // 新行 active，version = 旧行 + 1
                UserEpisodicMemory row = toWriteRow(userId, sourceSessionId, action, "active");
                row.setVersion(action.version());
                save(row);
                // 旧行索引置历史态 + 新行索引写入（各 best-effort，失败不影响 DB）
                syncIndexBestEffort(() -> buildUpsertById(action.targetRowId(), action.memoryType(), oldValidity));
                syncIndexBestEffort(() -> buildUpsert(row, "active"));
            }
            case INVALIDATE -> {
                // 用户明确否定：目标行 validity=invalidated（无新行）
                this.lambdaUpdate()
                        .eq(UserEpisodicMemory::getId, action.targetRowId())
                        .set(UserEpisodicMemory::getValidity, "invalidated")
                        .set(UserEpisodicMemory::getUpdatedAt, LocalDateTime.now())
                        .update();
                syncIndexBestEffort(() -> buildUpsertById(action.targetRowId(), action.memoryType(), "invalidated"));
            }
            case IGNORE -> {
                log.debug("经历记忆忽略: type={}, content={}", action.memoryType(), action.content());
                return 0;
            }
        }
        return 1;
    }

    // ========================================================================
    // 召回（spec §8.7）
    // ========================================================================

    @Override
    public List<EpisodicMemoryRef> recall(Long userId, String queryText, boolean recallHistory, int topK) {
        if (userId == null || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        int prefetch = properties.getEpisodic().getPrefetchTopK();
        try {
            float[] vector = embeddingModel.embed(queryText);
            if (vector == null || vector.length == 0) {
                log.warn("经历记忆召回: embedding 空向量，跳过: userId={}", userId);
                return List.of();
            }
            // 动态 validity 过滤（spec §8.7）：recall_history=false 默认只召 active；true 全量含历史
            String filter = MilvusCollectionInitializer.FIELD_MEMORY_USER_ID + " == \"" + userId + "\""
                    + (recallHistory
                            ? ""
                            : " and " + MilvusCollectionInitializer.FIELD_MEMORY_VALIDITY + " == \"active\"");
            // 单向量 dense 检索（v2 SearchReq.data 直接携带查询向量 FloatVec——AnnSearchReq 仅用于
            // HybridSearchReq，见 SearchKnowledgeTool；SQL 同 SDK 实际签名，简报差异见本模块类注释）
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(MilvusCollectionInitializer.COLLECTION_MEMORY)
                    .data(List.of(new FloatVec(vector)))
                    .annsField(MilvusCollectionInitializer.FIELD_MEMORY_EMBEDDING)
                    .metricType(IndexParam.MetricType.COSINE)
                    .searchParams(Map.of("ef", 64))
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
        List<UserEpisodicMemory> active = this.lambdaQuery()
                .select(UserEpisodicMemory::getType, UserEpisodicMemory::getContent)
                .eq(UserEpisodicMemory::getUserId, userId)
                .eq(UserEpisodicMemory::getValidity, "active")
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
     * 新增行的 Milvus 索引 upsert（embedding = summary+content 合并，spec §8.4）
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
        return buildMemoryUpsert(
                String.valueOf(row.getId()), String.valueOf(row.getUserId()), row.getType(), validity, vector);
    }

    /**
     * 旧行状态流转后的 Milvus 索引 upsert（按 id 反查 content/summary 组装 embedding）
     *
     * @return UpsertReq；目标行不存在或 embedding 空向量时返回 null（索引同步跳过）
     */
    public UpsertReq buildUpsertById(Long targetRowId, String memoryType, String validity) {
        // 按需取列反查旧行（不用内置 getById——全字段映射 created_at/updated_at 在 TIMESTAMPTZ→LocalDateTime 失败，
        // 会静默吞掉索引同步；只取组装 embedding 所需列）
        UserEpisodicMemory old = this.lambdaQuery()
                .select(
                        UserEpisodicMemory::getId,
                        UserEpisodicMemory::getUserId,
                        UserEpisodicMemory::getSummary,
                        UserEpisodicMemory::getContent)
                .eq(UserEpisodicMemory::getId, targetRowId)
                .one();
        if (old == null) {
            return null;
        }
        String text = (old.getSummary() == null ? "" : old.getSummary()) + "\n"
                + (old.getContent() == null ? "" : old.getContent());
        float[] vector = embeddingModel.embed(text);
        if (vector == null || vector.length == 0) {
            log.warn("经历记忆索引同步跳过（embedding 空向量）: memoryId={}", targetRowId);
            return null;
        }
        return buildMemoryUpsert(
                String.valueOf(old.getId()), String.valueOf(old.getUserId()), memoryType, validity, vector);
    }

    /**
     * 组装 memory_chunks 单行 UpsertReq（6 字段与 MilvusCollectionInitializer 常量严格一致，
     * spec §8.5：memory_id 主键 + user_id/type/validity 过滤键 + embedding 向量 + updated_at）
     *
     * <p>说明：milvus-sdk-java 2.6.11 v2 的 {@link UpsertReq#setData} 接收
     * {@code List<com.google.gson.JsonObject>}（Gson 行式，同 EtlPipeline 既有用法），
     * 标量字段 addProperty、向量字段 add(JsonArray)，与简报的 {@code List.of(List.of(...))} 不符，
     * 以 SDK 实际签名为准（见本模块实现）。
     */
    private UpsertReq buildMemoryUpsert(String memoryId, String userId, String type, String validity, float[] vector) {
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
        return UpsertReq.builder()
                .collectionName(MilvusCollectionInitializer.COLLECTION_MEMORY)
                .data(List.of(row))
                .build();
    }

    /** Milvus 索引同步 best-effort（异常仅记日志不回滚，spec §8.5）；public 供单测直测 */
    public void syncIndexBestEffort(Supplier<UpsertReq> supplier) {
        try {
            UpsertReq req = supplier.get();
            if (req != null) {
                milvusClientV2.upsert(req);
            }
        } catch (RuntimeException e) {
            // 索引同步失败仅降级（Milvus 为运行时异常），不影响 DB 事务写（spec §8.5 PG 为事实源）
            log.warn("Milvus memory_chunks 索引同步失败（忽略，不影响 DB 写）: {}", e.getMessage());
        }
    }

    /** double → BigDecimal（保留 3 位小数，与 NUMERIC(4,3) 一致） */
    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }
}
