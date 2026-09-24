package com.astra.freyja.service;

import com.astra.freyja.dto.res.AssetImageGenerationRequest;
import com.astra.freyja.dto.res.AssetImageGenerationVO;

public interface AssetImageGenerationService {
    AssetImageGenerationVO submit(AssetImageGenerationRequest request);

    AssetImageGenerationVO getTask(String taskId);

    void apply(String targetType, Long targetId, String slot, String imageUrl);
}
