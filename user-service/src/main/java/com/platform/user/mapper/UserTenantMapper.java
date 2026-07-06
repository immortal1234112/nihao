package com.platform.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.user.entity.UserTenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserTenantMapper extends BaseMapper<UserTenant> {
}
