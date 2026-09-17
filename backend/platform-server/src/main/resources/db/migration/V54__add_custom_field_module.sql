-- =====================================================================
-- V54: 新增【字段管理】模块 — 支持按项目、模块、视图配置动态字段
-- =====================================================================

-- ── 1. sys_custom_field：字段定义表 ──
CREATE TABLE `sys_custom_field` (
  `id`            bigint       NOT NULL AUTO_INCREMENT,
  `project_id`    bigint       NOT NULL              COMMENT '所属项目',
  `module`        varchar(30)  NOT NULL              COMMENT '模块标识：defect / requirement',
  `view_type`     varchar(10)  NOT NULL              COMMENT '视图：create / edit',
  `field_key`     varchar(50)  NOT NULL              COMMENT '字段唯一标识',
  `field_label`   varchar(50)  NOT NULL              COMMENT '显示标签',
  `field_type`    varchar(20)  NOT NULL              COMMENT '类型：text / select / datetime / number',
  `options_json`  text                               COMMENT '下拉框选项 JSON: [{"label":"高","value":"HIGH"},...]',
  `default_value` varchar(200)          DEFAULT NULL COMMENT '默认值',
  `is_required`   tinyint      NOT NULL DEFAULT 0    COMMENT '是否必填：1=是 0=否',
  `sort_no`       int          NOT NULL DEFAULT 0    COMMENT '排序号（升序）',
  `is_active`     tinyint      NOT NULL DEFAULT 1    COMMENT '是否启用：1=启用 0=停用',
  `created_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_module_view_key` (`project_id`, `module`, `view_type`, `field_key`),
  KEY `idx_custom_field_project` (`project_id`),
  KEY `idx_custom_field_module_view` (`module`, `view_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自定义字段定义表';

-- ── 2. sys_custom_field_value：字段值存储表 ──
CREATE TABLE `sys_custom_field_value` (
  `id`            bigint       NOT NULL AUTO_INCREMENT,
  `field_id`      bigint       NOT NULL              COMMENT '关联 sys_custom_field.id',
  `module`        varchar(30)  NOT NULL              COMMENT '模块标识：defect / requirement',
  `entity_id`     bigint       NOT NULL              COMMENT '业务实体 ID（如 defect.id / requirement_item.id）',
  `field_value`   text                               COMMENT '字段值（文本形式存储）',
  `created_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_field_entity` (`field_id`, `entity_id`),
  KEY `idx_cf_value_entity` (`module`, `entity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自定义字段值表';

-- ── 3. sys_menu：系统管理目录下新增【字段管理】菜单 ──
-- 系统管理 parent_id=2
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `menu_type`, `icon`, `route_path`, `component`, `permission_code`, `sort_no`, `is_active`, `created_at`, `updated_at`) VALUES
(130, 2, '字段管理', 2, '', '/settings/custom-fields', 'settings/CustomFieldView', 'system:custom-field', 8, 1, NOW(), NOW());

-- 按钮级菜单
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `menu_type`, `icon`, `route_path`, `component`, `permission_code`, `sort_no`, `is_active`, `created_at`, `updated_at`) VALUES
(131, 130, '新增字段', 3, NULL, NULL, NULL, 'system:custom-field:add', 1, 1, NOW(), NOW()),
(132, 130, '编辑字段', 3, NULL, NULL, NULL, 'system:custom-field:edit', 2, 1, NOW(), NOW()),
(133, 130, '删除字段', 3, NULL, NULL, NULL, 'system:custom-field:delete', 3, 1, NOW(), NOW());

-- ── 4. permission：与 sys_menu 同步 ──
INSERT INTO `permission` (`id`, `permission_name`, `permission_code`, `type`, `parent_id`, `path`, `sort_order`, `is_active`, `description`, `control_mode`, `created_at`, `updated_at`) VALUES
(141, '字段管理', 'system:custom-field', 'MENU', 2, '/settings/custom-fields', 8, 1, '字段管理页面', 'display', NOW(), NOW()),
(142, '新增字段', 'system:custom-field:add', 'BUTTON', 141, NULL, 1, 1, '新增自定义字段按钮', 'display', NOW(), NOW()),
(143, '编辑字段', 'system:custom-field:edit', 'BUTTON', 141, NULL, 2, 1, '编辑自定义字段按钮', 'display', NOW(), NOW()),
(144, '删除字段', 'system:custom-field:delete', 'BUTTON', 141, NULL, 3, 1, '删除自定义字段按钮', 'display', NOW(), NOW());

-- ── 5. role_permission：SUPER_ADMIN（role_id=1）全部分配 ──
INSERT INTO `role_permission` (`role_id`, `permission_id`, `control_mode`, `created_at`) VALUES
(1, 141, NULL, NOW()),
(1, 142, 'enabled', NOW()),
(1, 143, 'enabled', NOW()),
(1, 144, 'enabled', NOW());
