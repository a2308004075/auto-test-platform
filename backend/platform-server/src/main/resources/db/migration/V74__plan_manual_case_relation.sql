-- =====================================================================
-- V74: 测试计划-手动化用例关联表 + 关联级动态字段（台架/整站是否执行）
-- =====================================================================
-- 1. 创建 test_plan_manual_case 关联表：承载"计划-用例"关联（替代纯 ID JSON 数组的权威读源），
--    并作为关联级动态字段值（sys_custom_field_value，module='plan_case'，entity_id=关联行 ID）的挂载实体
-- 2. 存量数据迁移：test_plan.manual_case_ids JSON 数组 → 关联行（sort_no 按数组顺序）
-- 3. 为存量项目幂等预置【页面配置-测试计划字段】：run_on_bench（台架是否执行）、run_on_site（整站是否执行）
--    字段统一存 edit 视图；值存 sys_custom_field_value（entity_id=test_plan_manual_case.id）
-- 说明：test_plan.manual_case_ids JSON 列保留作为写镜像（兼容旧读路径），读路径逐步切换到关联表

-- ── 1. 创建测试计划-手动化用例关联表 ──
CREATE TABLE `test_plan_manual_case` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `plan_id` bigint NOT NULL COMMENT '测试计划 ID',
  `manual_case_id` bigint NOT NULL COMMENT '手动化用例 ID',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '计划内的用例顺序（沿用 JSON 数组顺序）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_case` (`plan_id`, `manual_case_id`),
  KEY `idx_plan_manual_case_case_id` (`manual_case_id`),
  CONSTRAINT `fk_plan_manual_case_plan_id` FOREIGN KEY (`plan_id`) REFERENCES `test_plan` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_plan_manual_case_case_id` FOREIGN KEY (`manual_case_id`) REFERENCES `manual_case` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试计划-手动化用例关联表';

-- ── 2. 存量数据迁移：manual_case_ids JSON 数组展开为关联行（空数组/NULL 天然产生零行；
--    注意：MySQL 8.0 的 JSON_TABLE 首参仅支持基表列直接引用，不可引用派生表列，否则报
--    ERROR 1210 Incorrect arguments to JSON_TABLE） ──
INSERT INTO `test_plan_manual_case` (`plan_id`, `manual_case_id`, `sort_no`)
SELECT t.`id`, jt.case_id, jt.ord
FROM `test_plan` t,
JSON_TABLE(
  t.`manual_case_ids`,
  '$[*]' COLUMNS (
    ord FOR ORDINALITY,
    case_id BIGINT PATH '$'
  )
) jt
WHERE jt.case_id IS NOT NULL;

-- ── 3. 为存量项目幂等预置【页面配置-测试计划字段】（同一 fieldKey 已存在则跳过） ──
INSERT INTO `sys_custom_field`
  (`project_id`, `module`, `view_type`, `field_key`, `field_label`, `description`, `field_type`, `options_json`, `default_value`, `is_required`, `display_scope`, `sort_no`, `is_active`, `created_at`, `updated_at`)
SELECT p.`id`, 'plan_case', 'edit', k.field_key, k.field_label, k.description, k.field_type, k.options_json, k.default_value, k.is_required, k.display_scope, k.sort_no, 1, NOW(), NOW()
FROM `project` p
CROSS JOIN (
  SELECT 'run_on_bench' AS field_key, '台架是否执行' AS field_label,
    '手动测试计划中该用例是否在台架环境执行（仅记录展示，不影响执行引擎）' AS description,
    'select' AS field_type,
    '[{"label":"是","value":"1"},{"label":"否","value":"0"}]' AS options_json,
    '1' AS default_value, 0 AS is_required, 'detail' AS display_scope, 1 AS sort_no
  UNION ALL SELECT 'run_on_site', '整站是否执行',
    '手动测试计划中该用例是否在整站环境执行（仅记录展示，不影响执行引擎）',
    'select',
    '[{"label":"是","value":"1"},{"label":"否","value":"0"}]',
    '1', 0, 'detail', 2
) k
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_custom_field` cf
  WHERE cf.`project_id` = p.`id`
    AND cf.`module` = 'plan_case'
    AND cf.`view_type` = 'edit'
    AND cf.`field_key` = k.field_key
);
