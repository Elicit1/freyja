package com.astra.freyja.dao;

import com.astra.freyja.entity.CharacterVoiceAudio;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;
import java.util.Map;
import java.util.List;

@org.apache.ibatis.annotations.Mapper
public interface CharacterVoiceAudioMapper extends BaseMapper<CharacterVoiceAudio> {
    @Select("SELECT * FROM character_voice_audio WHERE id=#{id} AND deleted=0 FOR UPDATE")
    CharacterVoiceAudio lock(@Param("id") Long id);

    @Select("SELECT id FROM res_character WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Long lockCharacter(@Param("id") Long id);

    @Select("""
        <script>
        SELECT c.id,c.name,c.drama_id,d.title AS drama_title,c.create_time,
          (SELECT COUNT(*) FROM character_voice_audio a WHERE a.character_id=c.id AND a.kind='BASE' AND a.deleted=0) AS has_base
        FROM res_character c LEFT JOIN drama d ON c.drama_id=d.id AND d.deleted=0
        WHERE c.deleted=0 AND (c.identity_status IS NULL OR c.identity_status != 'MERGED')
        <if test="name != null and name != ''">AND (c.name LIKE CONCAT('%',#{name},'%') OR c.canonical_name LIKE CONCAT('%',#{name},'%') OR c.display_name LIKE CONCAT('%',#{name},'%'))</if>
        <if test="drama != null and drama != ''">AND d.title LIKE CONCAT('%',#{drama},'%')</if>
        ORDER BY d.create_time DESC,d.id DESC,c.create_time DESC,c.id DESC
        </script>
        """)
    Page<Map<String,Object>> characters(Page<Map<String,Object>> page, @Param("name") String name, @Param("drama") String drama);

    @Select("""
        SELECT position FROM (
          SELECT c.id,ROW_NUMBER() OVER (ORDER BY d.create_time DESC,d.id DESC,c.create_time DESC,c.id DESC) AS position
          FROM res_character c LEFT JOIN drama d ON c.drama_id=d.id AND d.deleted=0
          WHERE c.deleted=0 AND (c.identity_status IS NULL OR c.identity_status != 'MERGED')
        ) ranked WHERE id=#{id}
        """)
    Long position(@Param("id") Long id);

    @Select("""
        <script>
        SELECT c.id,c.name,c.drama_id,d.title AS drama_title,c.create_time,
          (SELECT COUNT(*) FROM character_voice_audio a WHERE a.character_id=c.id AND a.kind='BASE' AND a.deleted=0) AS has_base
        FROM res_character c LEFT JOIN drama d ON d.id=c.drama_id AND d.deleted=0
        WHERE c.deleted=0 AND (c.identity_status IS NULL OR c.identity_status != 'MERGED')
          AND (c.drama_id=#{dramaId} OR c.drama_id=0)
          AND c.id IN <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
        <if test="name != null and name != ''">AND (c.name LIKE CONCAT('%',#{name},'%') OR c.canonical_name LIKE CONCAT('%',#{name},'%') OR c.display_name LIKE CONCAT('%',#{name},'%'))</if>
        ORDER BY CASE WHEN c.id=#{preferredId} THEN 0 ELSE 1 END,c.create_time DESC,c.id DESC
        </script>
        """)
    Page<Map<String,Object>> pickerCharacters(Page<Map<String,Object>> page,@Param("dramaId") Long dramaId,@Param("ids") List<Long> ids,@Param("name") String name,@Param("preferredId") Long preferredId);
}
