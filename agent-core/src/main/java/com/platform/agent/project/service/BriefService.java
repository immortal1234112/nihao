package com.platform.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.project.dto.BriefUpdateRequest;
import com.platform.agent.project.entity.CreativeBrief;
import com.platform.agent.project.entity.ProjectMember;
import com.platform.agent.project.mapper.CreativeBriefMapper;
import com.platform.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 创作简报服务 —— 每项目一份，GET 无记录时返回空简报。
 */
@Service
@RequiredArgsConstructor
public class BriefService {

    private final CreativeBriefMapper creativeBriefMapper;
    private final ProjectAccessService projectAccessService;

    public Map<String, Object> get(String projectId) {
        projectAccessService.requireVisible(projectId);
        CreativeBrief brief = select(projectId);
        Map<String, Object> data = new HashMap<>();
        data.put("projectId", projectId);
        data.put("targetAudience", brief != null ? brief.getTargetAudience() : "");
        data.put("theme", brief != null ? brief.getTheme() : "");
        data.put("tone", brief != null ? brief.getTone() : "");
        data.put("channels", brief != null ? brief.getChannels() : "");
        data.put("brandWords", brief != null ? brief.getBrandWords() : "");
        data.put("bannedWords", brief != null ? brief.getBannedWords() : "");
        data.put("extra", brief != null && brief.getExtra() != null ? JsonUtils.fromJson(brief.getExtra(), Map.class) : Map.of());
        return data;
    }

    /** upsert：无记录则创建。 */
    public void update(String projectId, BriefUpdateRequest request) {
        projectAccessService.requireProjectRole(projectId, ProjectMember.ROLE_OWNER, ProjectMember.ROLE_EDITOR);
        CreativeBrief brief = select(projectId);
        if (brief == null) {
            brief = new CreativeBrief();
            brief.setProjectId(projectId);
        }
        brief.setTargetAudience(request.getTargetAudience());
        brief.setTheme(request.getTheme());
        brief.setTone(request.getTone());
        brief.setChannels(request.getChannels());
        brief.setBrandWords(request.getBrandWords());
        brief.setBannedWords(request.getBannedWords());
        brief.setExtra(request.getExtra() != null ? JsonUtils.toJson(request.getExtra()) : null);
        if (brief.getId() == null) {
            creativeBriefMapper.insert(brief);
        } else {
            creativeBriefMapper.updateById(brief);
        }
    }

    private CreativeBrief select(String projectId) {
        return creativeBriefMapper.selectOne(
                new LambdaQueryWrapper<CreativeBrief>().eq(CreativeBrief::getProjectId, projectId));
    }
}
