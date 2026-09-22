-- =====================================================================
-- V68: 【字段管理】缺陷"状态"字段：显示位置固定为"缺陷详情"（detail）
--      背景：状态字段系统预置为"仅缺陷详情显示"且不可修改（前端置灰 + 后端强制），
--            本迁移将存量"都显示（create,detail）"修正为"仅缺陷详情"
-- =====================================================================

UPDATE `sys_custom_field`
SET `display_scope` = 'detail'
WHERE `module` = 'defect' AND `view_type` = 'edit' AND `field_key` = 'defect_status'
  AND `display_scope` <> 'detail';
