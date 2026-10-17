package com.platform.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.agent.chat.entity.MemorySpace;
import com.platform.agent.chat.entity.UserMemory;
import com.platform.agent.chat.mapper.MemorySpaceMapper;
import com.platform.agent.chat.mapper.UserMemoryMapper;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 记忆空间服务 —— 空间的增删改查 + 默认空间解析。
 * <p>
 * 核心约定：
 * <ol>
 *   <li>每个用户在自己的租户下自动拥有一个默认空间（{@link MemorySpace#DEFAULT_NAME}），首次访问时懒创建；</li>
 *   <li>对话创建时绑定空间，<b>创建后不可改</b>——中途换空间会导致上下文撕裂，要换就新建对话；</li>
 *   <li>删除空间 = 软删空间 + 级联软删其下记忆（沿用 user_memory.deleted 语义，防提取"复活"）；</li>
 *   <li>默认空间不可删除、不可重命名。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemorySpaceService {

    private final MemorySpaceMapper spaceMapper;
    private final UserMemoryMapper memoryMapper;

    // ==================== 默认空间解析（全链路入口） ====================

    /**
     * 取用户的默认空间 ID；不存在则创建。
     * <p>
     * 这是「存量兼容」的关键：老对话 {@code space_id} 为 NULL、老记忆 {@code space_id} 为空串时，
     * 都解析到这里返回的默认空间，行为与改造前完全一致。
     */
    public String resolveDefaultSpaceId(String userId, String tenantId) {
        List<MemorySpace> found = spaceMapper.selectList(new LambdaQueryWrapper<MemorySpace>()
                .eq(MemorySpace::getUserId, userId)
                .eq(MemorySpace::getTenantId, tenantId)
                .eq(MemorySpace::getIsDefault, 1)
                .eq(MemorySpace::getDeleted, 0)
                .last("LIMIT 1"));
        if (!found.isEmpty()) {
            return found.get(0).getId();
        }
        return createDefaultSpace(userId, tenantId);
    }

    /**
     * 解析本次请求生效的空间 ID，按优先级：
     * 显式指定（且归属校验通过）&gt; 会话已绑定 &gt; 用户默认空间。
     *
     * @param explicitSpaceId 请求显式指定的空间（可为空）
     * @param boundSpaceId    会话已绑定的空间（可为空；存量会话为 NULL）
     */
    public String resolveSpaceId(String userId, String tenantId, String explicitSpaceId, String boundSpaceId) {
        if (explicitSpaceId != null && !explicitSpaceId.isBlank()) {
            requireOwnedSpace(explicitSpaceId, userId, tenantId);
            return explicitSpaceId;
        }
        if (boundSpaceId != null && !boundSpaceId.isBlank()) {
            // 绑定的空间可能已被删除 → 回落到默认空间，不让老对话直接报错
            MemorySpace bound = spaceMapper.selectById(boundSpaceId);
            if (bound != null && Integer.valueOf(0).equals(bound.getDeleted())) {
                return boundSpaceId;
            }
            log.info("Bound memory space gone, fallback to default: spaceId={}, user={}", boundSpaceId, userId);
        }
        return resolveDefaultSpaceId(userId, tenantId);
    }

    /** 校验空间归属，不符抛 NO_PERMISSION；不存在抛 PARAM_INVALID */
    public MemorySpace requireOwnedSpace(String spaceId, String userId, String tenantId) {
        MemorySpace space = spaceMapper.selectById(spaceId);
        if (space == null || Integer.valueOf(1).equals(space.getDeleted())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "记忆空间不存在: " + spaceId);
        }
        if (!space.getUserId().equals(userId) || !space.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return space;
    }

    // ==================== CRUD ====================

    /** 列表（默认空间排最前，其余按 sort_order + 创建时间） */
    public List<MemorySpace> list(String userId, String tenantId) {
        // 保证至少有一个默认空间（首次进记忆页也能看到）
        resolveDefaultSpaceId(userId, tenantId);
        return spaceMapper.selectList(new LambdaQueryWrapper<MemorySpace>()
                .eq(MemorySpace::getUserId, userId)
                .eq(MemorySpace::getTenantId, tenantId)
                .eq(MemorySpace::getDeleted, 0)
                .orderByDesc(MemorySpace::getIsDefault)
                .orderByAsc(MemorySpace::getSortOrder)
                .orderByAsc(MemorySpace::getCreatedAt));
    }

    public MemorySpace create(String userId, String tenantId, String name, String description) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "空间名不能为空");
        }
        if (trimmed.length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID, "空间名不能超过 64 字符");
        }
        // 名字重复：唯一索引兜底，这里先给友好提示（并发下靠 DuplicateKeyException 兜）
        Long dup = spaceMapper.selectCount(new LambdaQueryWrapper<MemorySpace>()
                .eq(MemorySpace::getUserId, userId)
                .eq(MemorySpace::getTenantId, tenantId)
                .eq(MemorySpace::getName, trimmed));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "已存在同名记忆空间: " + trimmed);
        }

        MemorySpace space = new MemorySpace();
        space.setUserId(userId);
        space.setTenantId(tenantId);
        space.setName(trimmed);
        space.setDescription(description == null ? "" : description.trim());
        space.setIsDefault(0);
        space.setSortOrder(0);
        space.setDeleted(0);
        try {
            spaceMapper.insert(space);
        } catch (DuplicateKeyException e) {
            // 并发创建同名空间：唯一索引兜底
            throw new BizException(ErrorCode.PARAM_INVALID, "已存在同名记忆空间: " + trimmed);
        }
        log.info("Memory space created: id={}, name={}, user={}", space.getId(), trimmed, userId);
        return space;
    }

    /** 改名 / 改说明（默认空间不可改名，避免「默认」语义丢失） */
    public MemorySpace update(String userId, String tenantId, String spaceId, String name, String description) {
        MemorySpace space = requireOwnedSpace(spaceId, userId, tenantId);
        boolean isDefault = Integer.valueOf(1).equals(space.getIsDefault());

        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            if (trimmed.length() > 64) {
                throw new BizException(ErrorCode.PARAM_INVALID, "空间名不能超过 64 字符");
            }
            if (isDefault) {
                throw new BizException(ErrorCode.PARAM_INVALID, "默认空间不可重命名");
            }
            if (!trimmed.equals(space.getName())) {
                Long dup = spaceMapper.selectCount(new LambdaQueryWrapper<MemorySpace>()
                        .eq(MemorySpace::getUserId, userId)
                        .eq(MemorySpace::getTenantId, tenantId)
                        .eq(MemorySpace::getName, trimmed)
                        .ne(MemorySpace::getId, spaceId));
                if (dup != null && dup > 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "已存在同名记忆空间: " + trimmed);
                }
                space.setName(trimmed);
            }
        }
        if (description != null) {
            space.setDescription(description.trim());
        }
        try {
            spaceMapper.updateById(space);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "已存在同名记忆空间");
        }
        return space;
    }

    /**
     * 删除空间（软删空间 + 级联软删其下全部记忆）。
     * <p>
     * 记忆也软删而非物删：沿用「软删记录参与提取去重」的语义，
     * 避免同一内容被重新提取后在新空间"复活"成重复条目。
     */
    @Transactional
    public void delete(String userId, String tenantId, String spaceId) {
        MemorySpace space = requireOwnedSpace(spaceId, userId, tenantId);
        if (Integer.valueOf(1).equals(space.getIsDefault())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "默认空间不可删除");
        }
        // 1. 空间软删
        MemorySpace update = new MemorySpace();
        update.setId(spaceId);
        update.setDeleted(1);
        spaceMapper.updateById(update);

        // 2. 其下记忆软删（保留行，阻止同内容被重新提取）
        int cascaded = memoryMapper.update(null, new LambdaUpdateWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getTenantId, tenantId)
                .eq(UserMemory::getSpaceId, spaceId)
                .eq(UserMemory::getDeleted, 0)
                .set(UserMemory::getDeleted, 1));
        log.info("Memory space deleted: id={}, cascadedMemories={}, user={}", spaceId, cascaded, userId);
    }

    // ==================== 内部 ====================

    /** 创建默认空间；并发下（唯一索引冲突）回查已存在的 */
    private String createDefaultSpace(String userId, String tenantId) {
        MemorySpace space = new MemorySpace();
        space.setUserId(userId);
        space.setTenantId(tenantId);
        space.setName(MemorySpace.DEFAULT_NAME);
        space.setDescription("系统自动创建，承接改造前的全部记忆");
        space.setIsDefault(1);
        space.setSortOrder(0);
        space.setDeleted(0);
        try {
            spaceMapper.insert(space);
            log.info("Default memory space created: id={}, user={}", space.getId(), userId);
            return space.getId();
        } catch (DuplicateKeyException e) {
            // 并发：另一个线程刚建好，回查
            List<MemorySpace> found = spaceMapper.selectList(new LambdaQueryWrapper<MemorySpace>()
                    .eq(MemorySpace::getUserId, userId)
                    .eq(MemorySpace::getTenantId, tenantId)
                    .eq(MemorySpace::getName, MemorySpace.DEFAULT_NAME)
                    .last("LIMIT 1"));
            if (!found.isEmpty()) {
                return found.get(0).getId();
            }
            throw e;
        }
    }
}
