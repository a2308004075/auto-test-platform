/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试生成测试实体
 */
package com.platform.whitebox.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 白盒测试生成测试实体
 *
 * <p>对应数据库 ai_whitebox_test 表。仅含 created_at，无 updated_at。</p>
 */
@Data
@TableName("ai_whitebox_test")
public class AiWhiteboxTest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 所属任务 ID
     */
    private Long taskId;

    /**
     * 关联变更方法 ID
     */
    private Long methodId;

    /**
     * 测试类完整名
     */
    private String testClassName;

    /**
     * 测试方法名
     */
    private String testMethodName;

    /**
     * 用例标题
     */
    private String caseTitle;

    /**
     * 用例类型：NORMAL-正常，EXCEPTION-异常
     */
    private String caseType;

    /**
     * 优先级：高/中/低
     */
    private String priority;

    /**
     * 前置条件
     */
    private String preconditions;

    /**
     * 操作步骤（换行分隔）
     */
    private String operationSteps;

    /**
     * 预期结果
     */
    private String expectedResult;

    /**
     * 关联需求条目 ID（JSON 数组）
     */
    private String relatedRequirementIds;

    /**
     * 对应 JUnit 测试方法代码
     */
    private String testCode;

    /**
     * 编译状态：PENDING/PASSED/FAILED
     */
    private String compileStatus;

    /**
     * 执行状态：PENDING/PASSED/FAILED/SKIPPED
     */
    private String execStatus;

    /**
     * 执行失败信息（断言/异常摘要）
     */
    private String execMessage;

    /**
     * 是否已保存到手动用例库：0-否，1-是
     */
    private Integer savedToManual;

    /**
     * 保存后的手动用例 ID
     */
    private Long manualCaseId;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
