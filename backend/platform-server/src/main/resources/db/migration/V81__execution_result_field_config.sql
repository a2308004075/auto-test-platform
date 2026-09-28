-- =====================================================================
-- V81: 新增【用例执行】功能页 — 预置"执行结果"字段配置
-- =====================================================================
-- 用户需求：用例执行结果的枚举可在【页面配置】-【字段设置】中配置
-- 与缺陷"状态"字段同模式：系统预置、固定排第一位、必填、不可删除；
-- 结果值不走 sys_custom_field_value（存 test_result.round_results JSON），
-- 配置仅作手动计划执行页结果标记下拉框与测试记录回放的选项来源。
-- 选项 value 沿用现有英文编码（PASSED/FAILED/SKIPPED），存量数据无需迁移；
-- 为全部存量项目幂等预置（同 field_key 已存在则跳过）。

INSERT INTO `sys_custom_field`
  (`project_id`, `module`, `view_type`, `field_key`, `field_label`, `description`, `field_type`, `options_json`, `default_value`, `is_required`, `display_scope`, `sort_no`, `is_active`, `created_at`, `updated_at`)
SELECT p.`id`, 'execution', 'edit', 'execution_result', '执行结果',
  '手动计划执行页结果标记下拉框的枚举选项：可增删选项、修改显示名、调整顺序；删除选项后已记录的结果保留原值',
  'select',
  '[{"label":"通过","value":"PASSED"},{"label":"失败","value":"FAILED"},{"label":"跳过","value":"SKIPPED"}]',
  NULL, 1, 'detail', 1, 1, NOW(), NOW()
FROM `project` p
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_custom_field` cf
  WHERE cf.`project_id` = p.`id`
    AND cf.`module` = 'execution'
    AND cf.`view_type` = 'edit'
    AND cf.`field_key` = 'execution_result'
);
