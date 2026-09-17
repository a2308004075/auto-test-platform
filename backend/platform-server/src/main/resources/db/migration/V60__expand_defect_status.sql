-- =====================================================================
-- V60: 缺陷状态扩展为 9 个状态
-- NEW-新建 / TO_CONFIRM-待确认 / FIXING-修复中 / TO_DEPLOY-待部署 / PENDING-待验证
-- COMPLETED-已修复（原"已完成"改名）/ REOPENED-重新打开 / DEFERRED-延期修复
-- CLOSED-无需修复（原"已关闭"改名）
-- 说明：复用 COMPLETED/CLOSED 原编码，仅更新名称与排序，存量数据无需迁移
-- =====================================================================

-- 1. 存量状态改名与重排序（按目标顺序：新建1/待确认2/修复中3/待部署4/待验证5/已修复6/重新打开7/延期修复8/无需修复9）
UPDATE `sys_dict` SET `dict_value_name` = '已修复', `remark` = '已修复', `sort_no` = 6, `updated_at` = NOW()
WHERE `dict_type` = 'defect_status' AND `dict_value` = 'COMPLETED';

UPDATE `sys_dict` SET `dict_value_name` = '无需修复', `remark` = '无需修复', `sort_no` = 9, `updated_at` = NOW()
WHERE `dict_type` = 'defect_status' AND `dict_value` = 'CLOSED';

UPDATE `sys_dict` SET `sort_no` = 5, `updated_at` = NOW()
WHERE `dict_type` = 'defect_status' AND `dict_value` = 'PENDING';

UPDATE `sys_dict` SET `sort_no` = 7, `updated_at` = NOW()
WHERE `dict_type` = 'defect_status' AND `dict_value` = 'REOPENED';

-- 2. 新增 4 个状态
INSERT INTO `sys_dict` (`dict_type`, `dict_type_name`, `dict_value`, `dict_value_name`, `sort_no`, `remark`, `is_active`, `created_at`, `updated_at`) VALUES
('defect_status', '缺陷状态', 'TO_CONFIRM', '待确认', 2, '待确认', 1, NOW(), NOW()),
('defect_status', '缺陷状态', 'FIXING', '修复中', 3, '修复中', 1, NOW(), NOW()),
('defect_status', '缺陷状态', 'TO_DEPLOY', '待部署', 4, '待部署', 1, NOW(), NOW()),
('defect_status', '缺陷状态', 'DEFERRED', '延期修复', 8, '延期修复', 1, NOW(), NOW());

-- 3. 更新 defect.status 列注释（仅注释变更，无数据丢失）
ALTER TABLE `defect`
  MODIFY COLUMN `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NEW'
  COMMENT '状态：NEW-新建,TO_CONFIRM-待确认,FIXING-修复中,TO_DEPLOY-待部署,PENDING-待验证,COMPLETED-已修复,REOPENED-重新打开,DEFERRED-延期修复,CLOSED-无需修复';
