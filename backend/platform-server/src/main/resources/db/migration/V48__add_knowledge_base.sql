-- =====================================================================
-- V48: 知识库模块
-- 包含：知识库主表、文档表、分块表、会话表、消息表、菜单与权限
-- =====================================================================

-- ── 1. 知识库主表 ──
CREATE TABLE `knowledge_base` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `project_id` bigint NOT NULL COMMENT '所属项目 ID',
  `name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '知识库名称',
  `description` text COLLATE utf8mb4_unicode_ci COMMENT '知识库描述',
  `embedding_model` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'text-embedding-3-small' COMMENT '向量嵌入模型',
  `embedding_dimension` int NOT NULL DEFAULT 1536 COMMENT '向量维度',
  `doc_count` int NOT NULL DEFAULT 0 COMMENT '文档总数',
  `chunk_count` int NOT NULL DEFAULT 0 COMMENT '分块总数',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'READY' COMMENT '状态：READY/PROCESSING/ERROR',
  `created_by` bigint DEFAULT NULL COMMENT '创建人 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_base_project_id` (`project_id`),
  KEY `idx_knowledge_base_created_by` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库主表';

-- ── 2. 知识库文档表 ──
CREATE TABLE `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `knowledge_base_id` bigint NOT NULL COMMENT '所属知识库 ID',
  `project_doc_id` bigint DEFAULT NULL COMMENT '关联项目资料文档 ID',
  `doc_name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文档名称',
  `file_name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '原始文件名',
  `file_size` bigint DEFAULT NULL COMMENT '文件大小（字节）',
  `content_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'MIME 类型',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/PROCESSING/COMPLETED/ERROR',
  `chunk_count` int NOT NULL DEFAULT 0 COMMENT '分块数',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT '错误信息',
  `process_config` text COLLATE utf8mb4_unicode_ci COMMENT '处理配置（JSON）',
  `created_by` bigint DEFAULT NULL COMMENT '导入人 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_doc_kb_id` (`knowledge_base_id`),
  KEY `idx_knowledge_doc_project_doc_id` (`project_doc_id`),
  KEY `idx_knowledge_doc_status` (`status`),
  CONSTRAINT `fk_knowledge_doc_kb` FOREIGN KEY (`knowledge_base_id`) REFERENCES `knowledge_base` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表';

-- ── 3. 文档分块表 ──
CREATE TABLE `knowledge_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `document_id` bigint NOT NULL COMMENT '所属文档 ID',
  `chunk_index` int NOT NULL COMMENT '分块序号（从 0 开始）',
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分块文本内容',
  `token_count` int NOT NULL DEFAULT 0 COMMENT '预估 token 数',
  `metadata_json` text COLLATE utf8mb4_unicode_ci COMMENT '元数据（JSON，如页码、标题等）',
  `milvus_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Milvus 向量 ID',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/VECTORIZED/ERROR',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_chunk_doc_id` (`document_id`),
  KEY `idx_knowledge_chunk_status` (`status`),
  CONSTRAINT `fk_knowledge_chunk_doc` FOREIGN KEY (`document_id`) REFERENCES `knowledge_document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档分块表';

-- ── 4. 对话会话表 ──
CREATE TABLE `knowledge_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `knowledge_base_id` bigint NOT NULL COMMENT '所属知识库 ID',
  `title` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话标题（自动取首条消息摘要）',
  `user_id` bigint DEFAULT NULL COMMENT '用户 ID',
  `message_count` int NOT NULL DEFAULT 0 COMMENT '消息总数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_conv_kb_id` (`knowledge_base_id`),
  KEY `idx_knowledge_conv_user_id` (`user_id`),
  CONSTRAINT `fk_knowledge_conv_kb` FOREIGN KEY (`knowledge_base_id`) REFERENCES `knowledge_base` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库对话会话表';

-- ── 5. 对话消息表 ──
CREATE TABLE `knowledge_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` bigint NOT NULL COMMENT '所属会话 ID',
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色：user/assistant/system',
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息内容',
  `tokens_used` int DEFAULT NULL COMMENT '消耗 token 数',
  `sources_json` text COLLATE utf8mb4_unicode_ci COMMENT '引用来源（JSON 数组）',
  `duration_ms` bigint DEFAULT NULL COMMENT '生成耗时（毫秒）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_msg_conv_id` (`conversation_id`),
  KEY `idx_knowledge_msg_role` (`role`),
  CONSTRAINT `fk_knowledge_msg_conv` FOREIGN KEY (`conversation_id`) REFERENCES `knowledge_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库对话消息表';

-- ── 6. sys_menu：知识库页面菜单（AI管理 id=125 的子项） ──
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `menu_type`, `icon`, `route_path`, `component`, `permission_code`, `sort_no`, `is_active`, `created_at`, `updated_at`) VALUES
(127, 125, '知识库', 2, '', '/project/:id/knowledge', 'knowledge/KnowledgeBase', 'project:knowledge', 2, 1, NOW(), NOW());

-- ── 7. permission：与 sys_menu 同步 ──
INSERT INTO `permission` (`id`, `permission_name`, `permission_code`, `type`, `parent_id`, `path`, `sort_order`, `is_active`, `description`, `control_mode`, `created_at`, `updated_at`) VALUES
(135, '知识库', 'project:knowledge', 'MENU', 17, NULL, 15, 1, '知识库页面', 'display', NOW(), NOW()),
(136, '知识问答', 'project:knowledge:chat', 'BUTTON', 135, NULL, 1, 1, '知识库问答按钮', 'display', NOW(), NOW()),
(137, '文档管理', 'project:knowledge:doc', 'BUTTON', 135, NULL, 2, 1, '知识库文档管理按钮', 'display', NOW(), NOW());

-- ── 8. role_permission：ADMIN 全部分配，TESTER 分配菜单+问答 ──
INSERT INTO `role_permission` (`role_id`, `permission_id`, `control_mode`, `created_at`) VALUES
(1, 135, NULL, NOW()),
(1, 136, 'enabled', NOW()),
(1, 137, 'enabled', NOW()),
(2, 135, NULL, NOW()),
(2, 136, 'enabled', NOW());
