-- =====================================================================
-- V82: 缺陷"状态"字段预置默认值"新建"（NEW）
-- =====================================================================
-- 用户需求：【页面配置】-【缺陷】-【字段设置】中"状态"字段，默认值为"新建"
-- default_value 预置 NEW，供【字段设置】编辑弹窗只读展示"默认值=新建"；
-- 新建缺陷行为不变（DefectService.createDefect 仍固定置 NEW，不受该配置影响）。
-- 仅回填存量项目中 default_value 为空的历史行，幂等可重复执行。

UPDATE `sys_custom_field`
SET `default_value` = 'NEW'
WHERE `module` = 'defect'
  AND `view_type` = 'edit'
  AND `field_key` = 'defect_status'
  AND (`default_value` IS NULL OR `default_value` = '');
