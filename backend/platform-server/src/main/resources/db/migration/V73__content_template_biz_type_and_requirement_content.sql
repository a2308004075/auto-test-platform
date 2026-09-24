-- =====================================================================
-- V73: 内容模板按业务类型拆分（缺陷/手动用例/需求）+ 需求条目新增"内容"富文本列
-- =====================================================================
-- 1. sys_content_template 新增 biz_type 字段（defect-缺陷 / manual_case-手动用例 / requirement-需求），
--    唯一索引 (project_id, biz_type) 保证同一项目下每类业务仅一个模板；
--    内容模板仅用于新建页（打开新建页时自动填入"内容"编辑框）
-- 2. requirement_item 新增 content 列（longtext 富文本），与缺陷/手动用例"内容"一致

-- ── 1. sys_content_template：业务类型 ──
ALTER TABLE `sys_content_template`
  ADD COLUMN `biz_type` varchar(20) NOT NULL DEFAULT 'defect'
      COMMENT '业务类型：defect-缺陷，manual_case-手动用例，requirement-需求' AFTER `project_id`,
  ADD UNIQUE KEY `uk_content_template_project_biz` (`project_id`, `biz_type`);

-- ── 2. requirement_item：内容（富文本） ──
ALTER TABLE `requirement_item`
  ADD COLUMN `content` longtext COLLATE utf8mb4_unicode_ci DEFAULT NULL
      COMMENT '需求内容（富文本，与缺陷/手动用例"内容"一致）' AFTER `description`;
