-- =====================================================================
-- V62: 移除缺陷工时与层级功能
-- 删除：缺陷工时记录表、缺陷表汇总工时字段（估算/实际/剩余）、层级（父子缺陷）字段
-- =====================================================================

-- 1. 删除缺陷工时记录表
DROP TABLE IF EXISTS `defect_work_log`;

-- 2. 删除缺陷表层级字段（先删外键再删列，索引随列自动删除）
ALTER TABLE `defect`
  DROP FOREIGN KEY `fk_defect_parent_id`,
  DROP COLUMN `parent_id`;

-- 3. 删除缺陷表汇总工时字段
ALTER TABLE `defect`
  DROP COLUMN `estimated_hours`,
  DROP COLUMN `actual_hours`,
  DROP COLUMN `remaining_hours`;
