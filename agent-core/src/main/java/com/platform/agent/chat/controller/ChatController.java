package com.platform.agent.chat.controller;

import com.platform.agent.chat.dto.ConversationVO;
import com.platform.agent.chat.dto.MemoryVO;
import com.platform.agent.chat.entity.ChatConversation;
import com.platform.agent.chat.entity.UserMemory;
import com.platform.agent.chat.mapper.ChatConversationMapper;
import com.platform.agent.chat.mapper.ChatMessageMapper;
import com.platform.agent.chat.mapper.UserMemoryMapper;
import com.platform.agent.chat.service.MemoryService;
import com.platform.agent.chat.service.MemorySpaceService;
import com.platform.agent.rag.dto.RagReference;
import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.core.util.JsonUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话与记忆管理 — /api/v1/llm/conversations*、/memories*
 * 归属校验模式照抄 RagService.getOwnedDataset（不符抛 NO_PERMISSION）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class ChatController {

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final UserMemoryMapper memoryMapper;
    private final MemoryService memoryService;
    private final MemorySpaceService memorySpaceService;

    // ==================== 会话 ====================

    @GetMapping("/conversations")
    public Result<PageResult<ConversationVO>> listConversations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ChatConversation> mpPage = new Page<>(page, size);
        Page<ChatConversation> result = conversationMapper.selectPage(mpPage,
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getUserId, UserContext.getUserId())
                        .eq(ChatConversation::getTenantId, TenantContext.get())
                        .orderByDesc(ChatConversation::getUpdatedAt));
        List<ConversationVO> vOs = result.getRecords().stream().map(c -> {
            ConversationVO vo = new ConversationVO();
            vo.setId(c.getId());
            vo.setTitle(c.getTitle());
            vo.setProvider(c.getProvider());
            vo.setDatasetId(c.getDatasetId());
            vo.setSpaceId(c.getSpaceId());
            vo.setSystemPrompt(c.getSystemPrompt());
            vo.setMessageCount(c.getMessageCount());
            vo.setCreatedAt(c.getCreatedAt());
            vo.setUpdatedAt(c.getUpdatedAt());
            return vo;
        }).toList();
        return Result.ok(PageResult.of(vOs, page, size, result.getTotal()));
    }

    @PostMapping("/conversations")
    public Result<ConversationVO> createConversation(@RequestBody Map<String, Object> request) {
        String userId = UserContext.getUserId();
        String tenantId = TenantContext.get();
        // 显式指定则校验归属，否则落到用户默认空间（与 chat 提交时的解析规则一致）
        String requestSpaceId = request.get("spaceId") == null ? null : String.valueOf(request.get("spaceId"));
        String spaceId = memorySpaceService.resolveSpaceId(userId, tenantId, requestSpaceId, null);

        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        conversation.setTenantId(tenantId);
        conversation.setTitle((String) request.getOrDefault("title", "新对话"));
        conversation.setProvider((String) request.getOrDefault("provider", "deepseek"));
        conversation.setDatasetId((String) request.get("datasetId"));
        conversation.setSpaceId(spaceId);
        conversation.setSystemPrompt((String) request.get("systemPrompt"));
        conversation.setMessageCount(0);
        conversationMapper.insert(conversation);
        return Result.ok(toVO(conversation));
    }

    @PatchMapping("/conversations/{id}")
    public Result<ConversationVO> renameConversation(@PathVariable String id, @RequestBody Map<String, Object> request) {
        ChatConversation conversation = getOwnedConversation(id);
        String title = (String) request.get("title");
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "title 不能为空");
        }
        conversation.setTitle(title.trim());
        conversation.setUpdatedAt(java.time.LocalDateTime.now());
        conversationMapper.updateById(conversation);
        return Result.ok(toVO(conversation));
    }

    /** 删除会话（事务级联删消息；不删记忆——记忆是跨会话资产，source_conversation_id 仅溯源） */
    @DeleteMapping("/conversations/{id}")
    @Transactional
    public Result<Void> deleteConversation(@PathVariable String id) {
        getOwnedConversation(id);
        messageMapper.delete(new LambdaQueryWrapper<com.platform.agent.chat.entity.ChatMessage>()
                .eq(com.platform.agent.chat.entity.ChatMessage::getConversationId, id));
        conversationMapper.deleteById(id);
        log.info("Chat conversation deleted: id={}", id);
        return Result.ok(null);
    }

    @GetMapping("/conversations/{id}/messages")
    public Result<PageResult<Map<String, Object>>> listMessages(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        getOwnedConversation(id);
        Page<com.platform.agent.chat.entity.ChatMessage> mpPage = new Page<>(page, size);
        Page<com.platform.agent.chat.entity.ChatMessage> result = messageMapper.selectPage(mpPage,
                new LambdaQueryWrapper<com.platform.agent.chat.entity.ChatMessage>()
                        .eq(com.platform.agent.chat.entity.ChatMessage::getConversationId, id)
                        .orderByAsc(com.platform.agent.chat.entity.ChatMessage::getCreatedAt));
        List<Map<String, Object>> records = result.getRecords().stream().map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("role", m.getRole());
            map.put("content", m.getContent());
            map.put("references", parseReferences(m.getReferencesJson()));
            map.put("createdAt", m.getCreatedAt());
            return map;
        }).toList();
        return Result.ok(PageResult.of(records, page, size, result.getTotal()));
    }

    // ==================== 记忆 ====================

    @GetMapping("/memories")
    public Result<PageResult<MemoryVO>> listMemories(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String spaceId) {
        String userId = UserContext.getUserId();
        String tenantId = TenantContext.get();
        // 不传 spaceId 时回落到默认空间（存量前端/老调用方行为不变）
        String effectiveSpaceId = memorySpaceService.resolveSpaceId(userId, tenantId, spaceId, null);

        LambdaQueryWrapper<UserMemory> wrapper = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getTenantId, tenantId)
                .eq(UserMemory::getSpaceId, effectiveSpaceId)
                .eq(UserMemory::getDeleted, 0)   // 软删记忆不出现在管理列表
                .orderByDesc(UserMemory::getCreatedAt);
        if (category != null && !category.isBlank()) {
            if (!UserMemory.CATEGORY_LONG_TERM.equals(category)
                    && !UserMemory.CATEGORY_SHORT_TERM.equals(category)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "category 仅支持 LONG_TERM|SHORT_TERM");
            }
            wrapper.eq(UserMemory::getCategory, category);
        }
        Page<UserMemory> result = memoryMapper.selectPage(new Page<>(page, size), wrapper);
        List<MemoryVO> vOs = result.getRecords().stream().map(m -> {
            MemoryVO vo = new MemoryVO();
            vo.setId(m.getId());
            vo.setContent(m.getContent());
            vo.setMemoryType(m.getMemoryType());
            vo.setCategory(m.getCategory());
            vo.setSpaceId(m.getSpaceId());
            vo.setSourceConversationId(m.getSourceConversationId());
            vo.setCreatedAt(m.getCreatedAt());
            return vo;
        }).toList();
        return Result.ok(PageResult.of(vOs, page, size, result.getTotal()));
    }

    @DeleteMapping("/memories/{id}")
    public Result<Void> deleteMemory(@PathVariable String id) {
        UserMemory memory = memoryMapper.selectById(id);
        if (memory == null) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND);
        }
        if (!memory.getUserId().equals(UserContext.getUserId())
                || !memory.getTenantId().equals(TenantContext.get())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // 软删：行保留但标记 deleted=1 —— 列表/检索不再可见，
        // 同时提取去重（含软删记录）能阻止同内容再次被提取"复活"（假删除根因）
        UserMemory update = new UserMemory();
        update.setId(id);
        update.setDeleted(1);
        memoryMapper.updateById(update);
        return Result.ok(null);
    }

    /**
     * 手动添加记忆（记忆管理页）：content 必填，memoryType 缺省 FACT。
     * 固定写入长期板块（短期由自动提取/「记住 X」指令生成，管理页不提供手动短期入口）；请求体 category 忽略。
     */
    @PostMapping("/memories")
    public Result<Void> createMemory(@RequestBody Map<String, Object> request) {
        String content = request.get("content") == null ? null : String.valueOf(request.get("content")).trim();
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "记忆内容不能为空");
        }
        String type = request.get("memoryType") == null ? "FACT" : String.valueOf(request.get("memoryType"));
        String userId = UserContext.getUserId();
        String tenantId = TenantContext.get();
        // 归属空间：显式指定则校验归属，否则落到默认空间
        String spaceId = memorySpaceService.resolveSpaceId(userId, tenantId,
                request.get("spaceId") == null ? null : String.valueOf(request.get("spaceId")), null);
        String result = memoryService.saveManual(userId, tenantId, spaceId, content, type,
                UserMemory.CATEGORY_LONG_TERM);
        if ("DUPLICATE".equals(result)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该空间内已存在相同内容的记忆");
        }
        return Result.ok(null);
    }

    /** 编辑记忆（内容/类型变更，embedding 重算） */
    @PutMapping("/memories/{id}")
    public Result<Void> updateMemory(@PathVariable String id, @RequestBody Map<String, Object> request) {
        String content = request.get("content") == null ? null : String.valueOf(request.get("content")).trim();
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "记忆内容不能为空");
        }
        String type = request.get("memoryType") == null ? "FACT" : String.valueOf(request.get("memoryType"));
        memoryService.updateMemory(UserContext.getUserId(), TenantContext.get(), id, content, type);
        return Result.ok(null);
    }

    // ==================== 私有 ====================

    private ChatConversation getOwnedConversation(String conversationId) {
        ChatConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "会话不存在: " + conversationId);
        }
        if (!conversation.getUserId().equals(UserContext.getUserId())
                || !conversation.getTenantId().equals(TenantContext.get())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return conversation;
    }

    private ConversationVO toVO(ChatConversation c) {
        ConversationVO vo = new ConversationVO();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setProvider(c.getProvider());
        vo.setDatasetId(c.getDatasetId());
        vo.setSpaceId(c.getSpaceId());
        vo.setSystemPrompt(c.getSystemPrompt());
        vo.setMessageCount(c.getMessageCount());
        vo.setCreatedAt(c.getCreatedAt());
        vo.setUpdatedAt(c.getUpdatedAt());
        return vo;
    }

    /** references_json JSON 反序列化还原为 RagReference[]；空或解析失败返回空列表 */
    private List<RagReference> parseReferences(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JsonUtils.fromJson(json, RagReference[].class) == null
                    ? List.of()
                    : List.of(JsonUtils.fromJson(json, RagReference[].class));
        } catch (Exception e) {
            log.debug("Message references parse failed: {}", e.getMessage());
            return List.of();
        }
    }
}
