-- V50: 将 knowledge_chunk.milvus_id 重命名为 vector_id（Milvus → Qdrant 迁移）

ALTER TABLE `knowledge_chunk` CHANGE COLUMN `milvus_id` `vector_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '向量 ID（Qdrant point ID）';
