package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AttachmentProperties 默认值测试（与 application.yml attachment 段一致） */
class AttachmentPropertiesTest {

    /** M-2 迁移（2026-08-29）后的默认 caption 模型（与 application.yml attachment.caption-model 一致） */
    private static final String CAPTION_MODEL = "qwen3.7-max-2026-06-08";

    @Test
    @DisplayName("默认限额 — 图片10MB/文档50MB/10个/合计100MB，缓存100条30分钟，批量向量化批大小16")
    void defaults() {
        AttachmentProperties p = new AttachmentProperties(
                10,
                50,
                10,
                100,
                100,
                30,
                16,
                60000,
                new AttachmentProperties.Executor(2, 4, 20, "attachment-"),
                CAPTION_MODEL);
        assertEquals(10, p.imageMaxSizeMb());
        assertEquals(50, p.documentMaxSizeMb());
        assertEquals(10, p.maxCount());
        assertEquals(100, p.totalMaxSizeMb());
        assertEquals(100, p.cacheMaxSize());
        assertEquals(30, p.cacheExpireMinutes());
        assertEquals(16, p.embeddingBatchSize());
        // M-2 迁移：captionModel 绑定 attachment.caption-model（ETL 离线与用户附件共用一值）
        assertEquals(CAPTION_MODEL, p.captionModel(), "captionModel 应绑定 attachment.caption-model 键值");
    }

    @Test
    @DisplayName("并行处理配置 — 总超时与线程池参数（core/max/队列/线程名前缀）绑定（P2-2）")
    void parallelExecutorBinding() {
        AttachmentProperties p = new AttachmentProperties(
                10,
                50,
                10,
                100,
                100,
                30,
                16,
                60000,
                new AttachmentProperties.Executor(2, 4, 20, "attachment-"),
                CAPTION_MODEL);
        assertEquals(60000, p.processTimeoutMs(), "附件并行处理总超时应绑定 process-timeout-ms");
        assertEquals(2, p.executor().coreSize());
        assertEquals(4, p.executor().maxSize());
        assertEquals(20, p.executor().queueCapacity());
        assertEquals("attachment-", p.executor().threadNamePrefix());
    }

    @Test
    @DisplayName("M-2 启动校验 — captionModel 缺失（null/空白）时 jakarta 校验违规（配置缺失启动失败兜底）")
    void captionModelMissing_validationFails() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        AttachmentProperties missing = new AttachmentProperties(
                10, 50, 10, 100, 100, 30, 16, 60000, new AttachmentProperties.Executor(2, 4, 20, "attachment-"), null);
        AttachmentProperties blank = new AttachmentProperties(
                10, 50, 10, 100, 100, 30, 16, 60000, new AttachmentProperties.Executor(2, 4, 20, "attachment-"), "  ");

        assertTrue(
                validator.validate(missing).stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals("captionModel")),
                "captionModel 缺失应产生校验违规（启动期绑定校验失败，配置缺失启动失败）");
        assertTrue(
                validator.validate(blank).stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals("captionModel")),
                "captionModel 空白应产生校验违规");
    }

    @Test
    @DisplayName("M-2 迁移收口 — EtlProperties 不再暴露 captionModel（键已迁至 attachment 命名空间）")
    void etlProperties_noCaptionModel() throws Exception {
        assertThrows(
                NoSuchMethodException.class,
                () -> EtlProperties.class.getMethod("captionModel"),
                "EtlProperties 不应再持有 captionModel（唯一消费点已迁移至 AttachmentProperties）");
    }
}
