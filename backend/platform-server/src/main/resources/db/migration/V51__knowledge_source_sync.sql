-- =====================================================================
-- V51: 知识库资料来源扩展
-- 知识库文档从"仅项目文档导入"升级为"五类项目资料自动同步"：
--   需求文档（REQUIREMENT_VERSION）/ 项目文档（PROJECT_DOC）/ 源代码（CODE_REPOSITORY）
--   / 接口文档（API_MODULE）/ 界面元素（UI_ELEMENT）
-- =====================================================================

-- ── 1. knowledge_document 新增来源与内容指纹列 ──
ALTER TABLE `knowledge_document`
  ADD COLUMN `source_type` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源类型：REQUIREMENT_VERSION/PROJECT_DOC/CODE_REPOSITORY/API_MODULE/UI_ELEMENT' AFTER `project_doc_id`,
  ADD COLUMN `source_id` bigint DEFAULT NULL COMMENT '来源记录 ID（对应来源表主键）' AFTER `source_type`,
  ADD COLUMN `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '内容指纹（MD5），用于增量同步判断内容是否变化' AFTER `process_config`;

-- ── 2. 存量数据回填：历史从项目资料导入的文档迁移到统一来源字段 ──
UPDATE `knowledge_document`
SET `source_type` = 'PROJECT_DOC', `source_id` = `project_doc_id`
WHERE `project_doc_id` IS NOT NULL;

-- ── 3. 来源联合索引（同一知识库内同一来源唯一，代码层保证去重） ──
CREATE INDEX `idx_knowledge_doc_source` ON `knowledge_document` (`knowledge_base_id`, `source_type`, `source_id`);
