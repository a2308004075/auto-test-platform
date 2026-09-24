-- =====================================================================
-- V71: 手动用例模块对齐缺陷 — 属性字段动态化 + 附件表
-- =====================================================================
-- 1. 为存量项目初始化【页面配置-手动用例字段】默认字段配置（统一存 edit 视图）：
--    - case_status（状态，sort=1，系统字段）：仅作详情页状态下拉框的选项来源，
--      值仍走 manual_case.case_status 列（启停/筛选逻辑不变）
--    - case_type / priority / run_in_test_env / run_in_prod_env（4 个属性字段）：
--      值改存 sys_custom_field_value，原列保留不动、停止读写
-- 2. 存量手动用例数据迁移：4 个属性列值 → sys_custom_field_value
-- 3. 新增 manual_case_attachment 附件表（镜像 defect_attachment）

-- ── 1. 初始化存量项目默认字段配置（幂等：同 fieldKey 已存在则跳过） ──
INSERT INTO `sys_custom_field`
  (`project_id`, `module`, `view_type`, `field_key`, `field_label`, `description`, `field_type`, `options_json`, `default_value`, `is_required`, `display_scope`, `sort_no`, `is_active`, `created_at`, `updated_at`)
SELECT p.`id`, 'manual_case', 'edit', k.field_key, k.field_label, k.description, k.field_type, k.options_json, k.default_value, k.is_required, k.display_scope, k.sort_no, 1, NOW(), NOW()
FROM `project` p
CROSS JOIN (
  SELECT 'case_status' AS field_key, '状态' AS field_label,
    '手动用例启用状态（使用/废弃）的枚举选项：详情页状态下拉框的选项来源；删除选项后存量用例保留原状态值' AS description,
    'select' AS field_type,
    '[{"label":"使用","value":"1"},{"label":"废弃","value":"0"}]' AS options_json,
    NULL AS default_value, 1 AS is_required, 'detail' AS display_scope, 1 AS sort_no
  UNION ALL SELECT 'case_type', '用例类型', NULL, 'select', '[{"label":"正常","value":"NORMAL"},{"label":"异常","value":"EXCEPTION"}]', 'NORMAL', 0, 'create,detail', 2
  UNION ALL SELECT 'priority', '优先级', NULL, 'select', '[{"label":"高","value":"高"},{"label":"中","value":"中"},{"label":"低","value":"低"}]', '中', 0, 'create,detail', 3
  UNION ALL SELECT 'run_in_test_env', '测试环境是否执行', NULL, 'select', '[{"label":"是","value":"1"},{"label":"否","value":"0"}]', '1', 0, 'create,detail', 4
  UNION ALL SELECT 'run_in_prod_env', '生产环境是否执行', NULL, 'select', '[{"label":"是","value":"1"},{"label":"否","value":"0"}]', '0', 0, 'create,detail', 5
) k
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_custom_field` cf
  WHERE cf.`project_id` = p.`id`
    AND cf.`module` = 'manual_case'
    AND cf.`view_type` = 'edit'
    AND cf.`field_key` = k.field_key
);

-- ── 2. 存量手动用例数据迁移（仅非空值；tinyint 列 CAST 为字符串，对齐动态字段文本存储） ──
-- 用例类型
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'manual_case', m.id, m.case_type
FROM `manual_case` m
JOIN `sys_custom_field` cf ON cf.project_id = m.project_id AND cf.module = 'manual_case' AND cf.view_type = 'edit' AND cf.field_key = 'case_type'
WHERE m.case_type IS NOT NULL AND m.case_type != '';

-- 优先级
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'manual_case', m.id, m.priority
FROM `manual_case` m
JOIN `sys_custom_field` cf ON cf.project_id = m.project_id AND cf.module = 'manual_case' AND cf.view_type = 'edit' AND cf.field_key = 'priority'
WHERE m.priority IS NOT NULL AND m.priority != '';

-- 测试环境是否执行
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'manual_case', m.id, CAST(m.run_in_test_env AS CHAR)
FROM `manual_case` m
JOIN `sys_custom_field` cf ON cf.project_id = m.project_id AND cf.module = 'manual_case' AND cf.view_type = 'edit' AND cf.field_key = 'run_in_test_env';

-- 生产环境是否执行
INSERT INTO `sys_custom_field_value` (`field_id`, `module`, `entity_id`, `field_value`)
SELECT cf.id, 'manual_case', m.id, CAST(m.run_in_prod_env AS CHAR)
FROM `manual_case` m
JOIN `sys_custom_field` cf ON cf.project_id = m.project_id AND cf.module = 'manual_case' AND cf.view_type = 'edit' AND cf.field_key = 'run_in_prod_env';

-- ── 3. 创建手动用例附件表（镜像 defect_attachment） ──
CREATE TABLE `manual_case_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `manual_case_id` bigint NOT NULL COMMENT '手动用例 ID',
  `file_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件名称',
  `file_url` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件访问 URL',
  `file_size` bigint DEFAULT '0' COMMENT '文件大小（字节）',
  `created_by` bigint DEFAULT NULL COMMENT '上传人 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (`id`),
  KEY `idx_manual_case_attachment_case_id` (`manual_case_id`),
  CONSTRAINT `fk_manual_case_attachment_case_id` FOREIGN KEY (`manual_case_id`) REFERENCES `manual_case` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_manual_case_attachment_created_by` FOREIGN KEY (`created_by`) REFERENCES `user` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='手动用例附件表';
