package com.astra.freyja.dto.res;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResCharacterDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("测试当 aliases 传入对象数组时，能自动提取 alias 字段而不报错 START_OBJECT")
    void testAliasesAsObjectList() throws Exception {
        String json = """
            {
                "id": 1001,
                "name": "主角",
                "gender": "MALE",
                "aliases": [
                    {"id": 2001, "alias": "小张", "aliasType": "OTHER"},
                    {"id": 2002, "alias": "张哥", "aliasType": "NICKNAME"}
                ],
                "defaultOutfit": {"id": 3001, "outfitName": "日常装"},
                "outfits": [{"id": 3001}]
            }
            """;

        ResCharacterDTO dto = objectMapper.readValue(json, ResCharacterDTO.class);
        assertNotNull(dto);
        assertEquals(1001L, dto.getId());
        assertEquals("主角", dto.getName());
        assertEquals(List.of("小张", "张哥"), dto.getAliases());
    }

    @Test
    @DisplayName("测试当 aliases 传入普通字符串数组时正常解析")
    void testAliasesAsStringList() throws Exception {
        String json = """
            {
                "id": 1002,
                "name": "配角",
                "aliases": ["李四", "小李"]
            }
            """;

        ResCharacterDTO dto = objectMapper.readValue(json, ResCharacterDTO.class);
        assertNotNull(dto);
        assertEquals(List.of("李四", "小李"), dto.getAliases());
    }
}
