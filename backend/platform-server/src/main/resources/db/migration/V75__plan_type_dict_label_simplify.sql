-- V75: 计划类型字典文案简化（"自动测试计划"→"自动"、"手动测试计划"→"手动"）
-- 与前端【新建计划】下拉、列表"计划类型"列 tag、筛选下拉的显示统一
-- 幂等：仅当文案仍为旧值时更新，重复执行不破坏用户自定义文案

UPDATE `sys_dict`
SET `dict_value_name` = '自动'
WHERE `dict_type` = 'plan_type'
  AND `dict_value` = 'AUTO'
  AND `dict_value_name` = '自动测试计划';

UPDATE `sys_dict`
SET `dict_value_name` = '手动'
WHERE `dict_type` = 'plan_type'
  AND `dict_value` = 'MANUAL'
  AND `dict_value_name` = '手动测试计划';
