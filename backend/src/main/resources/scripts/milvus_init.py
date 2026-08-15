#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Milvus Collection `knowledge_chunks` 初始化脚本（pymilvus v2 API）

功能：
  1. 检查 Collection 是否存在（幂等）
  2. 不存在时创建 Collection Schema（12 个字段 + BM25 Function）
  3. 创建 4 个索引（dense/sparse/collection_type/course_id）
  4. 加载 Collection 到内存

用法：
  python milvus_init.py                              # 默认 localhost:19530
  python milvus_init.py --host 192.168.1.100         # 指定 Milvus 地址
  python milvus_init.py --port 19530                 # 指定端口
  python milvus_init.py --drop                       # 先删除再创建

依赖：
  pip install pymilvus>=2.5.0

Collection Schema（必须与 Java 端 SearchKnowledgeTool / EtlPipeline 精确匹配）：
  | 字段名          | 类型                 | 约束                          |
  | chunk_id        | VARCHAR(64)          | Primary Key, autoID=false    |
  | doc_id          | VARCHAR(64)          | 文档 ID                       |
  | kb_id           | VARCHAR(64)          | 知识库 ID                     |
  | content         | VARCHAR(65535)       | enable_analyzer=True ← BM25  |
  | heading_path    | VARCHAR(500)         | 标题导航路径                  |
  | dense_vector    | FLOAT_VECTOR(1024)   | HNSW + COSINE 索引            |
  | sparse_vector   | SPARSE_FLOAT_VECTOR  | BM25 Function 自动生成         |
  | chunk_index     | INT32                | 分片序号                      |
  | token_count     | INT32                | token 数量                    |
  | collection_type | VARCHAR(20)          | INVERTED 标量索引             |
  | course_id       | VARCHAR(64)          | INVERTED 标量索引             |
  | updated_at      | INT64                | Unix epoch 秒                 |

@author commerce-rag
"""

import argparse
import sys

from pymilvus import (
    MilvusClient,
    DataType,
    Function,
    FunctionType,
)


# ── Collection 配置常量（与 Java 端 application.yml 保持一致）──
COLLECTION_NAME = "knowledge_chunks"
EMBEDDING_DIM = 1024

# HNSW 索引参数
HNSW_M = 16
HNSW_EF_CONSTRUCTION = 200

# 字段长度
MAX_LEN_CHUNK_ID = 64
MAX_LEN_DOC_ID = 64
MAX_LEN_KB_ID = 64
MAX_LEN_CONTENT = 65535
MAX_LEN_HEADING_PATH = 500
MAX_LEN_COLLECTION_TYPE = 20
MAX_LEN_COURSE_ID = 64


def parse_args():
    """解析命令行参数"""
    parser = argparse.ArgumentParser(
        description="Milvus Collection knowledge_chunks 初始化脚本（v2 API）"
    )
    parser.add_argument(
        "--host",
        default="localhost",
        help="Milvus 服务地址（默认 localhost）",
    )
    parser.add_argument(
        "--port",
        default="19530",
        help="Milvus 服务端口（默认 19530）",
    )
    parser.add_argument(
        "--drop",
        action="store_true",
        help="先删除已存在的 Collection 再创建",
    )
    return parser.parse_args()


def connect_milvus(host, port):
    """连接 Milvus 服务（pymilvus v2 API：MilvusClient）"""
    uri = f"http://{host}:{port}"
    print(f"[1/4] 连接 Milvus: uri={uri}")
    try:
        client = MilvusClient(uri=uri)
        print(f"  ✓ Milvus 连接成功")
        return client
    except Exception as e:
        print(f"  ✗ Milvus 连接失败: {e}")
        sys.exit(1)


def drop_if_requested(client, drop):
    """根据 --drop 参数删除已存在的 Collection"""
    if not drop:
        return
    if client.has_collection(COLLECTION_NAME):
        print(f"  --drop 模式：删除已存在的 Collection: {COLLECTION_NAME}")
        client.drop_collection(COLLECTION_NAME)
        print(f"  ✓ Collection 已删除: {COLLECTION_NAME}")
    else:
        print(f"  --drop 模式：Collection 不存在，无需删除: {COLLECTION_NAME}")


def check_and_create_collection(client):
    """检查 Collection 是否存在，不存在则创建（幂等）"""
    print(f"[2/4] 检查 Collection 是否存在: {COLLECTION_NAME}")

    if client.has_collection(COLLECTION_NAME):
        print(f"  ✓ Collection 已存在，跳过创建: {COLLECTION_NAME}")
        return False  # 返回 False 表示已存在，跳过后续创建步骤

    print(f"  Collection 不存在，开始创建: {COLLECTION_NAME}")

    # 构建 Schema（pymilvus v2 API：create_schema + add_field）
    schema = MilvusClient.create_schema()

    # 1. chunk_id — 主键
    schema.add_field(
        field_name="chunk_id",
        datatype=DataType.VARCHAR,
        max_length=MAX_LEN_CHUNK_ID,
        is_primary=True,
        auto_id=False,
    )
    # 2. doc_id — 文档 ID
    schema.add_field(
        field_name="doc_id",
        datatype=DataType.VARCHAR,
        max_length=MAX_LEN_DOC_ID,
    )
    # 3. kb_id — 知识库 ID
    schema.add_field(
        field_name="kb_id",
        datatype=DataType.VARCHAR,
        max_length=MAX_LEN_KB_ID,
    )
    # 4. content — 分片文本内容（enable_analyzer=True，BM25 Function 输入）
    schema.add_field(
        field_name="content",
        datatype=DataType.VARCHAR,
        max_length=MAX_LEN_CONTENT,
        enable_analyzer=True,
    )
    # 5. heading_path — 标题导航路径
    schema.add_field(
        field_name="heading_path",
        datatype=DataType.VARCHAR,
        max_length=MAX_LEN_HEADING_PATH,
    )
    # 6. dense_vector — dense 向量
    schema.add_field(
        field_name="dense_vector",
        datatype=DataType.FLOAT_VECTOR,
        dim=EMBEDDING_DIM,
    )
    # 7. sparse_vector — sparse 向量（服务端 BM25 Function 自动生成）
    schema.add_field(
        field_name="sparse_vector",
        datatype=DataType.SPARSE_FLOAT_VECTOR,
    )
    # 8. chunk_index — 分片序号
    schema.add_field(
        field_name="chunk_index",
        datatype=DataType.INT32,
    )
    # 9. token_count — token 数量
    schema.add_field(
        field_name="token_count",
        datatype=DataType.INT32,
    )
    # 10. collection_type — 标量路由字段
    schema.add_field(
        field_name="collection_type",
        datatype=DataType.VARCHAR,
        max_length=MAX_LEN_COLLECTION_TYPE,
    )
    # 11. course_id — 课程 ID
    schema.add_field(
        field_name="course_id",
        datatype=DataType.VARCHAR,
        max_length=MAX_LEN_COURSE_ID,
    )
    # 12. updated_at — 更新时间戳
    schema.add_field(
        field_name="updated_at",
        datatype=DataType.INT64,
    )

    # BM25 Function: content → sparse_vector
    schema.add_function(
        Function(
            name="bm25_func",
            function_type=FunctionType.BM25,
            input_field_names=["content"],
            output_field_names=["sparse_vector"],
        )
    )

    # 构建索引参数（随 Collection 一起创建）
    index_params = client.prepare_index_params()

    # 1. dense_vector: HNSW + COSINE
    index_params.add_index(
        field_name="dense_vector",
        index_type="HNSW",
        metric_type="COSINE",
        params={"M": HNSW_M, "efConstruction": HNSW_EF_CONSTRUCTION},
    )
    # 2. sparse_vector: SPARSE_INVERTED_INDEX + BM25
    index_params.add_index(
        field_name="sparse_vector",
        index_type="SPARSE_INVERTED_INDEX",
        metric_type="BM25",
    )
    # 3. collection_type: INVERTED（标量索引）
    index_params.add_index(
        field_name="collection_type",
        index_type="INVERTED",
    )
    # 4. course_id: INVERTED（标量索引）
    index_params.add_index(
        field_name="course_id",
        index_type="INVERTED",
    )

    # 一步创建 Collection（含 Schema + Function + 索引）
    client.create_collection(
        collection_name=COLLECTION_NAME,
        schema=schema,
        index_params=index_params,
    )
    print(f"  ✓ Collection 创建成功（含 Schema + Function + 索引）: {COLLECTION_NAME}")
    return True


def load_collection(client):
    """加载 Collection 到内存"""
    print(f"[3/4] 加载 Collection 到内存: {COLLECTION_NAME}")
    client.load_collection(COLLECTION_NAME)
    print(f"  ✓ Collection 加载完成: {COLLECTION_NAME}")


def verify_collection(client):
    """验证 Collection 描述"""
    print(f"[4/4] 验证 Collection 描述: {COLLECTION_NAME}")
    desc = client.describe_collection(COLLECTION_NAME)
    print(f"  Collection 信息: name={desc.get('collection_name', 'N/A')}")
    fields = desc.get("fields", [])
    functions = desc.get("functions", [])
    print(f"  字段数: {len(fields)}")
    for f in fields:
        print(f"    - {f.get('name')}: {f.get('type')}")
    print(f"  Function 数: {len(functions)}")
    for fn in functions:
        print(f"    - {fn.get('name')}: {fn.get('type')}")


def main():
    """主入口"""
    print("=" * 60)
    print("Milvus Collection knowledge_chunks 初始化脚本（v2 API）")
    print("=" * 60)

    args = parse_args()

    # 1. 连接 Milvus
    client = connect_milvus(args.host, args.port)

    # --drop 模式：先删除
    drop_if_requested(client, args.drop)

    # 2. 检查并创建 Collection
    created = check_and_create_collection(client)

    # Collection 已存在则跳过后续步骤
    if not created:
        print(f"\n初始化完成（Collection 已存在，跳过索引创建和加载）: {COLLECTION_NAME}")
        print("=" * 60)
        return

    # 3. 加载 Collection
    load_collection(client)

    # 4. 验证
    verify_collection(client)

    print(f"\n初始化完成: {COLLECTION_NAME}")
    print("=" * 60)


if __name__ == "__main__":
    main()
