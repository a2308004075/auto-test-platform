-- 删除【缓存管理】功能：移除系统管理目录下的缓存管理菜单及其按钮（sys_menu id=9/58/59）
-- 依据 permission_code 定位，避免依赖具体主键 ID
DELETE FROM `sys_menu` WHERE `permission_code` IN ('system:cache', 'system:cache:set', 'system:cache:delete');

-- 删除缓存管理相关权限定义（permission id=30/46/47）
-- role_permission 存在 ON DELETE CASCADE 外键，角色已分配的关联记录随之自动清理
DELETE FROM `permission` WHERE `permission_code` IN ('system:cache', 'system:cache:set', 'system:cache:delete');
