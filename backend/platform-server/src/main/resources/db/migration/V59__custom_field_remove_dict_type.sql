-- =====================================================================
-- V59: 移除【字段管理】"字典"类型 —— 存量 dict 字段转换为普通下拉框
--   1) 默认值换算：字典项名称 → 新编号（须在 source_code 清空前执行）
--   2) 选项物化：显示文本=字典项名称，存储值按字典项顺序编号 1、2、3…
--   3) 字段类型 dict → select，source_code 清空
-- 说明：sys_dict 表及数据保留（角色字典同步、公开字典查询接口仍使用）
-- =====================================================================

-- ── 1. 默认值换算：字典项名称 → 新编号 ──
UPDATE `sys_custom_field` f
JOIN (
  SELECT t.dict_type, t.dict_value_name,
         CAST(ROW_NUMBER() OVER (PARTITION BY t.dict_type ORDER BY t.sort_no, t.id) AS CHAR) AS value_no
  FROM `sys_dict` t
  WHERE t.is_active = 1
) m ON f.source_code = m.dict_type AND f.default_value = m.dict_value_name
SET f.default_value = m.value_no
WHERE f.field_type = 'dict';

-- ── 2. 选项物化 + 类型转换 ──
UPDATE `sys_custom_field` f
JOIN (
  SELECT d.dict_type,
         CONCAT('[', GROUP_CONCAT(JSON_OBJECT('label', d.dict_value_name, 'value', d.value_no) ORDER BY d.sort_no, d.id SEPARATOR ','), ']') AS options_json
  FROM (
    SELECT t.dict_type, t.dict_value_name, t.sort_no, t.id,
           CAST(ROW_NUMBER() OVER (PARTITION BY t.dict_type ORDER BY t.sort_no, t.id) AS CHAR) AS value_no
    FROM `sys_dict` t
    WHERE t.is_active = 1
  ) d
  GROUP BY d.dict_type
) o ON f.source_code = o.dict_type
SET f.field_type = 'select',
    f.options_json = o.options_json,
    f.source_code = NULL
WHERE f.field_type = 'dict';

-- ── 3. 兜底：字典项缺失的 dict 字段转为空选项下拉框 ──
UPDATE `sys_custom_field`
SET `field_type`    = 'select',
    `options_json`  = '[]',
    `default_value` = NULL,
    `source_code`   = NULL
WHERE `field_type` = 'dict';
