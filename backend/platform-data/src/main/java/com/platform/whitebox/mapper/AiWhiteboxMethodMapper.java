/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试变更方法 Mapper
 */
package com.platform.whitebox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.whitebox.entity.AiWhiteboxMethod;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 白盒测试变更方法数据访问层
 */
@Mapper
public interface AiWhiteboxMethodMapper extends BaseMapper<AiWhiteboxMethod> {
}
