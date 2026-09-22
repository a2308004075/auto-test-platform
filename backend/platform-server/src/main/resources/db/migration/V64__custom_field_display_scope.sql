-- =====================================================================
-- V64: 【字段管理】字段配置新增"显示位置"（新建/详情差异化显示）
--      需求模块双视图（新建需求/编辑需求）统一为单视图，由显示位置驱动
-- =====================================================================

-- ── 1. sys_custom_field 新增 display_scope 列（存量行默认"都显示"） ──
ALTER TABLE `sys_custom_field`
  ADD COLUMN `display_scope` varchar(20) NOT NULL DEFAULT 'both' COMMENT '显示位置：both=都显示 create=仅新建显示 detail=仅详情(编辑)显示' AFTER `is_required`;

-- ── 2. 需求模块双视图合并：create 视图独有字段（edit 视图无同 fieldKey 配置行，
--      含软删行 —— 被删除过的字段不复活）复制到 edit 视图并标记"仅新建显示"；
--      原 create 视图配置行保留不删（跨视图读取的存量兜底） ──
INSERT INTO `sys_custom_field`
  (`project_id`, `module`, `view_type`, `field_key`, `field_label`, `description`, `field_type`, `source_code`, `options_json`, `default_value`, `is_required`, `display_scope`, `sort_no`, `is_active`)
SELECT c.project_id, 'requirement', 'edit', c.field_key, c.field_label, c.description, c.field_type, c.source_code, c.options_json, c.default_value, c.is_required, 'create',
       COALESCE(m.max_sort, 0) + ROW_NUMBER() OVER (PARTITION BY c.project_id ORDER BY c.sort_no, c.id), 1
FROM `sys_custom_field` c
LEFT JOIN (
  SELECT project_id, MAX(sort_no) AS max_sort
  FROM `sys_custom_field`
  WHERE module = 'requirement' AND view_type = 'edit'
  GROUP BY project_id
) m ON m.project_id = c.project_id
WHERE c.module = 'requirement' AND c.view_type = 'create' AND c.is_active = 1
  AND NOT EXISTS (
    SELECT 1 FROM `sys_custom_field` e
    WHERE e.project_id = c.project_id AND e.module = 'requirement'
      AND e.view_type = 'edit' AND e.field_key = c.field_key
  );
