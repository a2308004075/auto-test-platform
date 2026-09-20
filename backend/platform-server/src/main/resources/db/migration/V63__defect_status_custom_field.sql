-- =====================================================================
-- V63: 缺陷状态枚举配置化 —【字段管理-编辑缺陷】预置"状态"字段
-- fieldKey=defect_status：缺陷列表/详情页流转状态下拉框的选项来源
-- 选项 value 沿用 defect.status 现有英文编码（NEW/FIXING…），存量数据无需迁移；
-- 管理员可在【字段管理】中增删选项、改显示名、调顺序（新增选项自动生成 CUSTOM_ 编码）
-- 无配置时前后端均回退 sys_dict 的 defect_status 字典
-- =====================================================================

-- 1. 为所有存量项目预置"状态"字段（幂等：已存在则跳过，避免唯一键冲突）
INSERT INTO `sys_custom_field`
  (`project_id`, `module`, `view_type`, `field_key`, `field_label`, `description`, `field_type`, `options_json`, `default_value`, `is_required`, `sort_no`, `is_active`, `created_at`, `updated_at`)
SELECT p.`id`, 'defect', 'edit', 'defect_status', '状态',
  '缺陷流转状态下拉框的枚举选项：可增删选项、修改显示名、调整顺序；删除选项后存量缺陷保留原状态值',
  'select',
  '[{"label":"新建","value":"NEW"},{"label":"待确认","value":"TO_CONFIRM"},{"label":"修复中","value":"FIXING"},{"label":"待部署","value":"TO_DEPLOY"},{"label":"待验证","value":"PENDING"},{"label":"已修复","value":"COMPLETED"},{"label":"重新打开","value":"REOPENED"},{"label":"延期修复","value":"DEFERRED"},{"label":"无需修复","value":"CLOSED"}]',
  NULL, 0, 99, 1, NOW(), NOW()
FROM `project` p
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_custom_field` cf
  WHERE cf.`project_id` = p.`id`
    AND cf.`module` = 'defect'
    AND cf.`view_type` = 'edit'
    AND cf.`field_key` = 'defect_status'
);
