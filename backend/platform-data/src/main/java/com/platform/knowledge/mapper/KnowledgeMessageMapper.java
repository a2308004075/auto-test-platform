/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库对话消息 Mapper
 */
package com.platform.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.knowledge.entity.KnowledgeMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeMessageMapper extends BaseMapper<KnowledgeMessage> {
}
