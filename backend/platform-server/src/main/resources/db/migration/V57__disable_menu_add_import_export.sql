-- 菜单管理移除"新增顶级菜单/导入/导出"功能：软删除对应按钮级菜单与权限定义
-- 依据 permission_code 定位，避免依赖具体主键 ID；is_active=0 走 @TableLogic 逻辑删除，
-- 菜单树（tree() 仅返回 is_active=1）与权限下发（selectPermissionCodesByRoleId 过滤 p.is_active=1）均自动隐藏

-- sys_menu：菜单管理下的三条按钮记录（新增顶级菜单/导入/导出，V1 基线 id=67/68/69）
UPDATE `sys_menu` SET `is_active` = 0
WHERE `permission_code` IN ('system:menu:add', 'system:menu:import', 'system:menu:export')
  AND `is_active` = 1;

-- permission：对应按钮权限停用（V1 基线 id=35/39/40；角色已分配的 role_permission 关联保留不动）
UPDATE `permission` SET `is_active` = 0
WHERE `permission_code` IN ('system:menu:add', 'system:menu:import', 'system:menu:export')
  AND `is_active` = 1;
