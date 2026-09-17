/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试保存用例请求 DTO
 */
package com.platform.whitebox.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.io.Serializable;
import java.util.List;

/**
 * 保存生成用例到手动用例库请求
 */
@Data
public class WhiteboxSaveTestsRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待保存的生成测试 ID 列表
     */
    @NotEmpty(message = "请至少选择一个要保存的用例")
    private List<Long> testIds;
}
