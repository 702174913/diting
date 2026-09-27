package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM users WHERE id=#{id} FOR UPDATE")
    User lockById(@Param("id") Long id);

    @Update("UPDATE users SET erased_at=NOW(3) WHERE id=#{id} AND erased_at IS NULL")
    int revokeForErase(@Param("id") Long id);

    @Update("UPDATE users SET wechat_openid=#{openid} WHERE id=#{id} AND erased_at IS NULL")
    int bindActiveWechat(@Param("id") Long id, @Param("openid") String openid);

    /**
     * 抹除时的账号匿名化。
     * 注意：MyBatis-Plus 默认**跳过 null 字段**，用 updateById 清不掉 PII——
     * 这里显式写 SQL 把身份字段置空，避免「看起来匿名了、其实没清」。
     */
    @Update("UPDATE users SET username = NULL, password_hash = NULL, wechat_openid = NULL, "
        + "unionid = NULL, nickname = '已注销' WHERE id = #{id}")
    int anonymize(@Param("id") Long id);
}
