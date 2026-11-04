package com.astra.freyja.dao;

import com.astra.freyja.auth.AdminCredential;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AdminCredentialMapper {
    @Select("SELECT username, password_hash, session_version FROM sys_admin_credential WHERE username = #{username}")
    AdminCredential find(@Param("username") String username);

    @Insert("INSERT INTO sys_admin_credential (username, password_hash, session_version) VALUES (#{username}, #{passwordHash}, #{sessionVersion})")
    int insert(AdminCredential credential);

    @Update("UPDATE sys_admin_credential SET password_hash = #{credential.passwordHash}, session_version = #{credential.sessionVersion}, "
            + "update_time = CURRENT_TIMESTAMP WHERE username = #{credential.username} AND session_version = #{expectedVersion}")
    int replace(@Param("credential") AdminCredential credential, @Param("expectedVersion") String expectedVersion);
}
