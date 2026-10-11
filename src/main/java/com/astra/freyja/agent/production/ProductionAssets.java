package com.astra.freyja.agent.production;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.agent.ProductionPreparationDTO.*;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.astra.freyja.dto.drama.manifest.KeyImageRole;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** The model chooses an asset; this service only validates scope and resolves its real image. */
@Service @RequiredArgsConstructor
public class ProductionAssets {
    private final ResCharacterMapper characters;
    private final ResCharacterLookMapper looks;
    private final ResSceneMapper scenes;
    private final ResPropMapper props;
    private final ResKeyframeMapper keyframes;
    private final ResCharacterService characterService;
    private final ResCharacterLookService lookService;
    private final ResSceneService sceneService;
    private final ResPropService propService;
    private final ResKeyframeService keyframeService;
    private final CharacterVisualAssetResolver visual;
    private final AgentJson json;

    public record Material(String type, Long id, Long characterId, String name, String description,
                           String prompt, String imageUrl, String imageStatus, Object basis) {}
    public static void scope(Long assetDrama, Long drama) {
        if (drama == null || assetDrama != null && assetDrama != 0 && !Objects.equals(assetDrama, drama))
            throw new BizException(400, "SCOPE_VIOLATION: 资产不属于当前项目或公共库");
    }
    public Material resolve(String type, Long id, Long drama) {
        if (id == null || id <= 0) throw new BizException(400, "需要工具返回的真实资产 ID");
        return switch (Objects.toString(type, "")) {
            case "CHARACTER" -> {
                ResCharacter c = characters.selectById(id);
                if (c == null || !Objects.equals(c.getStatus(), 1) || c.getMergedToId() != null) throw new BizException("人物不存在、已合并或停用");
                scope(c.getDramaId(), drama);
                yield new Material(type, id, id, c.getName(), c.getAppearanceDesc(), c.getAppearancePrompt(), null, "MISSING", c);
            }
            case "CHARACTER_LOOK" -> {
                ResCharacterLook l = looks.selectById(id);
                if (l == null || !Objects.equals(l.getStatus(), 1)) throw new BizException("造型不存在或已停用");
                Material c = resolve("CHARACTER", l.getCharacterId(), drama);
                ResolvedCharacterVisual v = visual.resolve(l.getCharacterId(), id);
                String status = v.getImageStatus();
                String url = v.getReferenceImageUrl();
                if (!validImageUrl(url)) status = "MISSING";
                yield new Material(type, id, l.getCharacterId(), c.name() + " · " + l.getLookName(),
                        l.getDesignDesc(), String.join("\n", v.getPositivePromptSegments()), url, status,
                        List.of(c.basis(), l));
            }
            case "SCENE" -> {
                ResScene s = scenes.selectById(id);
                if (s == null || !Objects.equals(s.getStatus(), 1)) throw new BizException("场景不存在或已停用");
                scope(s.getDramaId(), drama);
                yield material(type, id, s.getName(), s.getDescription(), s.getScenePrompt(),
                        text(s.getReferenceImageUrl()) ? s.getReferenceImageUrl() : s.getCoverUrl(), s);
            }
            case "PROP" -> {
                ResProp p = props.selectById(id);
                if (p == null || !Objects.equals(p.getStatus(), 1)) throw new BizException("道具不存在或已停用");
                scope(p.getDramaId(), drama);
                yield material(type, id, p.getName(), p.getDescription(), p.getPropPrompt(), p.getCoverUrl(), p);
            }
            case "KEYFRAME" -> {
                ResKeyframe k = keyframes.selectById(id);
                if (k == null || !Objects.equals(k.getStatus(), 1)) throw new BizException("关键图不存在或已停用");
                scope(k.getDramaId(), drama);
                yield material(type, id, k.getName(), k.getDescription(), k.getPrompt(), k.getFrameUrl(), k);
            }
            default -> throw new BizException(400, "不支持的资产类型: " + type);
        };
    }
    private Material material(String type, Long id, String name, String desc, String prompt, String url, Object basis) {
        return new Material(type, id, null, name, desc, prompt, url, validImageUrl(url) ? "SYNCED" : "MISSING", basis);
    }
    public static boolean text(String s) { return s != null && !s.isBlank(); }
    public static boolean validImageUrl(String url) {
        if (!text(url)) return false;
        try {
            var uri = java.net.URI.create(url);
            return uri.getScheme() == null && url.startsWith("/") && !url.startsWith("//")
                    || Set.of("https", "http").contains(Objects.toString(uri.getScheme(), "")) && uri.getHost() != null;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    public List<Material> catalog(Long drama, boolean publicLibrary) {
        if (drama == null) return List.of();
        List<Material> result = new ArrayList<>();
        var cs = characters.selectList(new LambdaQueryWrapper<ResCharacter>().eq(ResCharacter::getStatus, 1)
                .and(q -> { q.eq(ResCharacter::getDramaId, drama); if (publicLibrary) q.or().eq(ResCharacter::getDramaId, 0L).or().isNull(ResCharacter::getDramaId); })
                .orderByAsc(ResCharacter::getId).last("LIMIT 200"));
        for (var c : cs) {
            if (c.getMergedToId() != null) continue;
            result.add(resolve("CHARACTER", c.getId(), drama));
            for (var l : looks.selectList(new LambdaQueryWrapper<ResCharacterLook>().eq(ResCharacterLook::getCharacterId, c.getId())
                    .eq(ResCharacterLook::getStatus, 1).orderByAsc(ResCharacterLook::getId))) result.add(resolve("CHARACTER_LOOK", l.getId(), drama));
        }
        scenes.selectList(new LambdaQueryWrapper<ResScene>().eq(ResScene::getStatus, 1).and(q -> {
            q.eq(ResScene::getDramaId, drama); if (publicLibrary) q.or().eq(ResScene::getDramaId, 0L).or().isNull(ResScene::getDramaId);
        }).orderByAsc(ResScene::getId).last("LIMIT 200")).forEach(s -> result.add(resolve("SCENE", s.getId(), drama)));
        props.selectList(new LambdaQueryWrapper<ResProp>().eq(ResProp::getStatus, 1).and(q -> {
            q.eq(ResProp::getDramaId, drama); if (publicLibrary) q.or().eq(ResProp::getDramaId, 0L).or().isNull(ResProp::getDramaId);
        }).orderByAsc(ResProp::getId).last("LIMIT 200")).forEach(p -> result.add(resolve("PROP", p.getId(), drama)));
        keyframes.selectList(new LambdaQueryWrapper<ResKeyframe>().eq(ResKeyframe::getStatus, 1).and(q -> {
            q.eq(ResKeyframe::getDramaId, drama); if (publicLibrary) q.or().eq(ResKeyframe::getDramaId, 0L).or().isNull(ResKeyframe::getDramaId);
        }).orderByAsc(ResKeyframe::getId).last("LIMIT 200")).forEach(k -> result.add(resolve("KEYFRAME", k.getId(), drama)));
        return result;
    }
    public Map<String, Object> candidate(Material m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", m.type()); out.put("id", m.id()); out.put("characterId", m.characterId());
        out.put("name", m.name()); out.put("description", m.description()); out.put("prompt", m.prompt());
        out.put("imageUrl", "SYNCED".equals(m.imageStatus()) ? m.imageUrl() : null); out.put("imageStatus", m.imageStatus());
        if (m.basis() instanceof List<?> basis && basis.size() == 2 && basis.get(0) instanceof ResCharacter c && basis.get(1) instanceof ResCharacterLook l)
            out.put("negativeConstraints", Arrays.asList(c.getNegativePrompt(), l.getNegativePrompt()));
        if (m.basis() instanceof ResScene s) out.put("negativeConstraints", s.getNegativePrompt());
        if (m.basis() instanceof ResKeyframe k) out.put("negativeConstraints", k.getNegativePrompt());
        if (m.basis() instanceof ResKeyframe k) { out.put("frameType", k.getFrameType()); out.put("shotId", k.getShotId()); }
        return out;
    }
    public Long create(Asset a, Long drama, String aspect) {
        if (!text(a.getKey()) || a.getKey().length() > 120 || !text(a.getName()) || a.getName().length() > 100)
            throw new BizException("资产 key 与 name 必填且长度合法");
        if (!text(a.getDescription()) || !text(a.getPrompt())) throw new BizException("文字资产需要完整 description 和 prompt");
        return switch (Objects.toString(a.getType(), "")) {
            case "CHARACTER" -> {
                var d = new ResCharacterDTO(); d.setDramaId(drama); d.setName(a.getName()); d.setCanonicalName(a.getCanonicalName());
                d.setAliases(a.getAliases()); d.setPersonality(a.getPersonality()); d.setAppearanceDesc(a.getDescription());
                d.setAppearancePrompt(a.getPrompt()); d.setDefaultOutfitName(a.getName() + "基础造型");
                d.setDefaultOutfitPrompt(a.getOutfitPrompt()); yield characterService.create(d);
            }
            case "CHARACTER_LOOK" -> {
                Material c = resolve("CHARACTER", a.getCharacterId(), drama);
                ResCharacter entity = (ResCharacter)c.basis();
                if (!Objects.equals(entity.getDramaId(), drama)) throw new BizException("公共人物为只读复用，不能在本任务新增其造型");
                var d = new ResCharacterLookDTO(); d.setCharacterId(a.getCharacterId()); d.setLookName(a.getName());
                d.setLookType(a.getLookType()); d.setDesignDesc(a.getDescription()); d.setAppearancePrompt(a.getPrompt());
                d.setOutfitPrompt(a.getOutfitPrompt()); d.setIsDefault(0); yield lookService.create(d);
            }
            case "SCENE" -> {
                var d = new ResSceneDTO(); d.setDramaId(drama); d.setName(a.getName()); d.setDescription(a.getDescription());
                d.setScenePrompt(a.getPrompt()); d.setSceneType(a.getSceneType()); d.setTimeOfDay(a.getTimeOfDay());
                d.setWeatherAtmosphere(a.getWeatherAtmosphere()); yield sceneService.create(d);
            }
            case "PROP" -> {
                var d = new ResPropDTO(); d.setDramaId(drama); d.setName(a.getName()); d.setDescription(a.getDescription());
                d.setPropPrompt(a.getPrompt()); d.setPropType(a.getPropType()); yield propService.create(d);
            }
            case "KEYFRAME" -> {
                var d = new ResKeyframeDTO(); d.setDramaId(drama); d.setShotId(a.getShotId()); d.setName(a.getName());
                d.setDescription(a.getDescription()); d.setPrompt(a.getPrompt()); d.setFrameType(KeyImageRole.requireCode(a.getFrameType()).name());
                d.setSourceType("TEXT_DRAFT"); d.setAspectRatio(aspect); yield keyframeService.create(d);
            }
            default -> throw new BizException("不支持的文字资产类型");
        };
    }
    public void updateText(Asset a, Long drama) {
        Material m = resolve(a.getType(), a.getExistingId(), drama);
        Object entity = m.basis();
        if (entity instanceof List<?> basis) entity = basis.getLast();
        Long owner = m.characterId() != null ? ((ResCharacter) resolve("CHARACTER", m.characterId(), drama).basis()).getDramaId()
                : (Long) new org.springframework.beans.BeanWrapperImpl(entity).getPropertyValue("dramaId");
        if (!Objects.equals(owner, drama)) throw new BizException("公共库资产为只读复用，不能修改文字设定");
        if (!text(a.getDescription()) || !text(a.getPrompt())) throw new BizException("补齐文字需要 description 和 prompt");
        switch (m.type()) {
            case "CHARACTER" -> {
                var d = new ResCharacterDTO(); org.springframework.beans.BeanUtils.copyProperties(entity, d);
                d.setAppearanceDesc(a.getDescription()); d.setAppearancePrompt(a.getPrompt()); characterService.update(d);
            }
            case "CHARACTER_LOOK" -> {
                var d = new ResCharacterLookDTO(); org.springframework.beans.BeanUtils.copyProperties(entity, d);
                d.setDesignDesc(a.getDescription()); d.setAppearancePrompt(a.getPrompt());
                if (text(a.getOutfitPrompt())) d.setOutfitPrompt(a.getOutfitPrompt()); lookService.update(d);
            }
            case "SCENE" -> {
                var d = new ResSceneDTO(); org.springframework.beans.BeanUtils.copyProperties(entity, d);
                d.setDescription(a.getDescription()); d.setScenePrompt(a.getPrompt()); sceneService.update(d);
            }
            case "PROP" -> {
                var d = new ResPropDTO(); org.springframework.beans.BeanUtils.copyProperties(entity, d);
                d.setDescription(a.getDescription()); d.setPropPrompt(a.getPrompt()); propService.update(d);
            }
            case "KEYFRAME" -> {
                var d = new ResKeyframeDTO(); org.springframework.beans.BeanUtils.copyProperties(entity, d);
                d.setDescription(a.getDescription()); d.setPrompt(a.getPrompt()); keyframeService.update(d);
            }
            default -> throw new BizException("不支持的文字资产类型");
        }
    }
    public ShotRefImageDTO reference(ReferenceSlot slot, Long drama) {
        Material m = resolve(slot.getSourceType(), slot.getSourceId(), drama);
        if ("CHARACTER_LOOK".equals(m.type())) {
            if (!Objects.equals(m.characterId(), slot.getCharacterId()) || !Objects.equals(m.id(), slot.getLookId()))
                throw new BizException("人物参考图需要正确的 characterId / lookId / sourceId");
        }
        if (!"SYNCED".equals(m.imageStatus()) || !text(m.imageUrl())) return null;
        return ShotRefImageDTO.builder().id("agent-" + slot.getSlotKey()).sourceType("CHARACTER_LOOK".equals(m.type()) ? "CHARACTER_REFERENCE" : m.type())
                .sourceId(m.id()).characterId(m.characterId()).lookId("CHARACTER_LOOK".equals(m.type()) ? m.id() : null)
                .name(m.name()).imageUrl(m.imageUrl()).usageRole(slot.getUsageRole()).referenceRole(slot.getReferenceRole()).build();
    }
}
