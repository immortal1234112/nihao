package com.platform.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.user.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
}
