package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.AppConfig;

public interface AppConfigMapper extends BaseMapper<AppConfig> {
    @org.apache.ibatis.annotations.Update("UPDATE app_config SET config_value=#{row.configValue}, version=#{row.version}, updated_at=#{row.updatedAt} "
        + "WHERE config_key=#{row.configKey} AND COALESCE(version,0)=#{expectedVersion}")
    int compareAndSet(@org.apache.ibatis.annotations.Param("row") AppConfig row,
                      @org.apache.ibatis.annotations.Param("expectedVersion") long expectedVersion);

    @org.apache.ibatis.annotations.Insert("INSERT INTO app_config (config_key,config_value,version,updated_at) "
        + "VALUES (#{configKey},#{configValue},#{version},#{updatedAt}) ON DUPLICATE KEY UPDATE config_key=config_key")
    int insertMissing(AppConfig row);
}
