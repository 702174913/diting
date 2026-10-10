package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.UploadSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    @Select("SELECT * FROM upload_sessions WHERE id=#{id} FOR UPDATE")
    UploadSession lockById(@Param("id") Long id);

    @org.apache.ibatis.annotations.Update("UPDATE upload_sessions SET status='CONSUMED' "
        + "WHERE id=#{id} AND user_id=#{userId} AND status='VERIFIED'")
    int consumeVerified(@Param("id") Long id, @Param("userId") Long userId);

    /** 孤立上传：已签发/已校验但超过清理时间，且没有登记成任务的对象 */
    @Select("SELECT * FROM upload_sessions "
        + "WHERE status IN ('SIGNED','VERIFIED') AND cleanup_after <= NOW(3) "
        + "AND signed_until < DATE_SUB(NOW(3), INTERVAL 1 MINUTE) "
        + "ORDER BY id LIMIT #{limit}")
    List<UploadSession> selectOrphans(@Param("limit") int limit);
}
