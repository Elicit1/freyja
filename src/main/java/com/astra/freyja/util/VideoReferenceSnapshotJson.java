package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** JSON embedded in a string is parsed by browsers separately from the global HTTP serializer. */
public final class VideoReferenceSnapshotJson {
    private VideoReferenceSnapshotJson() {}

    public static String write(Object value, ObjectMapper mapper) {
        try {
            SimpleModule ids = new SimpleModule("ReferenceSnapshotLongStrings");
            ids.addSerializer(Long.class, ToStringSerializer.instance);
            return mapper.copy().registerModule(ids).writeValueAsString(value);
        } catch (Exception e) { throw new BizException(500, "参考视频输入快照序列化失败"); }
    }
}
