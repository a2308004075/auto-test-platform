/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库对话控制器
 */
package com.platform.knowledge.controller;

import com.platform.common.response.ApiResponse;
import com.platform.knowledge.dto.ChatMessageResponse;
import com.platform.knowledge.dto.ChatRequest;
import com.platform.knowledge.dto.ConversationResponse;
import com.platform.knowledge.service.KnowledgeConversationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.validation.Valid;
import java.util.List;

/**
 * 知识库对话控制器
 *
 * <p>接口路径：{@code /api/v1/projects/{projectId}/knowledge/{kbId}/chat}</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/knowledge/{kbId}/chat")
public class KnowledgeChatController {

    private final KnowledgeConversationService convService;

    public KnowledgeChatController(KnowledgeConversationService convService) {
        this.convService = convService;
    }

    /**
     * 查询知识库下的会话列表
     */
    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('project:knowledge:chat')")
    public ApiResponse<List<ConversationResponse>> listConversations(@PathVariable Long projectId,
                                                                       @PathVariable Long kbId) {
        return ApiResponse.ok(convService.listConversations(kbId));
    }

    /**
     * 查询会话的消息列表
     */
    @GetMapping("/conversations/{conversationId}/messages")
    @PreAuthorize("hasAuthority('project:knowledge:chat')")
    public ApiResponse<List<ChatMessageResponse>> listMessages(@PathVariable Long projectId,
                                                                 @PathVariable Long kbId,
                                                                 @PathVariable Long conversationId) {
        return ApiResponse.ok(convService.listMessages(conversationId));
    }

    /**
     * 发送消息（流式 SSE）
     */
    @PostMapping("/stream")
    @PreAuthorize("hasAuthority('project:knowledge:chat')")
    public SseEmitter sendMessageStream(@PathVariable Long projectId,
                                          @PathVariable Long kbId,
                                          @Valid @RequestBody ChatRequest request) {
        return convService.sendMessageStream(kbId, request);
    }

    /**
     * 创建新会话
     */
    @PostMapping("/conversations")
    @PreAuthorize("hasAuthority('project:knowledge:chat')")
    public ApiResponse<ConversationResponse> createConversation(@PathVariable Long projectId,
                                                                  @PathVariable Long kbId,
                                                                  @RequestParam(required = false) String title) {
        return ApiResponse.ok(convService.createConversation(kbId, title));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/conversations/{conversationId}")
    @PreAuthorize("hasAuthority('project:knowledge:chat')")
    public ApiResponse<Void> deleteConversation(@PathVariable Long projectId,
                                                  @PathVariable Long kbId,
                                                  @PathVariable Long conversationId) {
        convService.deleteConversation(conversationId);
        return ApiResponse.ok();
    }
}
