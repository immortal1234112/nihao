package com.platform.agent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.agent.chat.entity.ChatConversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
}
