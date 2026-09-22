-- =====================================================================
-- V67: 【字段管理】缺陷"状态"字段：必填属性固定为 1，排序值固定为第一位
--      背景：状态字段系统预置为必填且不可修改；排序值仅由拖拽排序接口管理，
--            本迁移修正历史"编辑保存携带陈旧排序值"造成的偏移
-- =====================================================================

-- ── 1. "状态"字段：固定为必填（不可被修改，统一各环境数据基线） ──
UPDATE `sys_custom_field`
SET `is_required` = 1
WHERE `module` = 'defect' AND `view_type` = 'edit' AND `field_key` = 'defect_status'
  AND `is_required` <> 1;

-- ── 2. "状态"字段：固定排第一位（sort_no = 1） ──
--     其余字段无需顺延（V66 已规整）；本迁移仅修正状态字段自身的偏移
UPDATE `sys_custom_field`
SET `sort_no` = 1
WHERE `module` = 'defect' AND `view_type` = 'edit' AND `field_key` = 'defect_status'
  AND `sort_no` <> 1;
