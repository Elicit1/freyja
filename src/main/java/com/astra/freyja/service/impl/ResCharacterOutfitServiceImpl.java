package com.astra.freyja.service.impl;

import com.astra.freyja.dto.res.AssetPromptPackageVO;
import com.astra.freyja.dto.res.OutfitPromptDeriveDTO;
import com.astra.freyja.dto.res.OutfitPromptDeriveVO;
import com.astra.freyja.dto.res.ResCharacterOutfitDTO;
import com.astra.freyja.dto.res.ResCharacterOutfitVO;
import com.astra.freyja.service.ResCharacterLookService;
import com.astra.freyja.service.ResCharacterOutfitService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 人物造型/服装管理服务门面实现 (委托给 ResCharacterLookService 统一调度)。
 */
@Service
@RequiredArgsConstructor
public class ResCharacterOutfitServiceImpl implements ResCharacterOutfitService {

    private final ResCharacterLookService lookService;

    @Override
    public List<ResCharacterOutfitVO> listByCharacterId(Long characterId) {
        return lookService.listByCharacterId(characterId).stream()
                .map(this::toOutfitVO)
                .collect(Collectors.toList());
    }

    @Override
    public ResCharacterOutfitVO getById(Long id) {
        return toOutfitVO(lookService.getById(id));
    }

    @Override
    public Long create(ResCharacterOutfitDTO dto) {
        return lookService.create(dto);
    }

    @Override
    public void update(ResCharacterOutfitDTO dto) {
        lookService.update(dto);
    }

    @Override
    public void delete(Long id) {
        lookService.delete(id);
    }

    @Override
    public void setDefault(Long id) {
        lookService.setDefault(id);
    }

    @Override
    public OutfitPromptDeriveVO deriveOutfitPrompt(OutfitPromptDeriveDTO dto) {
        return lookService.deriveLookPrompt(dto);
    }

    @Override
    public SseEmitter deriveOutfitPromptStream(OutfitPromptDeriveDTO dto) {
        return lookService.deriveLookPromptStream(dto);
    }

    @Override
    public AssetPromptPackageVO buildOutfitPromptPackage(OutfitPromptDeriveDTO dto) {
        return lookService.buildLookPromptPackage(dto);
    }

    private ResCharacterOutfitVO toOutfitVO(com.astra.freyja.dto.res.ResCharacterLookVO lookVO) {
        if (lookVO == null) return null;
        ResCharacterOutfitVO vo = new ResCharacterOutfitVO();
        BeanUtils.copyProperties(lookVO, vo);
        return vo;
    }
}
