-- =====================================================================
-- V61: 【字段管理】sys_custom_field 新增 description（字段描述）列
-- 用途：字段列表展示与新增/编辑字段时填写，最多 200 字
-- =====================================================================

ALTER TABLE `sys_custom_field`
  ADD COLUMN `description` varchar(500) DEFAULT NULL COMMENT '字段描述（最多200字）' AFTER `field_label`;
