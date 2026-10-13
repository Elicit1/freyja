package com.astra.freyja.dao;

import com.astra.freyja.capability.model.CapabilityRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CapabilityMapper {
    CapabilityRow state();
    CapabilityRow lockState();
    int initializeState();
    CapabilityRow draft();
    int saveDraft(@Param("json") String json);
    CapabilityRow release(@Param("id") Long id);
    List<CapabilityRow> releases();
    int saveRelease(@Param("id") Long id, @Param("revision") long revision, @Param("json") String json,
                    @Param("hash") String hash, @Param("note") String note);
    int activate(@Param("id") Long id, @Param("revision") long revision);
    int insertDefinition(@Param("id") Long id, @Param("key") String key, @Param("name") String name);
    int insertVersion(@Param("id") Long id, @Param("capabilityId") Long capabilityId,
                      @Param("revision") long revision, @Param("json") String json);
    int saveBinding(@Param("id") Long id, @Param("capabilityId") Long capabilityId,
                    @Param("consumer") String consumer, @Param("name") String name, @Param("json") String json);
    int saveRule(@Param("code") String code, @Param("content") String content);
    int enqueue(@Param("id") Long id, @Param("revision") long revision, @Param("releaseId") Long releaseId);
    List<CapabilityRow> pendingEvents();
    int sent(@Param("id") Long id);
    int retry(@Param("id") Long id);
}
