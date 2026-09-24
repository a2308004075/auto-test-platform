-- V76: 取消【测试计划-关联用例】预置字段（台架是否执行/整站是否执行）
-- 用户需求：测试计划关联页不再显示这两列，在【页面配置】取消相关配置
-- 与【页面配置】删除按钮同语义：软删除（is_active=0），行保留可查
-- （新建字段 fieldKey 为 UUID 不会与软删行唯一键冲突；已保存字段值当前 0 行，无需清理）

UPDATE `sys_custom_field`
SET `is_active` = 0
WHERE `module` = 'plan_case'
  AND `field_key` IN ('run_on_bench', 'run_on_site')
  AND `is_active` = 1;
