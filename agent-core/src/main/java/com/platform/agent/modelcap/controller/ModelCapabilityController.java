package com.platform.agent.modelcap.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.modelcap.entity.ModelCapability;
import com.platform.agent.modelcap.mapper.ModelCapabilityMapper;
import com.platform.core.base.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型能力目录控制器 — /api/v1/model-capabilities
 * 供创作页"简单模式"质量档位选择；按 capability 分组返回启用项。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/model-capabilities")
@RequiredArgsConstructor
public class ModelCapabilityController {

    private final ModelCapabilityMapper modelCapabilityMapper;

    @GetMapping
    public Result<Map<String, List<ModelCapability>>> list() {
        List<ModelCapability> all = modelCapabilityMapper.selectList(
                new LambdaQueryWrapper<ModelCapability>()
                        .eq(ModelCapability::getEnabled, 1)
                        .orderByAsc(ModelCapability::getCapability)
                        .orderByAsc(ModelCapability::getPriority));

        Map<String, List<ModelCapability>> grouped = new LinkedHashMap<>();
        for (ModelCapability mc : all) {
            grouped.computeIfAbsent(mc.getCapability(), k -> new java.util.ArrayList<>()).add(mc);
        }
        return Result.ok(grouped);
    }
}
