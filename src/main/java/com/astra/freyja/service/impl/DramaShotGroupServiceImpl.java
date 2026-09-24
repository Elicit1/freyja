package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotGroupMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dto.drama.DramaShotGroupDTO;
import com.astra.freyja.dto.drama.DramaShotGroupMergeDTO;
import com.astra.freyja.dto.drama.DramaShotGroupReorderDTO;
import com.astra.freyja.dto.drama.DramaShotGroupSplitDTO;
import com.astra.freyja.dto.drama.DramaShotGroupVO;
import com.astra.freyja.dto.drama.DramaShotVO;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.DramaShotGroup;
import com.astra.freyja.service.DramaEpisodeService;
import com.astra.freyja.service.DramaShotGroupService;
import com.astra.freyja.service.DramaShotService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 镜头组管理服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaShotGroupServiceImpl implements DramaShotGroupService {

    private final DramaShotGroupMapper shotGroupMapper;
    private final DramaShotMapper shotMapper;
    private final DramaSceneMapper sceneMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaShotService shotService;
    private final DramaEpisodeService episodeService;

    @Override
    public List<DramaShotGroupVO> listBySceneId(Long sceneId) {
        if (sceneId == null) {
            return Collections.emptyList();
        }

        // 1. 获取所有镜头组
        List<DramaShotGroup> groups = shotGroupMapper.selectList(
                new LambdaQueryWrapper<DramaShotGroup>()
                        .eq(DramaShotGroup::getSceneId, sceneId)
                        .orderByAsc(DramaShotGroup::getGroupNo)
                        .orderByAsc(DramaShotGroup::getSortOrder)
        );

        // 2. 获取该场次下所有分镜
        List<DramaShotVO> allShots = shotService.listBySceneId(sceneId);

        // 3. 按 shotGroupId 分组
        Map<Long, List<DramaShotVO>> shotsByGroup = allShots.stream()
                .filter(s -> s.getShotGroupId() != null && s.getShotGroupId() > 0)
                .collect(Collectors.groupingBy(DramaShotVO::getShotGroupId));

        // 4. 若存在未分配 shotGroupId 的历史分镜，自动归入默认/第一个镜头组
        List<DramaShotVO> unassignedShots = allShots.stream()
                .filter(s -> s.getShotGroupId() == null || s.getShotGroupId() == 0)
                .toList();

        if (groups.isEmpty() && !allShots.isEmpty()) {
            // 为已有场次自动创建默认镜头组
            DramaScene scene = sceneMapper.selectById(sceneId);
            if (scene != null) {
                DramaShotGroup defaultGroup = new DramaShotGroup();
                defaultGroup.setSceneId(sceneId);
                defaultGroup.setEpisodeId(scene.getEpisodeId());
                defaultGroup.setDramaId(scene.getDramaId());
                defaultGroup.setGroupNo(1);
                defaultGroup.setName(scene.getName() + " - 连续镜头组 1");
                defaultGroup.setPurpose("默认动作与对白连续镜头组");
                defaultGroup.setSortOrder(1);
                shotGroupMapper.insert(defaultGroup);
                groups = List.of(defaultGroup);

                // 回填 shot_group_id
                for (DramaShotVO s : allShots) {
                    DramaShot updateEntity = new DramaShot();
                    updateEntity.setId(s.getId());
                    updateEntity.setShotGroupId(defaultGroup.getId());
                    shotMapper.updateById(updateEntity);
                    s.setShotGroupId(defaultGroup.getId());
                }
                shotsByGroup = Collections.singletonMap(defaultGroup.getId(), allShots);
                unassignedShots = Collections.emptyList();
            }
        } else if (!unassignedShots.isEmpty() && !groups.isEmpty()) {
            DramaShotGroup firstGroup = groups.get(0);
            for (DramaShotVO s : unassignedShots) {
                DramaShot updateEntity = new DramaShot();
                updateEntity.setId(s.getId());
                updateEntity.setShotGroupId(firstGroup.getId());
                shotMapper.updateById(updateEntity);
                s.setShotGroupId(firstGroup.getId());
            }
            List<DramaShotVO> combined = new ArrayList<>(shotsByGroup.getOrDefault(firstGroup.getId(), Collections.emptyList()));
            combined.addAll(unassignedShots);
            shotsByGroup.put(firstGroup.getId(), combined);
        }

        List<DramaShotGroupVO> result = new ArrayList<>();
        for (DramaShotGroup group : groups) {
            DramaShotGroupVO vo = enrichGroupVO(group);
            List<DramaShotVO> groupShots = shotsByGroup.getOrDefault(group.getId(), Collections.emptyList());
            vo.setShots(groupShots);
            vo.setShotCount(groupShots.size());
            vo.setRenderedShotCount((int) groupShots.stream().filter(s -> "SUCCESS".equalsIgnoreCase(s.getRenderStatus())).count());

            BigDecimal totalDur = BigDecimal.ZERO;
            for (DramaShotVO s : groupShots) {
                if (s.getDuration() != null) {
                    totalDur = totalDur.add(s.getDuration());
                }
            }
            vo.setTotalDuration(totalDur);

            result.add(vo);
        }

        return result;
    }

    @Override
    public List<DramaShotGroupVO> listByEpisodeId(Long episodeId) {
        if (episodeId == null) {
            return Collections.emptyList();
        }
        List<DramaShotGroup> groups = shotGroupMapper.selectList(
                new LambdaQueryWrapper<DramaShotGroup>()
                        .eq(DramaShotGroup::getEpisodeId, episodeId)
                        .orderByAsc(DramaShotGroup::getSceneId)
                        .orderByAsc(DramaShotGroup::getGroupNo)
        );
        return groups.stream().map(this::enrichGroupVO).collect(Collectors.toList());
    }

    @Override
    public DramaShotGroupVO getById(Long id) {
        DramaShotGroup group = shotGroupMapper.selectById(id);
        if (group == null) {
            throw new BizException(404, "镜头组不存在: " + id);
        }
        DramaShotGroupVO vo = enrichGroupVO(group);
        List<DramaShotVO> allShots = shotService.listBySceneId(group.getSceneId());
        List<DramaShotVO> groupShots = allShots.stream()
                .filter(s -> Objects.equals(s.getShotGroupId(), id))
                .collect(Collectors.toList());
        vo.setShots(groupShots);
        vo.setShotCount(groupShots.size());
        vo.setRenderedShotCount((int) groupShots.stream().filter(s -> "SUCCESS".equalsIgnoreCase(s.getRenderStatus())).count());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DramaShotGroupDTO dto) {
        if (dto == null || dto.getSceneId() == null) {
            throw new BizException(400, "场次 ID 不能为空");
        }
        DramaScene scene = sceneMapper.selectById(dto.getSceneId());
        if (scene == null) {
            throw new BizException(404, "所属场次不存在: " + dto.getSceneId());
        }

        DramaShotGroup group = new DramaShotGroup();
        BeanUtils.copyProperties(dto, group);
        group.setSceneId(scene.getId());
        group.setEpisodeId(scene.getEpisodeId());
        group.setDramaId(scene.getDramaId());

        if (group.getGroupNo() == null || group.getGroupNo() <= 0) {
            List<DramaShotGroup> existing = shotGroupMapper.selectList(
                    new LambdaQueryWrapper<DramaShotGroup>()
                            .eq(DramaShotGroup::getSceneId, dto.getSceneId())
                            .orderByDesc(DramaShotGroup::getGroupNo)
            );
            int nextNo = existing.isEmpty() ? 1 : existing.get(0).getGroupNo() + 1;
            group.setGroupNo(nextNo);
        }
        if (StringUtils.isBlank(group.getName())) {
            group.setName(String.format("镜头组 %02d", group.getGroupNo()));
        }
        if (group.getSortOrder() == null) {
            group.setSortOrder(group.getGroupNo());
        }

        shotGroupMapper.insert(group);
        log.info("[ShotGroupService] 创建镜头组成功: id={}, sceneId={}, groupNo={}", group.getId(), group.getSceneId(), group.getGroupNo());
        return group.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(DramaShotGroupDTO dto) {
        if (dto == null || dto.getId() == null) {
            throw new BizException(400, "镜头组 ID 不能为空");
        }
        DramaShotGroup existing = shotGroupMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BizException(404, "镜头组不存在: " + dto.getId());
        }

        DramaShotGroup group = new DramaShotGroup();
        BeanUtils.copyProperties(dto, group);

        shotGroupMapper.updateById(group);
        log.info("[ShotGroupService] 更新镜头组成功: id={}", group.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        DramaShotGroup existing = shotGroupMapper.selectById(id);
        if (existing == null) {
            return;
        }

        // 处理该镜头组下的所有分镜
        List<DramaShot> childShots = shotMapper.selectList(
                new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getShotGroupId, id)
        );

        if (!childShots.isEmpty()) {
            // 查找同场次下的其他镜头组
            List<DramaShotGroup> otherGroups = shotGroupMapper.selectList(
                    new LambdaQueryWrapper<DramaShotGroup>()
                            .eq(DramaShotGroup::getSceneId, existing.getSceneId())
                            .ne(DramaShotGroup::getId, id)
                            .orderByAsc(DramaShotGroup::getGroupNo)
            );
            if (!otherGroups.isEmpty()) {
                // 移入其他镜头组
                DramaShotGroup targetGroup = otherGroups.get(0);
                for (DramaShot s : childShots) {
                    s.setShotGroupId(targetGroup.getId());
                    shotMapper.updateById(s);
                }
            } else {
                // 若没有其他镜头组，删除所属分镜
                shotMapper.delete(new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getShotGroupId, id));
            }
        }

        shotGroupMapper.deleteById(id);
        log.info("[ShotGroupService] 删除镜头组成功: id={}", id);

        if (existing.getEpisodeId() != null) {
            episodeService.recalculateEpisodeDuration(existing.getEpisodeId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long splitGroup(DramaShotGroupSplitDTO dto) {
        if (dto == null || dto.getSourceGroupId() == null || dto.getSplitAtShotId() == null) {
            throw new BizException(400, "拆分源镜头组或拆分分镜 ID 不能为空");
        }

        DramaShotGroup sourceGroup = shotGroupMapper.selectById(dto.getSourceGroupId());
        if (sourceGroup == null) {
            throw new BizException(404, "源镜头组不存在: " + dto.getSourceGroupId());
        }

        List<DramaShot> allShots = shotMapper.selectList(
                new LambdaQueryWrapper<DramaShot>()
                        .eq(DramaShot::getShotGroupId, dto.getSourceGroupId())
                        .orderByAsc(DramaShot::getShotNo)
                        .orderByAsc(DramaShot::getSortOrder)
        );

        int splitIdx = -1;
        for (int i = 0; i < allShots.size(); i++) {
            if (Objects.equals(allShots.get(i).getId(), dto.getSplitAtShotId())) {
                splitIdx = i;
                break;
            }
        }

        if (splitIdx <= 0) {
            throw new BizException(400, "拆分分镜必须在镜头组第 2 个分镜或之后");
        }

        // 1. 创建新镜头组
        int nextGroupNo = sourceGroup.getGroupNo() + 1;
        // 先给后续组 groupNo 后移
        List<DramaShotGroup> subsequentGroups = shotGroupMapper.selectList(
                new LambdaQueryWrapper<DramaShotGroup>()
                        .eq(DramaShotGroup::getSceneId, sourceGroup.getSceneId())
                        .ge(DramaShotGroup::getGroupNo, nextGroupNo)
        );
        for (DramaShotGroup g : subsequentGroups) {
            g.setGroupNo(g.getGroupNo() + 1);
            g.setSortOrder(g.getGroupNo());
            shotGroupMapper.updateById(g);
        }

        DramaShotGroup newGroup = new DramaShotGroup();
        newGroup.setSceneId(sourceGroup.getSceneId());
        newGroup.setEpisodeId(sourceGroup.getEpisodeId());
        newGroup.setDramaId(sourceGroup.getDramaId());
        newGroup.setGroupNo(nextGroupNo);
        newGroup.setName(StringUtils.defaultIfBlank(dto.getNewGroupName(), sourceGroup.getName() + " (拆分部分)"));
        newGroup.setPurpose(dto.getNewGroupPurpose());
        newGroup.setSortOrder(nextGroupNo);

        shotGroupMapper.insert(newGroup);

        // 2. 将拆分点及之后的分镜移入新组
        for (int i = splitIdx; i < allShots.size(); i++) {
            DramaShot s = allShots.get(i);
            s.setShotGroupId(newGroup.getId());
            shotMapper.updateById(s);
        }

        log.info("[ShotGroupService] 镜头组拆分成功: sourceGroupId={}, newGroupId={}, splitAtShotId={}",
                sourceGroup.getId(), newGroup.getId(), dto.getSplitAtShotId());
        return newGroup.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long mergeGroups(DramaShotGroupMergeDTO dto) {
        if (dto == null || dto.getSourceGroupIds() == null || dto.getSourceGroupIds().size() < 2) {
            throw new BizException(400, "待合并的镜头组数量必须至少为 2 个");
        }

        List<Long> groupIds = dto.getSourceGroupIds();
        Long primaryGroupId = dto.getTargetGroupId() != null ? dto.getTargetGroupId() : groupIds.get(0);
        DramaShotGroup primaryGroup = shotGroupMapper.selectById(primaryGroupId);
        if (primaryGroup == null) {
            throw new BizException(404, "目标主镜头组不存在: " + primaryGroupId);
        }

        if (StringUtils.isNotBlank(dto.getMergedGroupName())) {
            primaryGroup.setName(dto.getMergedGroupName());
        }

        List<DramaShot> allMergedShots = new ArrayList<>();

        for (Long gid : groupIds) {
            List<DramaShot> shots = shotMapper.selectList(
                    new LambdaQueryWrapper<DramaShot>()
                            .eq(DramaShot::getShotGroupId, gid)
                            .orderByAsc(DramaShot::getShotNo)
                            .orderByAsc(DramaShot::getSortOrder)
            );
            for (DramaShot s : shots) {
                s.setShotGroupId(primaryGroupId);
                shotMapper.updateById(s);
                allMergedShots.add(s);
            }
            if (!Objects.equals(gid, primaryGroupId)) {
                shotGroupMapper.deleteById(gid);
            }
        }

        // 重新编排 shotNo
        for (int i = 0; i < allMergedShots.size(); i++) {
            DramaShot s = allMergedShots.get(i);
            s.setShotNo(i + 1);
            s.setSortOrder(i + 1);
            shotMapper.updateById(s);
        }

        shotGroupMapper.updateById(primaryGroup);

        log.info("[ShotGroupService] 镜头组合并成功: primaryGroupId={}, mergedShotCount={}", primaryGroupId, allMergedShots.size());
        return primaryGroupId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorderShotsInGroup(Long groupId, List<Long> shotIds) {
        if (groupId == null || shotIds == null || shotIds.isEmpty()) {
            throw new BizException(400, "参数不能为空");
        }
        DramaShotGroup group = shotGroupMapper.selectById(groupId);
        if (group == null) {
            throw new BizException(404, "镜头组不存在: " + groupId);
        }

        for (int i = 0; i < shotIds.size(); i++) {
            Long sid = shotIds.get(i);
            DramaShot updateEntity = new DramaShot();
            updateEntity.setId(sid);
            updateEntity.setShotGroupId(groupId);
            updateEntity.setSceneId(group.getSceneId());
            updateEntity.setEpisodeId(group.getEpisodeId());
            updateEntity.setDramaId(group.getDramaId());
            updateEntity.setShotNo(i + 1);
            updateEntity.setSortOrder(i + 1);
            shotMapper.updateById(updateEntity);
        }

        log.info("[ShotGroupService] 组内分镜重排完成: groupId={}, count={}", groupId, shotIds.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorderGroups(DramaShotGroupReorderDTO dto) {
        if (dto == null || dto.getSceneId() == null || dto.getGroupIds() == null || dto.getGroupIds().isEmpty()) {
            throw new BizException(400, "参数不能为空");
        }

        List<Long> groupIds = dto.getGroupIds();
        for (int i = 0; i < groupIds.size(); i++) {
            Long gid = groupIds.get(i);
            DramaShotGroup updateEntity = new DramaShotGroup();
            updateEntity.setId(gid);
            updateEntity.setGroupNo(i + 1);
            updateEntity.setSortOrder(i + 1);
            shotGroupMapper.updateById(updateEntity);
        }

        log.info("[ShotGroupService] 场次内镜头组重排完成: sceneId={}, count={}", dto.getSceneId(), groupIds.size());
    }

    private DramaShotGroupVO enrichGroupVO(DramaShotGroup group) {
        DramaShotGroupVO vo = new DramaShotGroupVO();
        BeanUtils.copyProperties(group, vo);
        return vo;
    }
}
