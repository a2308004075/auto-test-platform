-- =====================================================================
-- V49: AI 白盒测试模块（M17）
-- 包含：任务表、变更方法表、生成测试表、报告表、菜单与权限
-- =====================================================================

-- ── 1. 白盒测试任务表 ──
CREATE TABLE `ai_whitebox_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `project_id` bigint NOT NULL COMMENT '所属项目 ID',
  `repository_id` bigint NOT NULL COMMENT '源代码仓库 ID（code_repository.id）',
  `repository_name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '仓库名称（冗余）',
  `requirement_version_id` bigint DEFAULT NULL COMMENT '需求版本 ID（NULL=未选择，语义受限）',
  `requirement_version_name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '需求版本名称（冗余）',
  `baseline_commit` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '基准 commit（增量对比起点）',
  `head_commit` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '本次任务 HEAD commit',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/COMPLETED/FAILED/CANCELLED',
  `current_phase` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前阶段：sync/diff/analyze/align/generate/build/test/quality/report',
  `progress` int NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
  `task_log` longtext COLLATE utf8mb4_unicode_ci COMMENT '任务日志',
  `total_changed_files` int NOT NULL DEFAULT 0 COMMENT '变更文件总数',
  `total_changed_methods` int NOT NULL DEFAULT 0 COMMENT '变更方法总数',
  `total_cases` int NOT NULL DEFAULT 0 COMMENT '生成用例总数',
  `total_test_classes` int NOT NULL DEFAULT 0 COMMENT '生成测试类总数',
  `compile_pass` int NOT NULL DEFAULT 0 COMMENT '编译通过测试类数',
  `exec_pass` int NOT NULL DEFAULT 0 COMMENT '执行通过用例数',
  `exec_fail` int NOT NULL DEFAULT 0 COMMENT '执行失败用例数',
  `total_mutants` int NOT NULL DEFAULT 0 COMMENT '变异体总数',
  `killed_mutants` int NOT NULL DEFAULT 0 COMMENT '被杀死变异体数',
  `branch_coverage` decimal(5,2) DEFAULT NULL COMMENT '分支覆盖率（%）',
  `line_coverage` decimal(5,2) DEFAULT NULL COMMENT '行覆盖率（%）',
  `mutation_score` decimal(5,2) DEFAULT NULL COMMENT '变异得分（%）',
  `tokens_used` bigint NOT NULL DEFAULT 0 COMMENT 'LLM 消耗 token 总数',
  `started_at` datetime DEFAULT NULL COMMENT '开始时间',
  `completed_at` datetime DEFAULT NULL COMMENT '完成时间',
  `created_by` bigint DEFAULT NULL COMMENT '创建人 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_whitebox_task_project_id` (`project_id`),
  KEY `idx_ai_whitebox_task_status` (`status`),
  KEY `idx_ai_whitebox_task_created_by` (`created_by`),
  KEY `idx_ai_whitebox_task_repo_id` (`repository_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 白盒测试任务表';

-- ── 2. 变更方法表 ──
CREATE TABLE `ai_whitebox_method` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` bigint NOT NULL COMMENT '所属任务 ID',
  `file_path` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '源码文件相对路径',
  `class_name` varchar(300) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '完整类名（含包名）',
  `method_name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '方法名',
  `method_signature` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '方法签名',
  `change_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '变更类型：MODIFIED-直接变更，ADDED-新增，INDIRECT-间接影响',
  `start_line` int DEFAULT NULL COMMENT '方法起始行号',
  `end_line` int DEFAULT NULL COMMENT '方法结束行号',
  `source_code` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '方法源码',
  `context_json` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '静态分析上下文包（JSON：分支结构/Javadoc/依赖）',
  `related_requirements_json` text COLLATE utf8mb4_unicode_ci COMMENT '关联需求条目（JSON 数组）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_whitebox_method_task_id` (`task_id`),
  KEY `idx_ai_whitebox_method_change_type` (`change_type`),
  CONSTRAINT `fk_ai_whitebox_method_task` FOREIGN KEY (`task_id`) REFERENCES `ai_whitebox_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 白盒测试变更方法表';

-- ── 3. 生成测试表 ──
CREATE TABLE `ai_whitebox_test` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` bigint NOT NULL COMMENT '所属任务 ID',
  `method_id` bigint DEFAULT NULL COMMENT '关联变更方法 ID',
  `test_class_name` varchar(300) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '测试类完整名',
  `test_method_name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '测试方法名',
  `case_title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用例标题',
  `case_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NORMAL' COMMENT '用例类型：NORMAL-正常，EXCEPTION-异常',
  `priority` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '中' COMMENT '优先级：高/中/低',
  `preconditions` text COLLATE utf8mb4_unicode_ci COMMENT '前置条件',
  `operation_steps` text COLLATE utf8mb4_unicode_ci COMMENT '操作步骤（换行分隔）',
  `expected_result` text COLLATE utf8mb4_unicode_ci COMMENT '预期结果',
  `related_requirement_ids` text COLLATE utf8mb4_unicode_ci COMMENT '关联需求条目 ID（JSON 数组）',
  `test_code` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '对应 JUnit 测试方法代码',
  `compile_status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '编译状态：PENDING/PASSED/FAILED',
  `exec_status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '执行状态：PENDING/PASSED/FAILED/SKIPPED',
  `exec_message` text COLLATE utf8mb4_unicode_ci COMMENT '执行失败信息（断言/异常摘要）',
  `saved_to_manual` tinyint NOT NULL DEFAULT 0 COMMENT '是否已保存到手动用例库：0-否，1-是',
  `manual_case_id` bigint DEFAULT NULL COMMENT '保存后的手动用例 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_whitebox_test_task_id` (`task_id`),
  KEY `idx_ai_whitebox_test_method_id` (`method_id`),
  KEY `idx_ai_whitebox_test_saved` (`saved_to_manual`),
  CONSTRAINT `fk_ai_whitebox_test_task` FOREIGN KEY (`task_id`) REFERENCES `ai_whitebox_task` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ai_whitebox_test_method` FOREIGN KEY (`method_id`) REFERENCES `ai_whitebox_method` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 白盒测试生成测试表';

-- ── 4. 报告表 ──
CREATE TABLE `ai_whitebox_report` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` bigint NOT NULL COMMENT '所属任务 ID',
  `report_format` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '报告格式：markdown/json',
  `report_content` longtext COLLATE utf8mb4_unicode_ci COMMENT '报告内容',
  `file_name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件名',
  `file_size` bigint DEFAULT NULL COMMENT '文件大小（字节）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_whitebox_report_task_format` (`task_id`, `report_format`),
  CONSTRAINT `fk_ai_whitebox_report_task` FOREIGN KEY (`task_id`) REFERENCES `ai_whitebox_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 白盒测试报告表';

-- ── 5. sys_menu：【AI白盒测试】页面菜单（AI管理 id=125 的子项，sort_no=3） ──
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `menu_type`, `icon`, `route_path`, `component`, `permission_code`, `sort_no`, `is_active`, `created_at`, `updated_at`) VALUES
(128, 125, 'AI白盒测试', 2, '', '/project/:id/ai-whitebox', 'ai/AiWhitebox', 'project:ai-whitebox', 3, 1, NOW(), NOW());

-- ── 6. permission：与 sys_menu 同步（项目级页面挂 parent=17） ──
INSERT INTO `permission` (`id`, `permission_name`, `permission_code`, `type`, `parent_id`, `path`, `sort_order`, `is_active`, `description`, `control_mode`, `created_at`, `updated_at`) VALUES
(138, 'AI白盒测试', 'project:ai-whitebox', 'MENU', 17, NULL, 16, 1, 'AI白盒测试页面', 'display', NOW(), NOW()),
(139, '启动测试', 'project:ai-whitebox:run', 'BUTTON', 138, NULL, 1, 1, 'AI白盒测试启动任务按钮', 'display', NOW(), NOW()),
(140, '保存用例', 'project:ai-whitebox:save', 'BUTTON', 138, NULL, 2, 1, 'AI白盒测试保存用例到手动用例库按钮', 'display', NOW(), NOW());

-- ── 7. role_permission：ADMIN 全部分配，TESTER 菜单+保存用例 ──
INSERT INTO `role_permission` (`role_id`, `permission_id`, `control_mode`, `created_at`) VALUES
(1, 138, NULL, NOW()),
(1, 139, 'enabled', NOW()),
(1, 140, 'enabled', NOW()),
(2, 138, NULL, NOW()),
(2, 140, 'enabled', NOW());
