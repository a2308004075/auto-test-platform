-- =====================================================================
-- V58: 缺陷模块字段动态化 — 固定字段改为【字段管理】动态驱动
-- =====================================================================
-- 1. sys_custom_field 新增 source_code 列（dict 类型存字典编码）
-- 2. 为存量项目初始化【缺陷-新建缺陷/编辑缺陷】默认字段配置
-- 3. 存量缺陷数据迁移：固定列值 → sys_custom_field_value
--    （defect 表原列保留不动，仅停止前端读写）

-- ── 1. source_code 列 ──
ALTER TABLE `sys_custom_field`
  ADD COLUMN `source_code` varchar(100) DEFAULT NULL COMMENT '数据源编码：dict 类型=字典类型编码；user/environment 类型为空' AFTER `field_type`;

-- ── 2. 初始化存量项目默认字段配置 ──
-- 新建缺陷视图（9 个字段，对齐原 DefectEdit 新建模式表单）
INSERT INTO `sys_custom_field` (`project_id`, `module`, `view_type`, `field_key`, `field_label`, `field_type`, `source_code`, `options_json`, `default_value`, `is_required`, `sort_no`, `is_active`)
SELECT p.id, 'defect', 'create', k.field_key, k.field_label, k.field_type, k.source_code, NULL, k.default_value, 0, k.sort_no, 1
FROM `project` p
CROSS JOIN (
  SELECT 'defect_assignee'      AS field_key, '负责人'         AS field_label, 'user'        AS field_type, NULL             AS source_code, NULL               AS default_value, 1 AS sort_no
  UNION ALL SELECT 'defect_responsible',  '责任人',           'user',        NULL,                NULL,                    2
  UNION ALL SELECT 'defect_severity',     '严重级别',         'dict',       'defect_severity',   '一般',                  3
  UNION ALL SELECT 'defect_source',       '缺陷根源',         'dict',       'defect_source',     '开发修改引入',          4
  UNION ALL SELECT 'defect_module',       '所属模块',         'text',       NULL,                NULL,                    5
  UNION ALL SELECT 'defect_found_version','发现的版本',       'text',       NULL,                NULL,                    6
  UNION ALL SELECT 'defect_environment',  '环境信息',         'environment',NULL,                NULL,                    7
  UNION ALL SELECT 'defect_fixed_version','修改的版本',       'text',       NULL,                NULL,                    8
  UNION ALL SELECT 'defect_reason',       '原因描述',         'text',       NULL,                NULL,                    9
) k;

-- 编辑缺陷视图（11 个字段 = 新建 9 个 + 计划开始/完成修复时间）
INSERT INTO `sys_custom_field` (`project_id`, `module`, `view_type`, `field_key`, `field_label`, `field_type`, `source_code`, `options_json`, `default_value`, `is_required`, `sort_no`, `is_active`)
SELECT p.id, 'defect', 'edit', k.field_key, k.field_label, k.field_type, k.source_code, NULL, k.default_value, 0, k.sort_no, 1
FROM `project` p
CROSS JOIN (
  SELECT 'defect_assignee'      AS field_key, '负责人'         AS field_label, 'user'        AS field_type, NULL             AS source_code, NULL               AS default_value, 1 AS sort_no
  UNION ALL SELECT 'defect_responsible',  '责任人',           'user',        NULL,                NULL,                    2
  UNION ALL SELECT 'defect_severity',     '严重级别',         'dict',       'defect_severity',   '一般',                  3
  UNION ALL SELECT 'defect_source',       '缺陷根源',         'dict',       'defect_source',     '开发修改引入',          4
  UNION ALL SELECT 'defect_module',       '所属模块',         'text',       NULL,                NULL,                    5
  UNION ALL SELECT 'defect_found_version','发现的版本',       'text',       NULL,                NULL,                    6
  UNION ALL SELECT 'defect_environment',  '环境信息',         'environment',NULL,                NULL,                    7
  UNION ALL SELECT 'defect_fixed_version','修改的版本',       'text',       NULL,                NULL,                    8
  UNION ALL SELECT 'defect_reason',       '原因描述',         'text',       NULL,                NULL,                    9
  UNION ALL SELECT 'defect_plan_start',   '计划开始修复时间', 'datetime',   NULL,                NULL,                   10
  UNION ALL SELECT 'defect_plan_end',     '计划完成修复时间', 'datetime',   NULL,                NULL,                   11
) k;

-- ── 3. 存量缺陷数据迁移（仅非空值；user/environment 存 ID 字符串，date 补全为日期时间格式） ──
-- 负责人
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, CAST(d.assignee_id AS CHAR)
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'create' AND cf.field_key = 'defect_assignee'
WHERE d.assignee_id IS NOT NULL;

-- 责任人
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, CAST(d.responsible_id AS CHAR)
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'create' AND cf.field_key = 'defect_responsible'
WHERE d.responsible_id IS NOT NULL;

-- 严重级别
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, d.severity
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'create' AND cf.field_key = 'defect_severity'
WHERE d.severity IS NOT NULL AND d.severity != '';

-- 缺陷根源
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, d.source
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'create' AND cf.field_key = 'defect_source'
WHERE d.source IS NOT NULL AND d.source != '';

-- 所属模块
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, d.module_name
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'create' AND cf.field_key = 'defect_module'
WHERE d.module_name IS NOT NULL AND d.module_name != '';

-- 发现的版本
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, d.found_version
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'create' AND cf.field_key = 'defect_found_version'
WHERE d.found_version IS NOT NULL AND d.found_version != '';

-- 环境信息
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, CAST(d.environment_id AS CHAR)
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'create' AND cf.field_key = 'defect_environment'
WHERE d.environment_id IS NOT NULL;

-- 修改的版本
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, d.fixed_version
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'create' AND cf.field_key = 'defect_fixed_version'
WHERE d.fixed_version IS NOT NULL AND d.fixed_version != '';

-- 原因描述
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, d.reason_description
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'create' AND cf.field_key = 'defect_reason'
WHERE d.reason_description IS NOT NULL AND d.reason_description != '';

-- 计划开始修复时间（date 列补全为 YYYY-MM-DD HH:mm 格式，对齐前端 date-picker value-format）
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, DATE_FORMAT(d.plan_test_date, '%Y-%m-%d 00:00')
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'edit' AND cf.field_key = 'defect_plan_start'
WHERE d.plan_test_date IS NOT NULL;

-- 计划完成修复时间
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'defect', d.id, DATE_FORMAT(d.due_date, '%Y-%m-%d 00:00')
FROM `defect` d
JOIN `sys_custom_field` cf ON cf.project_id = d.project_id AND cf.module = 'defect' AND cf.view_type = 'edit' AND cf.field_key = 'defect_plan_end'
WHERE d.due_date IS NOT NULL;
