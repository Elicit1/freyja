package com.astra.freyja.service;
import com.astra.freyja.dto.res.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.multipart.MultipartFile;

public interface ResVideoService {
    Page<ResVideoVO> page(ResVideoQuery query);
    ResVideoVO getById(Long id);
    ResVideoVO upload(MultipartFile file, String name, Long dramaId, Long shotId, String sourceType);
    void update(ResVideoDTO dto);
    void delete(Long id);
}
