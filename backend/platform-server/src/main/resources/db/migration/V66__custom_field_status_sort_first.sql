-- =====================================================================
-- V66: 【字段管理】缺陷"状态"字段固定排第一位
--      范围：存在"状态"字段（field_key=defect_status）的缺陷编辑视图配置
--      处理：状态 sort_no 置 1，其余字段按原顺序顺延为 2、3、4…（保持相对顺序）
-- =====================================================================

-- ── 1. 非状态字段：按原顺序（sort_no, id）整体顺延一位，让出第一位 ──
UPDATE `sys_custom_field` f
JOIN (
  SELECT `id`,
         ROW_NUMBER() OVER (PARTITION BY `project_id` ORDER BY `sort_no`, `id`) + 1 AS `rn`
  FROM `sys_custom_field`
  WHERE `module` = 'defect'
    AND `view_type` = 'edit'
    AND `field_key` <> 'defect_status'
    AND `project_id` IN (
      SELECT `project_id`
      FROM (
        SELECT DISTINCT `project_id`
        FROM `sys_custom_field`
        WHERE `module` = 'defect' AND `view_type` = 'edit' AND `field_key` = 'defect_status'
      ) `has_status`
    )
) `t` ON f.`id` = t.`id`
SET f.`sort_no` = t.`rn`;

-- ── 2. 状态字段：固定第一位 ──
UPDATE `sys_custom_field`
SET `sort_no` = 1
WHERE `module` = 'defect' AND `view_type` = 'edit' AND `field_key` = 'defect_status';
