package com.platform.agent.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.agent.rag.entity.RagChunk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RagChunkMapper extends BaseMapper<RagChunk> {
}
