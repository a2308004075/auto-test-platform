/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库对话会话 Mapper
 */
package com.platform.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.knowledge.entity.KnowledgeConversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeConversationMapper extends BaseMapper<KnowledgeConversation> {
}
