package com.platform.agent.asset.dto;

import lombok.Data;

import java.util.List;

/**
 * 更新资产请求（全部可选，tags 传了则整体替换）。
 */
@Data
public class AssetUpdateRequest {

    private String title;

    private String description;

    private String status;

    /** 移动归属（源与目标项目均需写权限） */
    private String projectId;

    private List<String> tags;
}
