/**
 * @author HXN
 * @date 2026-09-22
 * @description 手动用例附件 Mapper
 */
package com.platform.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.execution.entity.ManualCaseAttachment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 手动用例附件数据访问层
 */
@Mapper
public interface ManualCaseAttachmentMapper extends BaseMapper<ManualCaseAttachment> {
}
