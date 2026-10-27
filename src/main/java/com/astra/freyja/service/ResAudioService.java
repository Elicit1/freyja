package com.astra.freyja.service;
import com.astra.freyja.dto.res.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.multipart.MultipartFile;

public interface ResAudioService {
    Page<ResAudioVO> page(ResAudioQuery query);
    ResAudioVO getById(Long id);
    ResAudioVO upload(MultipartFile file, String name, Long dramaId, Long shotId, String sourceType, String audioKind, String text, String language);
    void validateReferences(Long dramaId, java.util.List<com.astra.freyja.dto.drama.ShotRefAudioDTO> references);
    ResAudioVO upload(MultipartFile file, ResAudioDTO metadata, String sourceType);
    void update(ResAudioDTO dto);
    void delete(Long id);
}
