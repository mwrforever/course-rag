-- P1-1（2026-08-23）：课程分片查询复合部分索引——覆盖 J2/J3 的过滤与排序。
-- 背景：V6 的 idx_document_chunk_course 谓词为 course_id != 'DEFAULT' AND deleted = 0，
-- 通用资料库行（course_id='DEFAULT'）被排除在索引外，J3 分页（eq course_id='DEFAULT'
-- + orderBy chunk_index，count + page 两条 SQL）只能全表扫 + 排序。
-- 本索引不排除 DEFAULT：复合 (course_id, chunk_index) 同时覆盖 J3 的 DEFAULT 过滤、
-- J2 的具体课程过滤与两者的 chunk_index 排序；谓词 deleted=0 与 @TableLogic
-- 自动追加的软删过滤一致（软删行不入索引）。旧索引保留（course_id 单列仍服务其它等值查询）。
CREATE INDEX idx_document_chunk_course_default ON document_chunk(course_id, chunk_index) WHERE deleted = 0;
