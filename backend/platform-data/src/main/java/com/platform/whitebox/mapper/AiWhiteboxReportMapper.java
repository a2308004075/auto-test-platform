/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试报告 Mapper
 */
package com.platform.whitebox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.whitebox.entity.AiWhiteboxReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 白盒测试报告数据访问层
 */
@Mapper
public interface AiWhiteboxReportMapper extends BaseMapper<AiWhiteboxReport> {
}
