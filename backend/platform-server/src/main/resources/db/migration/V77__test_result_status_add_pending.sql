-- =============================================================
-- V77: test_result.status 枚举补充 PENDING（待记录）
--
-- 背景：V74 起手动化用例在计划执行前需预创建 PENDING 结果记录，
-- 手动测试计划的执行语义为生成"测试结果记录单"（WAITING_MANUAL），
-- 均需向 test_result.status 写入 PENDING；而原枚举仅有
-- PASSED/FAILED/SKIPPED/ERROR，插入 PENDING 在 MySQL 严格模式下
-- 报 Data truncated for column 'status'，导致执行请求 500。
--
-- 仅扩展枚举取值（PENDING 置于首位，符合状态生命周期顺序），
-- 不改写任何存量数据；MODIFY COLUMN 重复执行结果一致，幂等。
-- =============================================================
ALTER TABLE `test_result`
    MODIFY COLUMN `status` enum('PENDING','PASSED','FAILED','SKIPPED','ERROR')
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
    COMMENT '用例执行结果：PENDING-待记录，PASSED-通过，FAILED-失败，SKIPPED-跳过，ERROR-错误';
