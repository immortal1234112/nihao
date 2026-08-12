package com.platform.agent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.agent.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
