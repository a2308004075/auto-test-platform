-- =====================================================================
-- V69: 【字段管理】模块更名为【页面配置】— 显示名、路由路径、权限码全量同步
-- =====================================================================
-- 1. 菜单/权限显示名："字段管理" -> "页面配置"
-- 2. 技术标识同步迁移：路由 /settings/custom-fields -> /settings/page-config，
--    权限码 system:custom-field* -> system:page-config*
-- 3. 前端 CustomFieldView.vue 中 hasPermission 判断已同步更新；
--    role_permission 以 permission_id 关联，无需数据处理
-- 4. 按钮级菜单"新增字段/编辑字段/删除字段"名称描述操作对象，保持不变，仅权限码前缀同步

-- ── 1. sys_menu：菜单显示名 / 路由路径 / 权限码 ──
UPDATE `sys_menu` SET `name` = '页面配置', `route_path` = '/settings/page-config', `permission_code` = 'system:page-config', `updated_at` = NOW() WHERE `id` = 130;

UPDATE `sys_menu` SET `permission_code` = 'system:page-config:add', `updated_at` = NOW() WHERE `id` = 131;
UPDATE `sys_menu` SET `permission_code` = 'system:page-config:edit', `updated_at` = NOW() WHERE `id` = 132;
UPDATE `sys_menu` SET `permission_code` = 'system:page-config:delete', `updated_at` = NOW() WHERE `id` = 133;

-- ── 2. permission：与 sys_menu 同步 ──
UPDATE `permission` SET `permission_name` = '页面配置', `permission_code` = 'system:page-config', `path` = '/settings/page-config', `description` = '页面配置页面', `updated_at` = NOW() WHERE `permission_code` = 'system:custom-field';

UPDATE `permission` SET `permission_code` = 'system:page-config:add', `updated_at` = NOW() WHERE `permission_code` = 'system:custom-field:add';
UPDATE `permission` SET `permission_code` = 'system:page-config:edit', `updated_at` = NOW() WHERE `permission_code` = 'system:custom-field:edit';
UPDATE `permission` SET `permission_code` = 'system:page-config:delete', `updated_at` = NOW() WHERE `permission_code` = 'system:custom-field:delete';
