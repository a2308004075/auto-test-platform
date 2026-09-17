-- 删除【字典管理】功能：移除系统管理目录下的字典管理菜单及其按钮（sys_menu id=8/60/61/62/63/64）
-- 依据 permission_code 定位，避免依赖具体主键 ID
-- sys_dict 表及字典数据保留，公开字典查询接口 /api/v1/sys/dicts/type/{dictType} 不受影响
DELETE FROM `sys_menu` WHERE `permission_code` IN ('system:dict', 'system:dict:add', 'system:dict:edit', 'system:dict:delete', 'system:dict:import', 'system:dict:export');

-- 删除字典管理相关权限定义（permission id=29/41/42/43/44/45）
-- role_permission 存在 ON DELETE CASCADE 外键，角色已分配的关联记录随之自动清理
DELETE FROM `permission` WHERE `permission_code` IN ('system:dict', 'system:dict:add', 'system:dict:edit', 'system:dict:delete', 'system:dict:import', 'system:dict:export');
