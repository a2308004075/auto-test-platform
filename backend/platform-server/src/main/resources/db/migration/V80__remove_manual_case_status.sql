-- =====================================================================
-- V80: 手动化用例模块删除【状态】功能（全栈下线，保留 manual_case.case_status 数据列）
-- =====================================================================
-- 用户需求：手动化用例删除「状态」（使用/废弃）
-- 1. 【页面配置-手动用例字段】的「状态」字段配置：软删除（is_active=0），
--    与【页面配置】删除按钮同语义（行保留可查；逻辑删除自动过滤，不再参与渲染/筛选/排序）
-- 2. 删除「启停用例」按钮权限（sys_menu + permission；角色关联由外键级联/显式清理）
-- 3. manual_case.case_status 列保留停止读写，存量数据不删（与其他 4 个存量列策略一致）
-- 说明：状态值一直走业务列不存 sys_custom_field_value，无值行需清理（已核对为 0 行）

-- ── 1. 「状态」字段配置软删除 ──
UPDATE `sys_custom_field`
SET `is_active` = 0
WHERE `module` = 'manual_case'
  AND `field_key` = 'case_status'
  AND `is_active` = 1;

-- ── 2. 删除「启停用例」按钮权限（依据 permission_code 定位，避免依赖具体主键 ID） ──
-- role_permission 存在 ON DELETE CASCADE 外键，此处先显式清理以保证可重复执行
DELETE FROM `role_permission`
WHERE `permission_id` IN (SELECT `id` FROM `permission` WHERE `permission_code` = 'project:manual-case:toggle');

DELETE FROM `sys_menu` WHERE `permission_code` = 'project:manual-case:toggle';

DELETE FROM `permission` WHERE `permission_code` = 'project:manual-case:toggle';
