/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试任务启动请求 DTO
 */
package com.platform.whitebox.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 启动 AI 白盒测试任务请求
 *
 * <p>基准 commit 缺省时取该仓库最近一次已完成白盒任务的 HEAD commit；
 * 首次测试（无历史任务）必须显式提供，否则报 WHITEBOX_NO_BASELINE。</p>
 */
@Data
public class WhiteboxStartRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 源代码仓库 ID（code_repository.id）
     */
    @NotNull(message = "源代码仓库不能为空")
    private Long repositoryId;

    /**
     * 需求版本 ID（可选，NULL 表示语义受限模式）
     */
    private Long requirementVersionId;

    /**
     * 基准 commit（可选，留空自动取上次任务 HEAD）
     */
    @Size(max = 64, message = "基准 commit 长度不能超过 64")
    private String baselineCommit;
}
