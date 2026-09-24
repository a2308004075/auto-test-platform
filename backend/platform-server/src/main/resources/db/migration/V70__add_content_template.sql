-- =====================================================================
-- V70: 【页面配置】新增【内容模板】Tab — 按项目维护缺陷"内容"富文本模板
-- =====================================================================
-- 1. 新表 sys_content_template：按项目存储富文本模板，
--    供【编辑缺陷】【缺陷详情】内容区"套用模板"使用
-- 2. sys_menu：页面配置（id=130）下新增按钮级菜单【新增模板】【编辑模板】【删除模板】
-- 3. permission：与 sys_menu 同步新增 3 条 BUTTON 权限（parent=141 页面配置）
-- 4. role_permission：ADMIN（role_id=1，当前持有【页面配置】页面权限的角色）全部分配；
--    SUPER_ADMIN 走通配权限无需显式分配

-- ── 1. sys_content_template：内容模板表 ──
CREATE TABLE `sys_content_template` (
  `id`         bigint       NOT NULL AUTO_INCREMENT,
  `project_id` bigint       NOT NULL              COMMENT '所属项目',
  `name`       varchar(50)  NOT NULL              COMMENT '模板名称（同一项目内不可重复）',
  `content`    mediumtext   NOT NULL              COMMENT '模板内容（富文本 HTML）',
  `created_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_content_template_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容模板表';

-- ── 2. sys_menu：新增按钮级菜单 ──
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `menu_type`, `icon`, `route_path`, `component`, `permission_code`, `sort_no`, `is_active`, `created_at`, `updated_at`) VALUES
(134, 130, '新增模板', 3, NULL, NULL, NULL, 'system:page-config:template:add', 3, 1, NOW(), NOW()),
(135, 130, '编辑模板', 3, NULL, NULL, NULL, 'system:page-config:template:edit', 4, 1, NOW(), NOW()),
(136, 130, '删除模板', 3, NULL, NULL, NULL, 'system:page-config:template:delete', 5, 1, NOW(), NOW());

-- ── 3. permission：与 sys_menu 同步 ──
INSERT INTO `permission` (`id`, `permission_name`, `permission_code`, `type`, `parent_id`, `path`, `sort_order`, `is_active`, `description`, `control_mode`, `created_at`, `updated_at`) VALUES
(145, '新增模板', 'system:page-config:template:add', 'BUTTON', 141, NULL, 4, 1, '新增内容模板按钮', 'display', NOW(), NOW()),
(146, '编辑模板', 'system:page-config:template:edit', 'BUTTON', 141, NULL, 5, 1, '编辑内容模板按钮', 'display', NOW(), NOW()),
(147, '删除模板', 'system:page-config:template:delete', 'BUTTON', 141, NULL, 6, 1, '删除内容模板按钮', 'display', NOW(), NOW());

-- ── 4. role_permission：ADMIN（role_id=1）分配全部模板权限 ──
INSERT INTO `role_permission` (`role_id`, `permission_id`, `control_mode`, `created_at`) VALUES
(1, 145, 'enabled', NOW()),
(1, 146, 'enabled', NOW()),
(1, 147, 'enabled', NOW());
