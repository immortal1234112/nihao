package com.platform.storagesvc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.storagesvc.entity.StorageFile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StorageFileMapper extends BaseMapper<StorageFile> {
}
