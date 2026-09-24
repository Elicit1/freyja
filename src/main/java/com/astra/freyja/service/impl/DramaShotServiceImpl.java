package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotGroupMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dao.ResCharacterOutfitMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dto.comfy.ComfyRenderTaskVO;
import com.astra.freyja.dto.drama.CharacterShotRefInfoVO;
import com.astra.freyja.dto.drama.DramaShotBatchAssembleDTO;
import com.astra.freyja.dto.drama.DramaShotDTO;
import com.astra.freyja.dto.drama.DramaShotFirstFrameDTO;
import com.astra.freyja.dto.drama.DramaShotRenderRequestDTO;
import com.astra.freyja.dao.ResPropMapper;
import com.astra.freyja.dto.drama.DramaShotReorderDTO;
import com.astra.freyja.dto.drama.DramaShotVO;
import com.astra.freyja.dto.drama.PropShotRefDTO;
import com.astra.freyja.dto.drama.PropShotRefInfoVO;
import com.astra.freyja.dto.drama.ShotRefAudioDTO;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.astra.freyja.dto.res.CharacterShotRefDTO;
import com.astra.freyja.dto.res.PromptAssembleRequestDTO;
import com.astra.freyja.dto.res.PromptAssembleResultVO;
import com.astra.freyja.entity.ResProp;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterOutfit;
import com.astra.freyja.entity.ResScene;
import com.astra.freyja.entity.enums.ComfyTaskStatus;
import com.astra.freyja.service.DramaEpisodeService;
import com.astra.freyja.service.DramaShotService;
import com.astra.freyja.service.PromptAssembleService;
import com.astra.freyja.dao.DramaShotVideoTakeMapper;
import com.astra.freyja.dao.RenderTaskMapper;
import com.astra.freyja.entity.DramaShotVideoTake;
import com.astra.freyja.entity.RenderTask;
import com.astra.freyja.service.ShotVideoTakeService;
import com.astra.freyja.util.AspectRatioUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 分镜镜头管理服务实现（已全面转向标准 API 调度）。
 */
@Slf4j
@Service
public class DramaShotServiceImpl implements DramaShotService {

    public static final BigDecimal MAX_SHOT_DURATION = new BigDecimal("15.00");
    public static final BigDecimal DEFAULT_SHOT_DURATION = new BigDecimal("3.00");

    private final DramaShotMapper shotMapper;
    private final DramaShotGroupMapper shotGroupMapper;
    private final DramaSceneMapper sceneMapper;
    private final DramaMapper dramaMapper;
    private final ResSceneMapper resSceneMapper;
    private final ResPropMapper resPropMapper;
    private final ResCharacterMapper characterMapper;
    private final ResCharacterOutfitMapper outfitMapper;
    private final DramaEpisodeService episodeService;
    private final PromptAssembleService promptAssembleService;
    private final ObjectMapper objectMapper;
    private final com.astra.freyja.service.AiImageApiService aiImageApiService;
    private final com.astra.freyja.service.RenderTaskService renderTaskService;
    private final java.util.concurrent.Executor renderAsyncExecutor;
    private final ShotVideoTakeService shotVideoTakeService;
    private final DramaShotVideoTakeMapper shotVideoTakeMapper;
    private final RenderTaskMapper renderTaskMapper;

    public DramaShotServiceImpl(DramaShotMapper shotMapper,
                                DramaShotGroupMapper shotGroupMapper,
                                DramaSceneMapper sceneMapper,
                                DramaMapper dramaMapper,
                                ResSceneMapper resSceneMapper,
                                ResPropMapper resPropMapper,
                                ResCharacterMapper characterMapper,
                                ResCharacterOutfitMapper outfitMapper,
                                DramaEpisodeService episodeService,
                                PromptAssembleService promptAssembleService,
                                ObjectMapper objectMapper,
                                com.astra.freyja.service.AiImageApiService aiImageApiService,
                                @Lazy com.astra.freyja.service.RenderTaskService renderTaskService,
                                @org.springframework.beans.factory.annotation.Qualifier("renderAsyncExecutor") java.util.concurrent.Executor renderAsyncExecutor,
                                ShotVideoTakeService shotVideoTakeService,
                                DramaShotVideoTakeMapper shotVideoTakeMapper,
                                RenderTaskMapper renderTaskMapper) {
        this.shotMapper = shotMapper;
        this.shotGroupMapper = shotGroupMapper;
        this.sceneMapper = sceneMapper;
        this.dramaMapper = dramaMapper;
        this.resSceneMapper = resSceneMapper;
        this.resPropMapper = resPropMapper;
        this.characterMapper = characterMapper;
        this.outfitMapper = outfitMapper;
        this.episodeService = episodeService;
        this.promptAssembleService = promptAssembleService;
        this.objectMapper = objectMapper;
        this.aiImageApiService = aiImageApiService;
        this.renderTaskService = renderTaskService;
        this.renderAsyncExecutor = renderAsyncExecutor;
        this.shotVideoTakeService = shotVideoTakeService;
        this.shotVideoTakeMapper = shotVideoTakeMapper;
        this.renderTaskMapper = renderTaskMapper;
    }

    @Override
    public List<DramaShotVO> listBySceneId(Long sceneId) {
        if (sceneId == null) {
            return Collections.emptyList();
        }
        List<DramaShot> list = shotMapper.selectList(
                new LambdaQueryWrapper<DramaShot>()
                        .eq(DramaShot::getSceneId, sceneId)
                        .orderByAsc(DramaShot::getShotNo)
                        .orderByAsc(DramaShot::getSortOrder)
        );

        return list.stream().map(this::enrichShotVO).collect(Collectors.toList());
    }

    @Override
    public List<DramaShotVO> listByEpisodeId(Long episodeId) {
        if (episodeId == null) {
            return Collections.emptyList();
        }
        List<DramaShot> list = shotMapper.selectList(
                new LambdaQueryWrapper<DramaShot>()
                        .eq(DramaShot::getEpisodeId, episodeId)
                        .orderByAsc(DramaShot::getSceneId)
                        .orderByAsc(DramaShot::getShotNo)
        );

        return list.stream().map(this::enrichShotVO).collect(Collectors.toList());
    }

    @Override
    public DramaShotVO getById(Long id) {
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + id);
        }
        return enrichShotVO(shot);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DramaShotDTO dto) {
        if (dto == null || dto.getSceneId() == null) {
            throw new BizException(400, "归属场次 ID 不能为空");
        }
        DramaScene scene = sceneMapper.selectById(dto.getSceneId());
        if (scene == null) {
            throw new BizException(404, "归属场次不存在: " + dto.getSceneId());
        }

        DramaShot shot = new DramaShot();
        BeanUtils.copyProperties(dto, shot);

        shot.setSceneId(scene.getId());
        if (shot.getEpisodeId() == null) {
            shot.setEpisodeId(scene.getEpisodeId());
        }
        if (shot.getDramaId() == null) {
            shot.setDramaId(scene.getDramaId());
        }

        // 自动关联或创建镜头组 (ShotGroup)
        if (shot.getShotGroupId() == null || shot.getShotGroupId() <= 0) {
            List<com.astra.freyja.entity.DramaShotGroup> existingGroups = shotGroupMapper.selectList(
                    new LambdaQueryWrapper<com.astra.freyja.entity.DramaShotGroup>()
                            .eq(com.astra.freyja.entity.DramaShotGroup::getSceneId, scene.getId())
                            .orderByAsc(com.astra.freyja.entity.DramaShotGroup::getGroupNo)
            );
            if (!existingGroups.isEmpty()) {
                shot.setShotGroupId(existingGroups.get(0).getId());
            } else {
                com.astra.freyja.entity.DramaShotGroup defaultGroup = new com.astra.freyja.entity.DramaShotGroup();
                defaultGroup.setSceneId(scene.getId());
                defaultGroup.setEpisodeId(scene.getEpisodeId());
                defaultGroup.setDramaId(scene.getDramaId());
                defaultGroup.setGroupNo(1);
                defaultGroup.setName(scene.getName() + " - 连续镜头组 1");
                defaultGroup.setPurpose("默认动作与对白连续镜头组");
                defaultGroup.setSortOrder(1);
                shotGroupMapper.insert(defaultGroup);
                shot.setShotGroupId(defaultGroup.getId());
            }
        }

        if (shot.getShotNo() == null || shot.getShotNo() <= 0) {
            List<DramaShot> existing = shotMapper.selectList(
                    new LambdaQueryWrapper<DramaShot>()
                            .eq(DramaShot::getSceneId, dto.getSceneId())
                            .orderByDesc(DramaShot::getShotNo)
            );
            int nextNo = existing.isEmpty() ? 1 : existing.get(0).getShotNo() + 1;
            shot.setShotNo(nextNo);
        }

        if (StringUtils.isBlank(shot.getShotName())) {
            shot.setShotName(String.format("S%02d-%02d", scene.getSceneNo(), shot.getShotNo()));
        }
        if (StringUtils.isBlank(shot.getShotType())) {
            shot.setShotType(null);
        }
        if (StringUtils.isBlank(shot.getCameraMovement())) {
            shot.setCameraMovement(null);
        }
        shot.setShotTypeLocked(isExplicitCameraConstraint(dto.getShotTypeLocked(), shot.getShotType()));
        shot.setCameraMovementLocked(isExplicitCameraConstraint(dto.getCameraMovementLocked(), shot.getCameraMovement()));
        if (shot.getDuration() == null || shot.getDuration().compareTo(BigDecimal.ZERO) <= 0) {
            shot.setDuration(DEFAULT_SHOT_DURATION);
        } else if (shot.getDuration().compareTo(MAX_SHOT_DURATION) > 0) {
            throw new BizException(400, "创建分镜镜头时长最长不得超过 15 秒 (当前: " + shot.getDuration() + "s)");
        }
        if (StringUtils.isBlank(shot.getRenderStatus())) {
            shot.setRenderStatus("INIT");
        }
        if (shot.getSortOrder() == null) {
            shot.setSortOrder(shot.getShotNo());
        }

        if (StringUtils.isBlank(shot.getGenerationMode())) {
            shot.setGenerationMode("FIRST_LAST_FRAME");
        }

        // 序列化角色引用列表
        if (dto.getCharacterRefs() != null && !dto.getCharacterRefs().isEmpty()) {
            try {
                shot.setCharacterRefsJson(objectMapper.writeValueAsString(dto.getCharacterRefs()));
            } catch (Exception e) {
                log.warn("[DramaShotService] 序列化 characterRefs 异常: {}", e.getMessage());
            }
        }

        // 序列化道具引用列表
        if (dto.getPropRefs() != null && !dto.getPropRefs().isEmpty()) {
            try {
                shot.setPropRefsJson(objectMapper.writeValueAsString(dto.getPropRefs()));
            } catch (Exception e) {
                log.warn("[DramaShotService] 序列化 propRefs 异常: {}", e.getMessage());
            }
        }

        // 序列化参考图列表
        if (dto.getRefImages() != null && !dto.getRefImages().isEmpty()) {
            try {
                shot.setRefImagesJson(objectMapper.writeValueAsString(dto.getRefImages()));
            } catch (Exception e) {
                log.warn("[DramaShotService] 序列化 refImages 异常: {}", e.getMessage());
            }
        }

        // 序列化参考音频列表
        if (dto.getRefAudios() != null && !dto.getRefAudios().isEmpty()) {
            try {
                shot.setRefAudiosJson(objectMapper.writeValueAsString(dto.getRefAudios()));
            } catch (Exception e) {
                log.warn("[DramaShotService] 序列化 refAudios 异常: {}", e.getMessage());
            }
        }

        shotMapper.insert(shot);
        log.info("[DramaShotService] 创建分镜成功: id={}, sceneId={}, shotGroupId={}, shotNo={}",
                shot.getId(), shot.getSceneId(), shot.getShotGroupId(), shot.getShotNo());

        // 重新计算剧集时长
        episodeService.recalculateEpisodeDuration(shot.getEpisodeId());
        return shot.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(DramaShotDTO dto) {
        if (dto == null || dto.getId() == null) {
            throw new BizException(400, "分镜 ID 不能为空");
        }
        DramaShot existing = shotMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BizException(404, "分镜不存在: " + dto.getId());
        }

        DramaShot shot = new DramaShot();
        BeanUtils.copyProperties(dto, shot);
        // 视频产物及其来源只能通过视频 Take 选择/渲染流程更新，不能被旧的编辑表单覆盖。
        // 详情页可能在用户切换历史版本前已打开，直接保存 DTO 会把旧 videoUrl 写回数据库。
        shot.setVideoUrl(existing.getVideoUrl());
        shot.setCurrentVideoTakeId(existing.getCurrentVideoTakeId());
        // 渲染调度字段只能由渲染流程维护，普通编辑不得用旧表单覆盖。
        shot.setRenderStatus(existing.getRenderStatus());
        shot.setLatestTaskId(existing.getLatestTaskId());
        shot.setLastFrameUrl(existing.getLastFrameUrl());
        shot.setLastFrameSourceVideoUrl(existing.getLastFrameSourceVideoUrl());
        shot.setLastFrameSourceTakeId(existing.getLastFrameSourceTakeId());
        applyCameraConstraintUpdate(
                shot, dto.getShotType(), dto.getShotTypeLocked(), existing.getShotType(), existing.getShotTypeLocked(), true);
        applyCameraConstraintUpdate(
                shot, dto.getCameraMovement(), dto.getCameraMovementLocked(), existing.getCameraMovement(), existing.getCameraMovementLocked(), false);
        if (dto.getDuration() != null) {
            if (dto.getDuration().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(400, "分镜镜头时长必须大于 0 秒");
            }
            if (dto.getDuration().compareTo(MAX_SHOT_DURATION) > 0) {
                throw new BizException(400, "分镜镜头时长最长不得超过 15 秒 (当前: " + dto.getDuration() + "s)");
            }
        }

        if (dto.getCharacterRefs() != null) {
            try {
                shot.setCharacterRefsJson(objectMapper.writeValueAsString(dto.getCharacterRefs()));
            } catch (Exception e) {
                log.warn("[DramaShotService] 序列化 characterRefs 异常: {}", e.getMessage());
            }
        }

        if (dto.getPropRefs() != null) {
            try {
                shot.setPropRefsJson(objectMapper.writeValueAsString(dto.getPropRefs()));
            } catch (Exception e) {
                log.warn("[DramaShotService] 序列化 propRefs 异常: {}", e.getMessage());
            }
        }

        if (dto.getRefImages() != null) {
            try {
                shot.setRefImagesJson(objectMapper.writeValueAsString(dto.getRefImages()));
            } catch (Exception e) {
                log.warn("[DramaShotService] 序列化 refImages 异常: {}", e.getMessage());
            }
        }

        if (dto.getRefAudios() != null) {
            try {
                shot.setRefAudiosJson(objectMapper.writeValueAsString(dto.getRefAudios()));
            } catch (Exception e) {
                log.warn("[DramaShotService] 序列化 refAudios 异常: {}", e.getMessage());
            }
        }

        if (dto.getPreviewImageUrl() != null && dto.getPreviewImageUrl().isEmpty()) {
            shot.setPreviewImageUrl(null);
            shot.setFirstFrameSourceType(null);
            shot.setFirstFrameSourceShotId(null);
            shot.setFirstFrameSourceVideoUrl(null);
        }

        shotMapper.updateById(shot);
        log.info("[DramaShotService] 更新分镜成功: id={}", shot.getId());

        if (existing.getEpisodeId() != null) {
            episodeService.recalculateEpisodeDuration(existing.getEpisodeId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        DramaShot existing = shotMapper.selectById(id);
        if (existing == null) {
            return;
        }
        shotMapper.deleteById(id);
        shotVideoTakeMapper.delete(new LambdaQueryWrapper<DramaShotVideoTake>().eq(DramaShotVideoTake::getShotId, id));
        log.info("[DramaShotService] 删除分镜成功: id={}", id);

        if (existing.getEpisodeId() != null) {
            episodeService.recalculateEpisodeDuration(existing.getEpisodeId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long cloneShot(Long id) {
        DramaShot source = shotMapper.selectById(id);
        if (source == null) {
            throw new BizException(404, "待克隆分镜不存在: " + id);
        }

        List<DramaShot> existing = shotMapper.selectList(
                new LambdaQueryWrapper<DramaShot>()
                        .eq(DramaShot::getSceneId, source.getSceneId())
                        .orderByDesc(DramaShot::getShotNo)
        );
        int nextNo = existing.isEmpty() ? 1 : existing.get(0).getShotNo() + 1;

        DramaShot clone = new DramaShot();
        BeanUtils.copyProperties(source, clone);
        clone.setId(null);
        clone.setShotGroupId(source.getShotGroupId());
        clone.setShotNo(nextNo);
        clone.setShotName(source.getShotName() + " (副本)");
        clone.setSortOrder(nextNo);
        clone.setRenderStatus("INIT");
        clone.setPreviewImageUrl(null);
        clone.setEndFrameImageUrl(null);
        clone.setVideoUrl(null);
        clone.setAudioUrl(null);
        clone.setLatestTaskId(null);

        shotMapper.insert(clone);
        log.info("[DramaShotService] 克隆分镜成功: srcId={}, newId={}", id, clone.getId());

        if (clone.getEpisodeId() != null) {
            episodeService.recalculateEpisodeDuration(clone.getEpisodeId());
        }
        return clone.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorderShots(DramaShotReorderDTO dto) {
        if (dto == null || dto.getSceneId() == null || dto.getShotIds() == null || dto.getShotIds().isEmpty()) {
            throw new BizException(400, "场次 ID 或分镜 ID 列表不能为空");
        }
        DramaScene targetScene = sceneMapper.selectById(dto.getSceneId());
        if (targetScene == null) {
            throw new BizException(404, "目标场次不存在: " + dto.getSceneId());
        }

        List<Long> shotIds = dto.getShotIds();
        for (int i = 0; i < shotIds.size(); i++) {
            Long shotId = shotIds.get(i);
            DramaShot updateEntity = new DramaShot();
            updateEntity.setId(shotId);
            updateEntity.setSceneId(targetScene.getId());
            updateEntity.setEpisodeId(targetScene.getEpisodeId());
            updateEntity.setDramaId(targetScene.getDramaId());
            updateEntity.setShotNo(i + 1);
            updateEntity.setSortOrder(i + 1);
            shotMapper.updateById(updateEntity);
        }

        log.info("[DramaShotService] 批量重新排序分镜成功: sceneId={}, count={}", dto.getSceneId(), shotIds.size());
        episodeService.recalculateEpisodeDuration(targetScene.getEpisodeId());
    }

    @Override
    public PromptAssembleResultVO previewShotPrompt(Long id) {
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + id);
        }
        return buildShotAssembleResult(shot);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptAssembleResultVO assembleShotPrompt(Long id) {
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + id);
        }

        PromptAssembleResultVO resultVO = buildShotAssembleResult(shot);

        // 5. 更新分镜提示词 (首帧生图与负向词按最新资产库重组，保持原有视频动态运镜词不被篡改)
        shot.setPrompt(resultVO.getPositivePrompt());
        shot.setNegativePrompt(resultVO.getNegativePrompt());
        if (StringUtils.isNotBlank(shot.getVideoPrompt())) {
            // 已有视频动态运镜词 (AI 语义理解产物或创作者手写)，保持原样返回
            resultVO.setVideoPrompt(shot.getVideoPrompt());
        } else if (StringUtils.isNotBlank(resultVO.getVideoPrompt())) {
            // 原视频运镜词为空，才回填默认生成的运镜词
            shot.setVideoPrompt(resultVO.getVideoPrompt());
        }

        Drama drama = shot.getDramaId() != null ? dramaMapper.selectById(shot.getDramaId()) : null;
        String style = StringUtils.firstNonBlank(shot.getStylePreset(), drama != null ? drama.getStylePreset() : null);
        if (StringUtils.isNotBlank(style)) {
            shot.setStylePreset(style);
        }
        shotMapper.updateById(shot);

        log.info("[DramaShotService] 单镜 Prompt 组装完成: shotId={}, promptLen={}", id,
                shot.getPrompt() != null ? shot.getPrompt().length() : 0);
        return resultVO;
    }

    /**
     * 依据分镜关联的角色、造型、场景主数据及连续性状态，构建完整的 Prompt 组装结果 (支持只读预览与落库)。
     */
    private PromptAssembleResultVO buildShotAssembleResult(DramaShot shot) {
        DramaScene scene = sceneMapper.selectById(shot.getSceneId());
        Drama drama = shot.getDramaId() != null ? dramaMapper.selectById(shot.getDramaId()) : null;

        // 1. 解析角色引用
        List<CharacterShotRefDTO> characterRefs = parseCharacterRefs(shot.getCharacterRefsJson());

        // 2. 确定环境场景资产 ID (优先分镜本身，其次继承所属场次)
        Long effectiveResSceneId = shot.getResSceneId();
        if ((effectiveResSceneId == null || effectiveResSceneId <= 0) && scene != null) {
            effectiveResSceneId = scene.getResSceneId();
        }

        // 3. 构建 Prompt 组装请求
        PromptAssembleRequestDTO assembleRequest = new PromptAssembleRequestDTO();
        assembleRequest.setDramaId(shot.getDramaId());
        assembleRequest.setSceneId(effectiveResSceneId);
        assembleRequest.setCustomScenePrompt(shot.getCustomScenePrompt());
        assembleRequest.setCharacterRefs(characterRefs);

        // 拼接镜头/动作 Prompt (优先使用动作画面描述，仅当画面为空时才以景别兜底，杜绝机械堆砌景别词)
        StringBuilder shotPromptBuilder = new StringBuilder();
        if (StringUtils.isNotBlank(shot.getActionDescription())) {
            shotPromptBuilder.append(shot.getActionDescription());
        } else if (Boolean.TRUE.equals(shot.getShotTypeLocked()) && StringUtils.isNotBlank(shot.getShotType())
                && !"AUTO".equalsIgnoreCase(shot.getShotType())) {
            String type = shot.getShotType().equalsIgnoreCase("FULL_SHOT") ? "full shot" : shot.getShotType().toLowerCase().replace("_", " ") + " shot";
            shotPromptBuilder.append(type);
        }
        assembleRequest.setShotPrompt(shotPromptBuilder.toString());

        String style = shot.getStylePreset();
        if (StringUtils.isBlank(style) && drama != null) {
            style = drama.getStylePreset();
        }
        assembleRequest.setStylePreset(style);

        // 4. 调用组装引擎
        PromptAssembleResultVO resultVO = promptAssembleService.assemble(assembleRequest);

        // 若分镜数据库中已有保存的纯英文提示词，在预览和呈现时优先展示
        if (StringUtils.isNotBlank(shot.getPrompt())) {
            resultVO.setPositivePrompt(shot.getPrompt());
        }
        if (StringUtils.isNotBlank(shot.getVideoPrompt())) {
            resultVO.setVideoPrompt(shot.getVideoPrompt());
        }
        if (StringUtils.isNotBlank(shot.getNegativePrompt())) {
            resultVO.setNegativePrompt(shot.getNegativePrompt());
        }

        return resultVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchAssemblePrompts(DramaShotBatchAssembleDTO dto) {
        if (dto == null) {
            throw new BizException(400, "参数不能为空");
        }

        List<Long> targetIds = dto.getShotIds();
        if (targetIds == null || targetIds.isEmpty()) {
            if (dto.getSceneId() == null) {
                throw new BizException(400, "未指定场次 ID 或分镜 ID 列表");
            }
            List<DramaShot> list = shotMapper.selectList(
                    new LambdaQueryWrapper<DramaShot>()
                            .eq(DramaShot::getSceneId, dto.getSceneId())
                            .orderByAsc(DramaShot::getShotNo)
            );
            targetIds = list.stream().map(DramaShot::getId).collect(Collectors.toList());
        }

        int count = 0;
        for (Long shotId : targetIds) {
            try {
                assembleShotPrompt(shotId);
                count++;
            } catch (Exception e) {
                log.warn("[DramaShotService] 批量组装分镜 Prompt 失败: shotId={}, msg={}", shotId, e.getMessage());
            }
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ComfyRenderTaskVO generateFirstFrame(Long id, DramaShotFirstFrameDTO dto) {
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + id);
        }

        boolean isEndFrame = dto != null && "END_FRAME".equalsIgnoreCase(dto.getFrameType());

        // 优先使用自定义传入词，其次使用对应关键帧提示词，若均为空则明确提示创作者
        String prompt = (dto != null && StringUtils.isNotBlank(dto.getCustomPrompt()))
                ? dto.getCustomPrompt()
                : (isEndFrame
                    ? StringUtils.firstNonBlank(shot.getEndFramePrompt(), shot.getPrompt())
                    : StringUtils.firstNonBlank(shot.getFirstFramePrompt(), shot.getPrompt()));

        if (StringUtils.isBlank(prompt)) {
            throw new BizException(400, (isEndFrame ? "尾帧" : "首帧") + "生图提示词不能为空，请先在提示词工作台填写或使用 AI 智能生成");
        }

        if (dto == null) {
            dto = new DramaShotFirstFrameDTO();
        }

        // 尝试获取短剧配置中的画幅比例 / 尺寸
        if (StringUtils.isBlank(dto.getSize())) {
            String resolvedSize = resolveSizeFromDrama(shot.getDramaId());
            dto.setSize(resolvedSize);
            log.info("[DramaShotService] 自动获取短剧配置生图尺寸: shotId={}, dramaId={}, size={}", id, shot.getDramaId(), resolvedSize);
        }

        if (StringUtils.isBlank(dto.getNegativePrompt()) && StringUtils.isNotBlank(shot.getNegativePrompt())) {
            dto.setNegativePrompt(shot.getNegativePrompt());
        }

        String taskId = "GEN_" + System.currentTimeMillis();
        if (dto != null) {
            dto.setTaskId(taskId);
        }

        // 提前解析提供商与模型，固化到任务中，避免取消时提供商路由丢失
        com.astra.freyja.entity.AiProvider actualProvider = null;
        String actualModelCode = null;
        try {
            actualProvider = aiImageApiService.resolveProvider(dto != null ? dto.getProviderId() : null);
            actualModelCode = aiImageApiService.resolveModelCode(dto, actualProvider.getId());
        } catch (Exception e) {
            log.warn("[DramaShotService] 预解析提供商与模型警告: {}", e.getMessage());
        }

        com.astra.freyja.dto.render.RenderTaskVO rTask = com.astra.freyja.dto.render.RenderTaskVO.builder()
                .taskId(taskId)
                .taskType("SHOT_FRAME")
                .taskName(String.format("[S%d %s] %s生图", shot.getShotNo() != null ? shot.getShotNo() : 0,
                        StringUtils.defaultIfBlank(shot.getShotName(), "分镜"), isEndFrame ? "尾帧" : "首帧"))
                .dramaId(shot.getDramaId())
                .episodeId(shot.getEpisodeId())
                .sceneId(shot.getSceneId())
                .shotId(id)
                .shotNo(shot.getShotNo())
                .shotGroupId(shot.getShotGroupId())
                .prompt(prompt)
                .negativePrompt(dto != null ? dto.getNegativePrompt() : shot.getNegativePrompt())
                .providerId(actualProvider != null ? actualProvider.getId() : (dto != null ? dto.getProviderId() : null))
                .providerName(actualProvider != null ? actualProvider.getProviderName() : null)
                .modelCode(actualModelCode != null ? actualModelCode : (dto != null ? dto.getModelCode() : null))
                .status("RENDERING")
                .progress(0)
                .currentNode("正在生成关键帧并归档至 MinIO...")
                .build();
        renderTaskService.createTask(rTask);

        com.astra.freyja.service.RenderTaskThreadRegistry.register(taskId, Thread.currentThread());
        String outputUrl;
        try {
            outputUrl = aiImageApiService.generateAndArchiveImage(id, shot.getDramaId(), prompt, dto);
            if (isTaskCancelled(taskId)) {
                log.warn("[DramaShotService] 关键帧生图完成但任务已取消，终止落库: shotId={}, taskId={}", id, taskId);
                ComfyRenderTaskVO taskVO = new ComfyRenderTaskVO();
                taskVO.setTaskId(taskId);
                taskVO.setStatus(ComfyTaskStatus.CANCELED);
                taskVO.setProgress(0);
                taskVO.setShotId(id);
                taskVO.setProjectId(shot.getDramaId());
                return taskVO;
            }
            renderTaskService.finishTask(taskId, outputUrl, isEndFrame ? outputUrl : null);
        } catch (Exception e) {
            if (isTaskCancelled(taskId)) {
                log.info("[DramaShotService] 关键帧生图因任务取消中断: shotId={}, taskId={}", id, taskId);
                ComfyRenderTaskVO taskVO = new ComfyRenderTaskVO();
                taskVO.setTaskId(taskId);
                taskVO.setStatus(ComfyTaskStatus.CANCELED);
                return taskVO;
            }
            renderTaskService.failTask(taskId, e.getMessage(), org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(e));
            throw e;
        } finally {
            com.astra.freyja.service.RenderTaskThreadRegistry.unregister(taskId);
        }

        // 自动保存为分镜首帧或尾帧
        if (isEndFrame) {
            shot.setEndFrameImageUrl(outputUrl);
        } else {
            shot.setPreviewImageUrl(outputUrl);
            shot.setFirstFrameSourceType("AI_GENERATED");
            shot.setFirstFrameSourceShotId(null);
            shot.setFirstFrameSourceVideoUrl(null);
            shot.setRenderStatus("SUCCESS");
        }
        shotMapper.updateById(shot);

        ComfyRenderTaskVO taskVO = new ComfyRenderTaskVO();
        taskVO.setTaskId(taskId);
        taskVO.setStatus(ComfyTaskStatus.SUCCESS);
        taskVO.setProgress(100);
        taskVO.setOutputUrl(outputUrl);
        taskVO.setShotId(id);
        taskVO.setProjectId(shot.getDramaId());
        log.info("[DramaShotService] 分镜关键帧生图并归档成功: shotId={}, frameType={}, outputUrl={}", id, isEndFrame ? "END_FRAME" : "FIRST_FRAME", outputUrl);
        return taskVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setFirstFrame(Long id, String previewImageUrl) {
        if (StringUtils.isBlank(previewImageUrl)) {
            throw new BizException(400, "首帧图片 URL 不能为空");
        }
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + id);
        }
        shot.setPreviewImageUrl(previewImageUrl);
        shot.setFirstFrameSourceType("MANUAL_UPLOAD");
        shot.setFirstFrameSourceShotId(null);
        shot.setFirstFrameSourceVideoUrl(null);
        shotMapper.updateById(shot);
        log.info("[DramaShotService] 分镜确认为首帧图成功: shotId={}, previewImageUrl={}", id, previewImageUrl);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setEndFrame(Long id, String endFrameImageUrl) {
        if (StringUtils.isBlank(endFrameImageUrl)) {
            throw new BizException(400, "尾帧图片 URL 不能为空");
        }
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + id);
        }
        shot.setEndFrameImageUrl(endFrameImageUrl);
        shotMapper.updateById(shot);
        log.info("[DramaShotService] 分镜确认为尾帧图成功: shotId={}, endFrameImageUrl={}", id, endFrameImageUrl);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ComfyRenderTaskVO submitShotRender(Long id, DramaShotRenderRequestDTO requestDTO) {
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + id);
        }

        if (StringUtils.isBlank(shot.getPrompt())) {
            assembleShotPrompt(id);
            shot = shotMapper.selectById(id);
        }

        String taskId = "RENDER_" + System.currentTimeMillis();
        shot.setLatestTaskId(taskId);
        shot.setRenderStatus("QUEUED");
        shotMapper.updateById(shot);

        if (requestDTO != null) {
            requestDTO.setTaskId(taskId);
        }

        // 提前解析实际提供商与视频模型代码，固化至任务实体，杜绝取消时提供商路由丢失
        com.astra.freyja.entity.AiProvider actualProvider = null;
        String actualModelCode = null;
        try {
            actualProvider = aiImageApiService.resolveProvider(requestDTO != null ? requestDTO.getProviderId() : null);
            actualModelCode = aiImageApiService.resolveVideoModelCode(requestDTO, actualProvider.getId(), shot.getGenerationMode());
        } catch (Exception e) {
            log.warn("[DramaShotService] 预解析视频提供商与模型警告: {}", e.getMessage());
        }

        com.astra.freyja.dto.render.RenderTaskVO rTask = com.astra.freyja.dto.render.RenderTaskVO.builder()
                .taskId(taskId)
                .taskType("SHOT_VIDEO")
                .taskName(String.format("[S%d %s] 视频渲染", shot.getShotNo() != null ? shot.getShotNo() : 0,
                        StringUtils.defaultIfBlank(shot.getShotName(), "分镜")))
                .dramaId(shot.getDramaId())
                .episodeId(shot.getEpisodeId())
                .sceneId(shot.getSceneId())
                .shotId(id)
                .shotNo(shot.getShotNo())
                .shotGroupId(shot.getShotGroupId())
                .prompt(shot.getPrompt())
                .negativePrompt(shot.getNegativePrompt())
                .providerId(actualProvider != null ? actualProvider.getId() : (requestDTO != null ? requestDTO.getProviderId() : null))
                .providerName(actualProvider != null ? actualProvider.getProviderName() : null)
                .modelCode(actualModelCode)
                .status("QUEUED")
                .progress(0)
                .currentNode("等待渲染工坊调度...")
                .build();
        renderTaskService.createTask(rTask);

        ComfyRenderTaskVO taskVO = new ComfyRenderTaskVO();
        taskVO.setTaskId(taskId);
        taskVO.setStatus(ComfyTaskStatus.RUNNING);
        taskVO.setProgress(0);
        taskVO.setShotId(id);
        taskVO.setProjectId(shot.getDramaId());
        log.info("[DramaShotService] 提交分镜渲染成功: shotId={}, taskId={}", id, taskId);

        // 必须等当前事务提交后再启动异步线程，否则异步查询会读到旧 latestTaskId，
        // 随后的整行更新又可能把刚提交的任务关联覆盖为空。
        dispatchRenderAfterCommit(id, taskId, requestDTO);

        return taskVO;
    }

    public static void interruptTaskThread(String taskId) {
        if (StringUtils.isBlank(taskId)) return;
        com.astra.freyja.service.RenderTaskThreadRegistry.interrupt(taskId);
    }

    private void dispatchRenderAfterCommit(Long id, String taskId, DramaShotRenderRequestDTO requestDTO) {
        Runnable renderJob = () -> renderAsyncExecutor.execute(
                () -> executeShotVideoRenderAsync(id, taskId, requestDTO));
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    renderJob.run();
                }
            });
            return;
        }
        renderJob.run();
    }

    @Override
    public void executeShotVideoRenderAsync(Long id, String taskId, DramaShotRenderRequestDTO requestDTO) {
        log.info("[DramaShotService] 开始异步执行分镜视频渲染: shotId={}, taskId={}", id, taskId);
        com.astra.freyja.service.RenderTaskThreadRegistry.register(taskId, Thread.currentThread());
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null) {
            com.astra.freyja.service.RenderTaskThreadRegistry.unregister(taskId);
            log.error("[DramaShotService] 异步渲染失败，分镜不存在: shotId={}, taskId={}", id, taskId);
            renderTaskService.failTask(taskId, "分镜不存在: " + id, null);
            return;
        }

        if (isTaskCancelled(taskId)) {
            com.astra.freyja.service.RenderTaskThreadRegistry.unregister(taskId);
            log.warn("[DramaShotService] 异步渲染前检测到任务已取消，终止执行: shotId={}, taskId={}", id, taskId);
            return;
        }

        try {
            // 1. 更新任务状态为 RENDERING
            renderTaskService.updateProgress(taskId, 0, "正在生成视频并归档至 MinIO...");
            // 只按任务归属条件更新状态，禁止旧快照整行回写覆盖 latestTaskId。
            shotMapper.markRenderingIfLatest(id, taskId);

            if (isTaskCancelled(taskId)) {
                log.warn("[DramaShotService] 调用视频生成前检测到任务已取消，终止执行: shotId={}, taskId={}", id, taskId);
                return;
            }

            // 2. 调用生图/视频 API 服务生成视频并归档 MinIO
            com.astra.freyja.dto.drama.VideoGenerationResultVO resultVO = aiImageApiService.generateAndArchiveVideo(
                    id, shot.getDramaId(), shot, requestDTO);

            if (isTaskCancelled(taskId)) {
                log.warn("[DramaShotService] 视频生成返回后检测到任务已取消，丢弃结果并终止: shotId={}, taskId={}", id, taskId);
                return;
            }

            // 3. 记录视频候选版本 Take 并根据任务时间线自动选择
            RenderTask rTask = renderTaskMapper.selectOne(
                    new LambdaQueryWrapper<RenderTask>().eq(RenderTask::getTaskId, taskId)
            );
            DramaShotVideoTake take = shotVideoTakeService.recordGeneratedTake(shot, rTask, requestDTO, resultVO);

            // 4. 完成任务生命周期 (产物保存本次生成 URL)
            renderTaskService.finishTask(taskId, resultVO.getVideoUrl(), null);
            log.info("[DramaShotService] 异步执行分镜视频渲染完成: shotId={}, taskId={}, takeId={}, videoUrl={}",
                    id, taskId, take != null ? take.getId() : null, resultVO.getVideoUrl());
        } catch (Exception e) {
            if (isTaskCancelled(taskId) || Thread.currentThread().isInterrupted()) {
                log.info("[DramaShotService] 异步视频渲染因任务取消或中断退出: shotId={}, taskId={}", id, taskId);
                return;
            }
            log.error("[DramaShotService] 异步执行分镜视频渲染异常: shotId={}, taskId={}, error={}",
                    id, taskId, e.getMessage(), e);
            try {
                DramaShot failedShot = shotMapper.selectById(id);
                if (failedShot != null) {
                    failedShot.setRenderStatus("FAILED");
                    shotMapper.updateById(failedShot);
                }
            } catch (Exception ignored) {
            }
            renderTaskService.failTask(taskId, e.getMessage(), org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(e));
        } finally {
            com.astra.freyja.service.RenderTaskThreadRegistry.unregister(taskId);
        }
    }

    private boolean isTaskCancelled(String taskId) {
        if (StringUtils.isBlank(taskId)) return false;
        try {
            com.astra.freyja.dto.render.RenderTaskVO task = renderTaskService.getTaskById(taskId);
            return task != null && "CANCELLED".equalsIgnoreCase(task.getStatus());
        } catch (Exception e) {
            return false;
        }
    }

    // ==========================================================
    // 内部辅助方法
    // ==========================================================

    private DramaShotVO enrichShotVO(DramaShot shot) {
        DramaShotVO vo = new DramaShotVO();
        BeanUtils.copyProperties(shot, vo);

        // 1. 补充环境场景信息
        Long effectiveSceneId = shot.getResSceneId();
        if (effectiveSceneId == null || effectiveSceneId <= 0) {
            DramaScene scene = sceneMapper.selectById(shot.getSceneId());
            if (scene != null) {
                effectiveSceneId = scene.getResSceneId();
            }
        }
        if (effectiveSceneId != null && effectiveSceneId > 0) {
            ResScene resScene = resSceneMapper.selectById(effectiveSceneId);
            if (resScene != null) {
                vo.setResSceneName(resScene.getName());
                vo.setResSceneCoverUrl(resScene.getCoverUrl());
            }
        }

        // 2. 补充角色结构化展示列表
        List<CharacterShotRefDTO> refDTOs = parseCharacterRefs(shot.getCharacterRefsJson());
        if (!refDTOs.isEmpty()) {
            List<CharacterShotRefInfoVO> infoVOs = new ArrayList<>();
            for (CharacterShotRefDTO ref : refDTOs) {
                CharacterShotRefInfoVO info = new CharacterShotRefInfoVO();
                info.setCharacterId(ref.getCharacterId());
                info.setLookId(ref.getLookId());
                info.setActionPrompt(ref.getActionPrompt());
                info.setEmotionPrompt(ref.getEmotionPrompt());
                info.setPositionTag(ref.getPositionTag());

                if (ref.getCharacterId() != null) {
                    ResCharacter ch = characterMapper.selectById(ref.getCharacterId());
                    if (ch != null) {
                        info.setCharacterName(ch.getName());
                        info.setCharacterReferenceImageUrl(ch.getReferenceImageUrl());
                        info.setRoleType(ch.getRoleType());
                    }
                }
                Long lookId = ref.getLookId();
                if (lookId != null) {
                    ResCharacterOutfit outfit = outfitMapper.selectById(lookId);
                    if (outfit != null) {
                        info.setOutfitName(outfit.getLookName());
                        info.setOutfitReferenceImageUrl(outfit.getReferenceImageUrl());
                        info.setCharacterReferenceImageUrl(outfit.getReferenceImageUrl());
                        info.setDesignDesc(StringUtils.defaultIfBlank(ref.getDesignDesc(), outfit.getDesignDesc()));
                        info.setOutfitPrompt(outfit.getOutfitPrompt());
                        info.setAppearancePrompt(outfit.getAppearancePrompt());
                    } else if (StringUtils.isNotBlank(ref.getDesignDesc())) {
                        info.setDesignDesc(ref.getDesignDesc());
                    }
                } else if (ref.getCharacterId() != null) {
                    // 未明确指定造型时，尝试查询启用中的默认造型
                    ResCharacterOutfit defaultOutfit = outfitMapper.selectOne(new LambdaQueryWrapper<ResCharacterOutfit>()
                            .eq(ResCharacterOutfit::getCharacterId, ref.getCharacterId())
                            .eq(ResCharacterOutfit::getIsDefault, 1)
                            .eq(ResCharacterOutfit::getStatus, 1)
                            .last("LIMIT 1"));
                    if (defaultOutfit != null) {
                        info.setLookId(defaultOutfit.getId());
                        info.setOutfitName(defaultOutfit.getLookName());
                        info.setOutfitReferenceImageUrl(defaultOutfit.getReferenceImageUrl());
                        info.setCharacterReferenceImageUrl(defaultOutfit.getReferenceImageUrl());
                        info.setDesignDesc(StringUtils.defaultIfBlank(ref.getDesignDesc(), defaultOutfit.getDesignDesc()));
                        info.setOutfitPrompt(defaultOutfit.getOutfitPrompt());
                        info.setAppearancePrompt(defaultOutfit.getAppearancePrompt());
                    } else if (StringUtils.isNotBlank(ref.getDesignDesc())) {
                        info.setDesignDesc(ref.getDesignDesc());
                    }
                }
                infoVOs.add(info);
            }
            vo.setCharacterRefs(infoVOs);
        } else {
            vo.setCharacterRefs(Collections.emptyList());
        }

        // 2.5 补充道具结构化展示列表
        List<PropShotRefDTO> propRefDTOs = parsePropRefs(shot.getPropRefsJson());
        if (!propRefDTOs.isEmpty()) {
            List<PropShotRefInfoVO> propInfoVOs = new ArrayList<>();
            for (PropShotRefDTO ref : propRefDTOs) {
                PropShotRefInfoVO info = new PropShotRefInfoVO();
                info.setPropId(ref.getPropId());
                info.setPropName(ref.getPropName());
                info.setPropType(ref.getPropType());
                info.setPropPrompt(ref.getPropPrompt());
                if (ref.getPropId() != null && resPropMapper != null) {
                    ResProp p = resPropMapper.selectById(ref.getPropId());
                    if (p != null) {
                        if (StringUtils.isBlank(info.getPropName())) info.setPropName(p.getName());
                        if (StringUtils.isBlank(info.getPropType())) info.setPropType(p.getPropType());
                        if (StringUtils.isBlank(info.getPropPrompt())) info.setPropPrompt(p.getPropPrompt());
                        info.setCoverUrl(p.getCoverUrl());
                    }
                }
                propInfoVOs.add(info);
            }
            vo.setPropRefs(propInfoVOs);
        } else {
            vo.setPropRefs(Collections.emptyList());
        }

        // 3. 补充多模态参考图与参考音频列表
        vo.setRefImages(parseRefImages(shot.getRefImagesJson()));
        vo.setRefAudios(parseRefAudios(shot.getRefAudiosJson()));
        if (StringUtils.isBlank(vo.getGenerationMode())) {
            vo.setGenerationMode("FIRST_LAST_FRAME");
        }

        // 4. 补充渲染状态与产物实时信息
        vo.setRenderStatus(shot.getRenderStatus());
        vo.setVideoUrl(shot.getVideoUrl());
        vo.setCurrentVideoTakeId(shot.getCurrentVideoTakeId());
        vo.setLastFrameSourceTakeId(shot.getLastFrameSourceTakeId());
        vo.setFirstFrameSourceVideoTakeId(shot.getFirstFrameSourceVideoTakeId());
        if (shot.getId() != null) {
            Long takeCount = shotVideoTakeMapper.selectCount(
                    new LambdaQueryWrapper<DramaShotVideoTake>().eq(DramaShotVideoTake::getShotId, shot.getId())
            );
            vo.setVideoTakeCount(takeCount != null ? takeCount.intValue() : 0);
        }
        vo.setPreviewImageUrl(shot.getPreviewImageUrl());
        vo.setLastFrameUrl(shot.getLastFrameUrl());
        if ("SUCCESS".equalsIgnoreCase(shot.getRenderStatus())) {
            vo.setRenderProgress(100);
        } else if ("RENDERING".equalsIgnoreCase(shot.getRenderStatus())) {
            vo.setRenderProgress(50);
        } else if ("QUEUED".equalsIgnoreCase(shot.getRenderStatus())) {
            vo.setRenderProgress(10);
        } else {
            vo.setRenderProgress(0);
        }

        return vo;
    }

    private boolean isExplicitCameraConstraint(Boolean locked, String value) {
        return Boolean.TRUE.equals(locked) && StringUtils.isNotBlank(value) && !"AUTO".equalsIgnoreCase(value.trim());
    }

    private void applyCameraConstraintUpdate(DramaShot shot, String requestedValue, Boolean requestedLock,
                                             String existingValue, Boolean existingLock, boolean shotType) {
        String normalizedRequested = StringUtils.trimToNull(requestedValue);
        if (requestedLock == null && normalizedRequested == null) {
            // Older clients may omit these fields on an unrelated edit. Keep an existing explicit choice.
            normalizedRequested = StringUtils.trimToNull(existingValue);
            requestedLock = existingLock;
        } else if (requestedLock == null) {
            // A legacy client may still send a value without the new lock marker. Preserve an existing lock
            // only when the actual value has not changed; a new value is not proof of creator intent.
            requestedLock = Boolean.TRUE.equals(existingLock)
                    && StringUtils.equalsIgnoreCase(normalizedRequested, StringUtils.trimToNull(existingValue));
        }

        if (shotType) {
            shot.setShotType(normalizedRequested);
            shot.setShotTypeLocked(isExplicitCameraConstraint(requestedLock, normalizedRequested));
        } else {
            shot.setCameraMovement(normalizedRequested);
            shot.setCameraMovementLocked(isExplicitCameraConstraint(requestedLock, normalizedRequested));
        }
    }

    private List<CharacterShotRefDTO> parseCharacterRefs(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CharacterShotRefDTO>>() {});
        } catch (Exception e) {
            log.warn("[DramaShotService] 反序列化 characterRefsJson 异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<PropShotRefDTO> parsePropRefs(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<PropShotRefDTO>>() {});
        } catch (Exception e) {
            log.warn("[DramaShotService] 反序列化 propRefsJson 异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ShotRefImageDTO> parseRefImages(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ShotRefImageDTO>>() {});
        } catch (Exception e) {
            log.warn("[DramaShotService] 反序列化 refImagesJson 异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ShotRefAudioDTO> parseRefAudios(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ShotRefAudioDTO>>() {});
        } catch (Exception e) {
            log.warn("[DramaShotService] 反序列化 refAudiosJson 异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String resolveSizeFromDrama(Long dramaId) {
        if (dramaId != null) {
            try {
                Drama drama = dramaMapper.selectById(dramaId);
                if (drama != null && StringUtils.isNotBlank(drama.getAspectRatio())) {
                    return AspectRatioUtil.resolveSizeByAspectRatio(drama.getAspectRatio());
                }
            } catch (Exception e) {
                log.warn("[DramaShotService] 获取短剧画幅配置异常: dramaId={}, err={}", dramaId, e.getMessage());
            }
        }
        return AspectRatioUtil.DEFAULT_SHORT_DRAMA_SIZE;
    }
}
