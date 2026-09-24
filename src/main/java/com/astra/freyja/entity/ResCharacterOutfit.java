package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 人物造型/服装兼容实体（已由 ResCharacterLook 统一承载）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_character_look")
public class ResCharacterOutfit extends ResCharacterLook {

    public String getOutfitName() {
        return getLookName();
    }

    public void setOutfitName(String outfitName) {
        setLookName(outfitName);
    }
}
