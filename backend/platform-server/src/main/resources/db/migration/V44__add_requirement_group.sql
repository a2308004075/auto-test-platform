-- =====================================================================
-- V44: 需求文档模块新增分组功能
-- 包含：需求分组表、requirement_version 表增加 group_id 列、
--       存量项目初始化系统分组（未分组）、存量版本回填分组、
--       菜单与按钮权限（project:req:group）
-- =====================================================================

-- ── 1. 创建需求分组表（对齐 code_repository_group 结构，去掉服务前缀/Swagger 字段） ──
CREATE TABLE `requirement_group` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `project_id` bigint NOT NULL COMMENT '所属项目 ID',
  `parent_id` bigint DEFAULT NULL COMMENT '父分组 ID（null=根分组）',
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分组名称',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分组描述',
  `is_system` tinyint NOT NULL DEFAULT '0' COMMENT '是否系统默认分组（0-否，1-是）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_requirement_group_project_name` (`project_id`,`name`),
  KEY `idx_requirement_group_project_id` (`project_id`),
  KEY `idx_requirement_group_parent_id` (`parent_id`),
  CONSTRAINT `fk_requirement_group_project_id` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求分组表';

-- ── 2. 为每个已有项目初始化系统分组「未分组」（含软删项目，与 ProjectService 文案一致） ──
INSERT INTO `requirement_group` (`project_id`, `parent_id`, `name`, `description`, `is_system`, `created_at`, `updated_at`)
SELECT `id`, NULL, '未分组', '未分组的需求版本', 1, NOW(), NOW() FROM `project`;

-- ── 3. requirement_version 表新增 group_id 列（先可空回填，后收紧为 NOT NULL） ──
ALTER TABLE `requirement_version`
  ADD COLUMN `group_id` bigint DEFAULT NULL COMMENT '所属分组 ID' AFTER `project_id`;

-- 存量版本回填：归属各项目的「未分组」系统分组
UPDATE `requirement_version` v
INNER JOIN `requirement_group` g
  ON g.`project_id` = v.`project_id` AND g.`name` = '未分组' AND g.`is_system` = 1
SET v.`group_id` = g.`id`;

-- 回填完成后收紧为 NOT NULL（对齐 code_repository.group_id 模式）
ALTER TABLE `requirement_version`
  MODIFY COLUMN `group_id` bigint NOT NULL COMMENT '所属分组 ID',
  ADD KEY `idx_requirement_version_group_id` (`group_id`),
  ADD CONSTRAINT `fk_requirement_version_group_id` FOREIGN KEY (`group_id`) REFERENCES `requirement_group` (`id`) ON DELETE CASCADE;

-- ── 4. sys_menu：【需求文档】菜单（id=94）下新增【分组管理】按钮权限 ──
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `menu_type`, `icon`, `route_path`, `component`, `permission_code`, `sort_no`, `is_active`, `created_at`, `updated_at`) VALUES
(124, 94, '分组管理', 3, NULL, NULL, NULL, 'project:req:group', 7, 1, NOW(), NOW());

-- ── 5. permission：与 sys_menu 同步（挂 parent=103 即需求文档页面权限） ──
INSERT INTO `permission` (`id`, `permission_name`, `permission_code`, `type`, `parent_id`, `path`, `sort_order`, `is_active`, `description`, `control_mode`, `created_at`, `updated_at`) VALUES
(132, '分组管理', 'project:req:group', 'BUTTON', 103, NULL, 7, 1, '需求文档分组管理按钮', 'display', NOW(), NOW());

-- ── 6. role_permission：ADMIN 分配分组管理权限 ──
INSERT INTO `role_permission` (`role_id`, `permission_id`, `control_mode`, `created_at`) VALUES
(1, 132, 'enabled', NOW());
