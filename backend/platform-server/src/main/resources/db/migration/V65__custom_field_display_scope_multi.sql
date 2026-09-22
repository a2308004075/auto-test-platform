-- =====================================================================
-- V65: 【字段管理】"显示位置"由单选枚举改为多值（逗号分隔）
--      both 拆解为 create,detail；列注释与默认值同步更新
-- =====================================================================

-- ── 1. 存量数据：both（都显示）= create + detail，拆解为多值；create/detail 保持不变 ──
UPDATE `sys_custom_field`
SET `display_scope` = 'create,detail'
WHERE `display_scope` = 'both';

-- ── 2. 列定义：扩容至 50（容纳多值）并更新注释与默认值（缺省 = 都显示） ──
ALTER TABLE `sys_custom_field`
  MODIFY COLUMN `display_scope` varchar(50) NOT NULL DEFAULT 'create,detail' COMMENT '显示位置（多值，逗号分隔）：create=新建显示 detail=详情(编辑)显示' AFTER `is_required`;
