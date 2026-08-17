package com.platform.agent.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.asset.entity.Asset;
import com.platform.agent.asset.entity.AssetTaskRelation;
import com.platform.agent.asset.mapper.AssetMapper;
import com.platform.agent.asset.mapper.AssetTaskRelationMapper;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.mapper.AgentTaskMapper;
import com.platform.agent.modelcap.entity.ModelCapability;
import com.platform.agent.modelcap.mapper.ModelCapabilityMapper;
import com.platform.core.base.Result;
import com.platform.core.util.JsonUtils;
import com.platform.feign.StorageFeignClient;
import com.platform.feign.dto.FileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 资产归档服务 —— 任务完成后把产出文件归档为资产 + 估算成本回写。
 * 幂等：按 (task_id, OUTPUT) 查重；归档失败不阻塞任务主流程（兜底接口可重试）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetArchiveService {

    private final AssetMapper assetMapper;
    private final AssetTaskRelationMapper assetTaskRelationMapper;
    private final ModelCapabilityMapper modelCapabilityMapper;
    private final AgentTaskMapper agentTaskMapper;
    private final StorageFeignClient storageFeignClient;

    public void onTaskDone(AgentTask task) {
        // 幂等：已归档过直接返回
        Long archived = assetTaskRelationMapper.selectCount(new LambdaQueryWrapper<AssetTaskRelation>()
                .eq(AssetTaskRelation::getTaskId, task.getId())
                .eq(AssetTaskRelation::getRelationType, AssetTaskRelation.REL_OUTPUT));
        if (archived != null && archived > 0) {
            return;
        }

        // 输入资产关系（唯一键天然幂等）
        if (task.getSourceAssetId() != null && !task.getSourceAssetId().isBlank()) {
            writeRelation(task.getSourceAssetId(), task.getId(), AssetTaskRelation.REL_INPUT);
        }

        // 拉取产出文件
        Result<List<FileDTO>> res = storageFeignClient.listByTask(task.getId());
        if (res == null || res.getCode() != 0 || res.getData() == null || res.getData().isEmpty()) {
            log.warn("Archive skipped: no files for task={}", task.getId());
            return;
        }

        Map<String, Object> params = parseParams(task.getRequestParams());
        String prompt = params != null ? (String) params.get("prompt") : null;
        String provider = params != null ? (String) params.get("modelProvider") : null;
        Asset source = task.getSourceAssetId() != null ? assetMapper.selectById(task.getSourceAssetId()) : null;

        for (FileDTO file : res.getData()) {
            Asset asset = new Asset();
            asset.setTenantId(task.getTenantId());
            asset.setProjectId(task.getProjectId());
            asset.setParentAssetId(task.getSourceAssetId());
            asset.setDerivationType(resolveDerivationType(source, file));
            asset.setAssetType(resolveAssetType(file.contentType()));
            asset.setTitle(buildTitle(prompt));
            asset.setFileId(file.id());
            asset.setPrompt(prompt);
            asset.setModelProvider(provider);
            asset.setParams(task.getRequestParams());
            asset.setStatus(Asset.STATUS_DRAFT);
            asset.setStarred(0);
            asset.setCreatedBy(task.getUserId());
            assetMapper.insert(asset);

            writeRelation(asset.getId(), task.getId(), AssetTaskRelation.REL_OUTPUT);
        }

        estimateCost(task, res.getData(), params);
        log.info("Task archived: task={}, assets={}", task.getId(), res.getData().size());
    }

    // ========== 成本估算 ==========

    private void estimateCost(AgentTask task, List<FileDTO> files, Map<String, Object> params) {
        try {
            String provider = params != null ? (String) params.get("modelProvider") : null;
            if (provider == null || provider.isBlank()) {
                return;
            }
            String engineType = params != null ? String.valueOf(params.get("engineType")) : "";
            String capability = "VIDEO".equalsIgnoreCase(engineType)
                    ? ModelCapability.CAP_TEXT2VIDEO : ModelCapability.CAP_TEXT2IMAGE;

            ModelCapability mc = modelCapabilityMapper.selectOne(new LambdaQueryWrapper<ModelCapability>()
                    .eq(ModelCapability::getCapability, capability)
                    .eq(ModelCapability::getProvider, provider)
                    .eq(ModelCapability::getEnabled, 1)
                    .orderByAsc(ModelCapability::getPriority)
                    .last("LIMIT 1"));
            if (mc == null || mc.getCostPerUnit() == null) {
                return;
            }

            BigDecimal units = switch (mc.getCostUnit()) {
                case "SECOND" -> {
                    long totalMs = files.stream()
                            .map(f -> f.durationMs() != null ? f.durationMs() : 0)
                            .mapToLong(Integer::longValue).sum();
                    yield totalMs > 0 ? BigDecimal.valueOf(totalMs).divide(BigDecimal.valueOf(1000), 1, RoundingMode.UP)
                            : BigDecimal.ONE;
                }
                case "CALL" -> {
                    Object count = params != null ? params.get("count") : null;
                    long n = count instanceof Number num ? num.longValue() : files.size();
                    yield BigDecimal.valueOf(Math.max(n, 1));
                }
                default -> null; // TOKEN 类生成任务阶段 1 不估算
            };
            if (units == null) {
                return;
            }

            task.setCost(mc.getCostPerUnit().multiply(units));
            agentTaskMapper.updateById(task);
        } catch (Exception e) {
            log.warn("Cost estimation failed for task={}: {}", task.getId(), e.getMessage());
        }
    }

    // ========== 内部工具 ==========

    private void writeRelation(String assetId, String taskId, String relationType) {
        AssetTaskRelation rel = new AssetTaskRelation();
        rel.setAssetId(assetId);
        rel.setTaskId(taskId);
        rel.setRelationType(relationType);
        assetTaskRelationMapper.insert(rel);
    }

    private Map<String, Object> parseParams(String requestParams) {
        if (requestParams == null || requestParams.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.fromJson(requestParams, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 同模态变体 / 跨模态转换 / 直接生成。 */
    private String resolveDerivationType(Asset source, FileDTO file) {
        if (source == null) {
            return Asset.DERIVATION_GENERATED;
        }
        String outputType = resolveAssetType(file.contentType());
        return outputType.equals(source.getAssetType()) ? Asset.DERIVATION_VARIANT : Asset.DERIVATION_CONVERT;
    }

    private String resolveAssetType(String contentType) {
        if (contentType == null) {
            return Asset.TYPE_FILE;
        }
        if (contentType.startsWith("image/")) {
            return Asset.TYPE_IMAGE;
        }
        if (contentType.startsWith("video/")) {
            return Asset.TYPE_VIDEO;
        }
        if (contentType.startsWith("audio/")) {
            return Asset.TYPE_AUDIO;
        }
        return Asset.TYPE_FILE;
    }

    private String buildTitle(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "生成结果";
        }
        String cleaned = prompt.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 30 ? cleaned.substring(0, 30) : cleaned;
    }
}
