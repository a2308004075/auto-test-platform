-- =====================================================================
-- V78: 手动计划执行工作台（计划级结果列 + 多轮结果记录）
--
-- 背景：手动计划的"执行"语义改造为独立执行页：
--   1. 点击执行直接进入执行页（进行中执行单 WAITING_MANUAL）；
--   2. 测试人员在执行页按计划自定义"结果列"（如：第一次台架测试结果、
--      第二次整站测试结果……），列定义挂在计划级、历次执行共享；
--   3. 每条用例行 × 每个结果列的单元格记录 结果下拉（通过/失败/跳过）+ 备注；
--   4. 点击"执行完成"后快照列定义与各格结果，执行单置 COMPLETED，
--      归档形成一条【测试记录】。
--
-- 变更：
--   1. 新表 plan_result_column：计划级自定义结果列定义（列名计划内唯一）；
--   2. test_result 新增 round_results JSON 列：多轮结果单元格值
--      {"<列ID>": {"status": "PASSED|FAILED|SKIPPED", "remark": "文本备注"}}；
--   3. test_execution 新增 result_columns JSON 列：执行完成时的列定义快照
--      [{"id": 1, "columnName": "第一次台架测试结果", "sortNo": 1}]，
--      供测试记录详情页脱离 plan_result_column 稳定回放（列定义后续改名/
--      删除不影响已归档记录的展示）。
--
-- 无存量数据改写，纯增量 DDL，幂等安全性由 Flyway 单次执行保证。
-- =====================================================================

-- ── 1. 计划级自定义结果列定义表 ──
CREATE TABLE `plan_result_column` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `plan_id` bigint NOT NULL COMMENT '所属测试计划 ID',
  `column_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '列名称（用户自定义，如：第一次台架测试结果）',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '列顺序（添加时取当前计划最大值+1，上移/下移相邻交换）',
  `created_by` bigint DEFAULT NULL COMMENT '创建人 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_result_column` (`plan_id`, `column_name`),
  KEY `idx_plan_result_column_plan_id` (`plan_id`),
  CONSTRAINT `fk_plan_result_column_plan_id` FOREIGN KEY (`plan_id`) REFERENCES `test_plan` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_plan_result_column_created_by` FOREIGN KEY (`created_by`) REFERENCES `user` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计划级自定义测试结果列定义表';

-- ── 2. 测试结果明细表新增多轮结果 JSON 列 ──
ALTER TABLE `test_result`
    ADD COLUMN `round_results` json DEFAULT NULL COMMENT '多轮测试结果单元格值：{"<结果列ID>":{"status":"PASSED|FAILED|SKIPPED","remark":"备注"}}';

-- ── 3. 测试执行记录表新增完成时的列定义快照 JSON 列 ──
ALTER TABLE `test_execution`
    ADD COLUMN `result_columns` json DEFAULT NULL COMMENT '执行完成时的结果列定义快照：[{"id":1,"columnName":"第一次台架测试结果","sortNo":1}]';
