package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 人物造型/服装响应 VO (向后兼容)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResCharacterOutfitVO extends ResCharacterLookVO {

    public String getOutfitName() {
        return getLookName();
    }

    public void setOutfitName(String outfitName) {
        setLookName(outfitName);
    }
}
