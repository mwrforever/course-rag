# Milvus 向量残留与 MinIO 孤儿对象：删除链路三处不一致

- **风险类别**：数据一致性/资源泄漏（检索命中已删除内容；存储对象永久残留）
- **严重度**：P1（违反 AGENTS.md"文档/课程删除需同步 Milvus delete"约定）
- **变更范围**：未提交工作区全部新增代码

## Bug 1：reparse 后 Milvus 旧向量永久残留（C2）

### 证据
- `DocumentService.java:223-251` `reparse` 仅软删 PG 旧 chunk（`set(deleted, ts)`），**不调用** `deleteFromMilvusByDocId`；
- `EtlPipeline.java:254` `embedAndIndex` 内调用 `deleteFromMilvusByDocId(docId)`，但其实现（`EtlPipeline.java:326-336`）：
  ```java
  List<DocumentChunk> chunks = chunkMapper.selectList(
          new LambdaQueryWrapper<DocumentChunk>()
                  .eq(DocumentChunk::getDocId, docId)
                  .select(DocumentChunk::getId));
  ```
  `DocumentChunk` 带 `@TableLogic(value="0", delval="1")`（`DocumentChunk.java:120`），selectList 自动过滤 `deleted = 0` —— 此时旧 chunk 已被软删、新 chunk 刚插入，**只遍历到新 chunk ID**，旧 ID 的 Milvus 行永不删除。

### 影响
对已 INDEXED 文档点"重新解析" → Milvus 中旧向量 + 新向量并存，检索（SearchKnowledgeTool）返回已废弃内容。

## Bug 2：课程删除未同步 Milvus delete（C3）

### 证据
`CourseService.java:196-241` `deleteCourse` 级联软删 course_content/course_schedule/course_teacher/course_enrollment/document_chunk 五张表，**全程无任何 Milvus 调用**（类内未注入 EtlPipeline）。

### 影响
删除课程后，Milvus 中 `course_id=该课程` 的向量仍在，学生端按 course_id 过滤检索仍命中已删课程内容（过期信息泄露）。

## Bug 3：MinIO 删除失败静默，DB 已删对象残留（C7）

### 证据
- `MinioStorageService.java:104-112`：
  ```java
  public void deleteFile(String objectKey) {
      try { minioClient.removeObject(...); }
      catch (Exception e) { log.warn("MinIO 删除失败（忽略）: ..."); }   // 吞异常
  }
  ```
- `DocumentService.java:182-212` `delete` 顺序：先软删 PG chunk/doc（:195-204）→ 最后 MinIO 删除（:208，异常被吞）。

### 影响
MinIO 短暂不可用/权限错误时删除文档 → DB 已软删、对象永久残留，无重试/补偿；reparse 也拿不回原文件。

## Bug 4：知识库删除不清理 MinIO 对象（C8）

### 证据
`KnowledgeBaseService.java:148-177` `delete` 级联只做 Milvus（:156）+ PG 三表软删（:162/:168/:174），类内**未注入 MinioStorageService**，KB 下所有文档原始文件对象（`{kbId}/{docId}.{ext}`）无任何删除路径。

### 影响
删除知识库 → MinIO 对象全部成为孤儿，永久占存储。

## Bug 5：上传失败残留孤儿 PENDING 记录（C13）

### 证据
`DocumentService.java:83-97`：先 `documentMapper.insert(doc)`（:92，status=PENDING）→ 再 MinIO 上传（:95）；`MinioStorageService.uploadFile`（:68-81）失败抛 `RuntimeException` → doc 行已入库（source_path=NULL）且无补偿；反之 `updateById` 失败则 MinIO 对象成孤儿。

### 影响
MinIO 故障/超时上传 → 文档列表出现无法解析的垃圾记录，重试产生多条。

## 建议修复方向

- reparse 前先按 docId 查**全部**（含已删）chunk ID 清理 Milvus，或 reparse 入口直接调 `deleteFromMilvusByDocId`（实现改为不依赖 MP 逻辑删除过滤的原始 SQL）；
- deleteCourse 补 `deleteFromMilvusByKbId`/按 course_id 过滤删除 Milvus；
- MinIO 删除失败改记录待补偿队列/重试；
- KnowledgeBaseService 注入 MinioStorageService 级联删除对象；
- upload 事务化（MinIO 成功后再提交 DB 记录，或失败回滚）。
