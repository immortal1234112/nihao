package com.platform.agent.project.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 项目知识库挂载 —— 引用 rag_dataset.id，创作助手检索限定挂载数据集。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("project_dataset")
public class ProjectDataset extends BaseEntity {

    @TableField("project_id")
    private String projectId;

    @TableField("dataset_id")
    private String datasetId;
}
