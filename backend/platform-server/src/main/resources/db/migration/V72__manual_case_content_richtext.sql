-- =====================================================================
-- V72: 手动用例"前置条件/操作步骤/预期结果"三字段合并为富文本"内容"
-- =====================================================================
-- preconditions/operation_steps/expected_result 三列合并为 content 富文本列
-- （参考缺陷"内容"，longtext 对齐 defect.content）；manual_case 表当前无存量数据，
-- 不涉及数据迁移；变更记录字段名统一为 content（与缺陷一致）。

ALTER TABLE `manual_case`
  DROP COLUMN `preconditions`,
  DROP COLUMN `operation_steps`,
  DROP COLUMN `expected_result`,
  ADD COLUMN `content` longtext COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用例内容（富文本，统一维护前置条件/操作步骤/预期结果）';
