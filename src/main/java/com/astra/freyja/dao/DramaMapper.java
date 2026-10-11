package com.astra.freyja.dao;

import com.astra.freyja.entity.Drama;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DramaMapper extends BaseMapper<Drama> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM drama WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Drama lockById(@org.apache.ibatis.annotations.Param("id") Long id);
}
