-- =====================================================================
-- V79: 新增 AI 服务提供商切换（Qoder Cloud Agents 备选）
--
-- 背景：平台 AI 对话能力（知识库问答、白盒用例生成等）原仅支持
--   阿里云百炼（OpenAI 兼容模式，knowledge.llm 配置）；本次增加
--   Qoder Cloud Agents API 作为备选提供商：
--   global_settings 新增 ai.provider 开关（bailian/qoder），
--   前端【系统设置-AI 服务】页切换，运行时生效；连接凭据均
--   在后端 yml 配置（ai.qoder 段），不入库。
--
-- 变更：
--   1. global_settings 插入 ai.provider 开关（幂等）；
--   2. sys_menu 在系统管理下新增【AI 服务】菜单（id=138）；
--   3. permission 同步新增 1 条 MENU 权限（id=149）；
--   4. role_permission：ADMIN 分配（TESTER 不开放配置页）。
-- =====================================================================

-- ── 1. global_settings：AI 提供商切换开关（幂等插入） ──
INSERT INTO `global_settings` (`config_key`, `config_value`, `description`, `updated_at`)
SELECT 'ai.provider', 'bailian', 'AI 对话提供商切换：bailian-阿里云百炼，qoder-Qoder Cloud Agents', NOW()
WHERE NOT EXISTS (SELECT 1 FROM `global_settings` WHERE `config_key` = 'ai.provider');

-- ── 2. sys_menu：AI 服务（系统管理子菜单，排在页面配置之后） ──
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `menu_type`, `icon`, `route_path`, `component`, `permission_code`, `sort_no`, `is_active`, `created_at`, `updated_at`) VALUES
(138, 2, 'AI 服务', 2, '', '/settings/ai-provider', 'settings/AiProviderView', 'system:ai-provider', 9, 1, NOW(), NOW());

-- ── 3. permission：与 sys_menu 同步 ──
INSERT INTO `permission` (`id`, `permission_name`, `permission_code`, `type`, `parent_id`, `path`, `sort_order`, `is_active`, `description`, `control_mode`, `created_at`, `updated_at`) VALUES
(149, 'AI 服务', 'system:ai-provider', 'MENU', 2, '/settings/ai-provider', 9, 1, 'AI 服务提供商配置页面', 'display', NOW(), NOW());

-- ── 4. role_permission：ADMIN 分配（TESTER 不开放提供商配置页） ──
INSERT INTO `role_permission` (`role_id`, `permission_id`, `control_mode`, `created_at`) VALUES
(1, 149, NULL, NOW());
