package com.platform.agent.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.agent.asset.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetMapper extends BaseMapper<Asset> {

    /**
     * 绕过 @TableLogic 软删过滤按 id 查询（含已删除行）。
     * 派生链展示需要：父资产被删除后，子资产 lineage 仍能看到父节点（deleted=true 占位）。
     * 自定义 @Select 不会注入逻辑删除条件（BaseMapper 生成的方法才会）。
     */
    @Select("SELECT * FROM asset WHERE id = #{id}")
    Asset selectByIdIgnoreDeleted(@Param("id") String id);
}
