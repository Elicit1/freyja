package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 人物造型/服装新增或修改请求 DTO (向后兼容)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResCharacterOutfitDTO extends ResCharacterLookDTO {

    public String getOutfitName() {
        return getLookName();
    }

    public void setOutfitName(String outfitName) {
        setLookName(outfitName);
    }
}
