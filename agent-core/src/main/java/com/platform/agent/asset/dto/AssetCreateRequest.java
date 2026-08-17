package com.platform.agent.asset.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 手动创建资产请求（上传归档 / 文案归档）。
 */
@Data
public class AssetCreateRequest {

    private String title;

    private String description;

    /** 所属项目，空=租户级素材库 */
    private String projectId;

    private String parentAssetId;

    private String derivationType = "UPLOAD";

    private String assetType = "IMAGE";

    /** storage_file.id（图片/视频/文件资产） */
    private String fileId;

    /** TEXT 资产正文 */
    private String contentText;

    private String prompt;

    private String modelProvider;

    private String modelCode;

    private Map<String, Object> params;

    private List<String> tags;
}
