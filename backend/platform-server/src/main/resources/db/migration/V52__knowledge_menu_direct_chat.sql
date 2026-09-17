-- =====================================================================
-- V52: 知识库菜单直达智能问答
-- 知识库数据来源于项目资料自动同步（V51），不再提供文档管理入口；
-- 菜单 component 指向智能问答页，权限码对齐问答权限
-- =====================================================================

UPDATE `sys_menu`
SET `component` = 'knowledge/KnowledgeChat',
    `permission_code` = 'project:knowledge:chat'
WHERE `id` = 127
  AND `route_path` = '/project/:id/knowledge';
