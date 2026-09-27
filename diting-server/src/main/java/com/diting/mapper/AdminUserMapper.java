package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.AdminUser;

public interface AdminUserMapper extends BaseMapper<AdminUser> {
    /** 所有成员变更共享一行锁，防止两个管理员同时移除彼此。 */
    @org.apache.ibatis.annotations.Select("SELECT name FROM mutation_locks WHERE name='ADMIN_MEMBERSHIP' FOR UPDATE")
    String lockMembership();
}
