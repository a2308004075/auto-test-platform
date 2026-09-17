/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试生成测试 Mapper
 */
package com.platform.whitebox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.whitebox.entity.AiWhiteboxTest;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 白盒测试生成测试数据访问层
 */
@Mapper
public interface AiWhiteboxTestMapper extends BaseMapper<AiWhiteboxTest> {
}
