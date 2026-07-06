package com.platform.securitysvc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.securitysvc.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
