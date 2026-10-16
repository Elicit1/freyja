package com.astra.freyja.service.mediaexport;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.VideoExportProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.astra.freyja.service.mediaexport.VideoExportModels.*;

@Service
@RequiredArgsConstructor
public class ShotVideoExportSelection {
    private final DramaMapper dramas;
    private final DramaEpisodeMapper episodes;
    private final DramaSceneMapper scenes;
    private final DramaShotGroupMapper groups;
    private final DramaShotMapper shots;
    private final DramaShotVideoTakeMapper takes;
    private final VideoAssetSourceResolver sources;
    private final VideoExportProperties properties;
    private final ObjectMapper json;

    private record Outline(Drama drama, Map<Long,DramaEpisode> episodes, Map<Long,DramaScene> scenes,
                           Map<Long,DramaShotGroup> groups, List<DramaShot> shots) {}
    private Outline outline(Long dramaId) {
        Drama drama=dramaId==null?null:dramas.selectById(dramaId);
        if(drama==null)throw new BizException(404,"短剧不存在");
        var ep=map(episodes.selectList(new LambdaQueryWrapper<DramaEpisode>().eq(DramaEpisode::getDramaId,dramaId)),DramaEpisode::getId);
        var sc=map(scenes.selectList(new LambdaQueryWrapper<DramaScene>().eq(DramaScene::getDramaId,dramaId)),DramaScene::getId);
        sc.values().removeIf(s->!ep.containsKey(s.getEpisodeId()));
        var gr=map(groups.selectList(new LambdaQueryWrapper<DramaShotGroup>().eq(DramaShotGroup::getDramaId,dramaId)),DramaShotGroup::getId);
        gr.values().removeIf(g->!sc.containsKey(g.getSceneId())||!Objects.equals(sc.get(g.getSceneId()).getEpisodeId(),g.getEpisodeId()));
        var all=new ArrayList<>(shots.selectList(new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getDramaId,dramaId)));
        all.sort(Comparator.comparing((DramaShot s)->ep.get(s.getEpisodeId()),Comparator.nullsLast(Comparator.comparing(DramaEpisode::getSortOrder,Comparator.nullsLast(Integer::compareTo)).thenComparing(DramaEpisode::getEpisodeNo,Comparator.nullsLast(Integer::compareTo)).thenComparing(DramaEpisode::getId)))
                .thenComparing(s->sc.get(s.getSceneId()),Comparator.nullsLast(Comparator.comparing(DramaScene::getSortOrder,Comparator.nullsLast(Integer::compareTo)).thenComparing(DramaScene::getSceneNo,Comparator.nullsLast(Integer::compareTo)).thenComparing(DramaScene::getId)))
                .thenComparing(s->gr.get(s.getShotGroupId()),Comparator.nullsLast(Comparator.comparing(DramaShotGroup::getSortOrder,Comparator.nullsLast(Integer::compareTo)).thenComparing(DramaShotGroup::getGroupNo,Comparator.nullsLast(Integer::compareTo)).thenComparing(DramaShotGroup::getId)))
                .thenComparing(DramaShot::getSortOrder,Comparator.nullsLast(Integer::compareTo)).thenComparing(DramaShot::getShotNo,Comparator.nullsLast(Integer::compareTo)).thenComparing(DramaShot::getId));
        return new Outline(drama,ep,sc,gr,all);
    }
    private static <T> Map<Long,T> map(List<T> values,Function<T,Long> id){return values.stream().collect(Collectors.toMap(id,Function.identity()));}

    public List<Node> tree(Long dramaId) {
        var o=outline(dramaId);Map<String,Node> nodes=new LinkedHashMap<>();List<Node> roots=new ArrayList<>();
        for(var s:o.shots()) {
            var ep=o.episodes().get(s.getEpisodeId());var sc=o.scenes().get(s.getSceneId());var gr=o.groups().get(s.getShotGroupId());
            boolean valid=ep!=null&&sc!=null&&Objects.equals(sc.getEpisodeId(),ep.getId());
            Node e=node(nodes,valid?"EPISODE:"+ep.getId():"CONTAINER:ORPHAN",valid?"EPISODE":"CONTAINER",valid?ep.getId():null,valid?ep.getTitle():"未归属分集",roots);
            Node c=node(nodes,valid?"SCENE:"+sc.getId():"CONTAINER:ORPHAN_SCENE",valid?"SCENE":"CONTAINER",valid?sc.getId():null,valid?sc.getName():"未归属场景",e.getChildren());
            boolean validGroup=valid&&gr!=null&&Objects.equals(gr.getSceneId(),sc.getId());
            Node g=validGroup?node(nodes,"SHOT_GROUP:"+gr.getId(),"SHOT_GROUP",gr.getId(),gr.getName(),c.getChildren()):node(nodes,c.getKey()+":UNGROUPED","CONTAINER",null,"未归组",c.getChildren());
            node(nodes,"SHOT:"+s.getId(),"SHOT",s.getId(),"S"+s.getShotNo()+" · "+StringUtils.defaultString(s.getShotName(),"分镜"),g.getChildren());
            for(Node n:List.of(e,c,g,nodes.get("SHOT:"+s.getId()))){n.setShotCount(n.getShotCount()+1);if(StringUtils.isNotBlank(s.getVideoUrl()))n.setVideoCount(n.getVideoCount()+1);}
        }
        return roots;
    }
    private Node node(Map<String,Node> all,String key,String type,Long id,String label,List<Node> parent){return all.computeIfAbsent(key,k->{Node n=new Node();n.setKey(k);n.setType(type);n.setId(id);n.setLabel(StringUtils.defaultIfBlank(label,type));parent.add(n);return n;});}

    public Preview preview(Request req) { return prepare(req,null); }
    /** Supplying previous snapshots avoids storage I/O inside the create transaction. */
    public Preview prepare(Request req, Map<Long,Snapshot> previous) {
        if(req==null||!Set.of("SKIP","FAIL").contains(StringUtils.defaultString(req.getMissingVideoPolicy())))throw new BizException(400,"缺失视频策略无效");
        if(req.getShotIds()!=null&&req.getSelection()!=null)throw new BizException(400,"shotIds 和 selection 不能同时提交");
        var o=outline(req.getDramaId());Set<Long> selected=new HashSet<>();
        if(req.getShotIds()!=null)selected.addAll(req.getShotIds());
        else if(req.getSelection()!=null&&req.getSelection().getInclude()!=null){
            for(var scope:req.getSelection().getInclude()){
                if(scope==null||scope.getType()==null)throw new BizException(400,"选择范围无效");
                if(!"ALL".equals(scope.getType())&&scope.getId()==null)throw new BizException(400,"选择范围缺少 ID");
                boolean validScope=switch(scope.getType()){case "ALL","SHOT"->true;case "EPISODE"->o.episodes().containsKey(scope.getId());case "SCENE"->o.scenes().containsKey(scope.getId());case "SHOT_GROUP"->o.groups().containsKey(scope.getId());default->false;};
                if(!validScope)throw new BizException(400,"范围不存在或不属于所选短剧");
                var matching=o.shots().stream().filter(s->switch(scope.getType()){
                    case "ALL"->true;case "EPISODE"->Objects.equals(s.getEpisodeId(),scope.getId());case "SCENE"->Objects.equals(s.getSceneId(),scope.getId());
                    case "SHOT_GROUP"->Objects.equals(s.getShotGroupId(),scope.getId());case "SHOT"->Objects.equals(s.getId(),scope.getId());default->throw new BizException(400,"选择范围类型无效");}).toList();
                if(matching.isEmpty()&&!"ALL".equals(scope.getType()))throw new BizException(400,"范围不存在或不属于所选短剧");
                matching.forEach(s->selected.add(s.getId()));
            }
            if(req.getSelection().getExcludeShotIds()!=null)selected.removeAll(req.getSelection().getExcludeShotIds());
        }
        if(selected.isEmpty()||selected.size()>properties.getMaxShots())throw new BizException(400,"请选择 1～"+properties.getMaxShots()+" 个分镜");
        if(!o.shots().stream().map(DramaShot::getId).collect(Collectors.toSet()).containsAll(selected))throw new BizException(400,"分镜不存在或不属于所选短剧");
        Preview result=new Preview();List<Item> items=new ArrayList<>();Map<String,Integer> positions=new LinkedHashMap<>();int order=0;
        for(var shot:o.shots()){
            order++;var ep=o.episodes().get(shot.getEpisodeId());var sc=o.scenes().get(shot.getSceneId());var gr=o.groups().get(shot.getShotGroupId());
            boolean valid=ep!=null&&sc!=null&&Objects.equals(sc.getEpisodeId(),ep.getId());
            if(!valid){ep=null;sc=null;gr=null;}else if(gr!=null&&!Objects.equals(gr.getSceneId(),sc.getId()))gr=null;
            String epKey="E:"+(ep==null?"UNKNOWN":ep.getId()),scKey=epKey+"/S:"+(sc==null?"UNKNOWN":sc.getId()),grKey=scKey+"/G:"+(gr==null?"NONE":gr.getId());
            String epPath=position(positions,epKey)+"_"+safe(ep==null?"未归属分集":ep.getTitle(),30),scPath=position(positions,scKey)+"_"+safe(sc==null?"未归属场景":sc.getName(),30),grPath=position(positions,grKey)+"_"+safe(gr==null?"未归组":gr.getName(),30);
            if(!selected.contains(shot.getId()))continue;
            Snapshot snap=new Snapshot();snap.setDramaId(req.getDramaId());snap.setDramaTitle(o.drama().getTitle());snap.setRoot(safe(o.drama().getTitle(),36));snap.setShotId(shot.getId());snap.setEpisodeId(shot.getEpisodeId());snap.setSceneId(shot.getSceneId());snap.setShotGroupId(shot.getShotGroupId());
            snap.setName("S"+shot.getShotNo()+" · "+StringUtils.defaultString(shot.getShotName(),"分镜"));snap.setEpisodeName(ep==null?"未归属分集":ep.getTitle());snap.setSceneName(sc==null?"未归属场景":sc.getName());snap.setGroupName(gr==null?"未归组":gr.getName());snap.setDuration(shot.getDuration());snap.setExecutionOrder(order);snap.setTakeId(shot.getCurrentVideoTakeId());snap.setVideoUrl(shot.getVideoUrl());snap.setStatus("READY");
            try{
                if(StringUtils.isBlank(shot.getVideoUrl())){snap.setStatus("MISSING_VIDEO");snap.setErrorMessage("未绑定当前视频");}
                else{
                    if(snap.getTakeId()!=null){var take=takes.selectById(snap.getTakeId());if(take==null||!Objects.equals(take.getShotId(),shot.getId())||!Objects.equals(take.getVideoUrl(),shot.getVideoUrl())||!"AVAILABLE".equals(take.getStatus()))throw new BizException(400,"当前 Take 与分镜视频绑定不一致");snap.setTakeNo(take.getTakeNo());}
                    sources.resolve(snap);
                    if(previous==null)sources.describe(snap);
                    else {var frozen=previous.get(shot.getId());if(frozen==null||!Objects.equals(frozen.getVideoUrl(),snap.getVideoUrl()))throw new BizException(409,"当前视频已变化，请重新检查");snap.setEtag(frozen.getEtag());snap.setVersionId(frozen.getVersionId());snap.setSourceSize(frozen.getSourceSize());snap.setExtension(frozen.getExtension());}
                    if(snap.getSourceSize()!=null&&snap.getSourceSize()>properties.getMaxFileBytes())throw new BizException(400,"视频超过单文件大小限制");
                    if(snap.getSourceSize()!=null&&snap.getSourceSize()<=0)throw new BizException(400,"当前视频文件为空");
                }
            }catch(BizException e){snap.setStatus("BLOCKED");snap.setErrorMessage(e.getMessage());}
            snap.setZipEntryPath(snap.getRoot()+"/"+epPath+"/"+scPath+"/"+grPath+"/"+String.format(Locale.ROOT,"%04d_",order)+safe(snap.getName(),48)+"_"+(snap.getTakeNo()==null?"当前绑定":"Take"+snap.getTakeNo())+"."+StringUtils.defaultIfBlank(snap.getExtension(),"mp4"));
            Item item=new Item();BeanUtils.copyProperties(snap,item);item.setSnapshot(snap);items.add(item);
        }
        result.setItems(items);result.setShotIds(items.stream().map(Item::getShotId).toList());
        result.setAccepted((int)items.stream().filter(i->"READY".equals(i.getStatus())).count());result.setMissing((int)items.stream().filter(i->"MISSING_VIDEO".equals(i.getStatus())).count());result.setBlocked(items.size()-result.getAccepted()-result.getMissing());result.setKnownBytes(items.stream().filter(i->"READY".equals(i.getStatus())).map(Item::getSourceSize).filter(Objects::nonNull).mapToLong(Long::longValue).sum());
        result.setPreviewHash(hash(write(Arrays.asList(req.getDramaId(),req.getMissingVideoPolicy(),req.getName(),items.stream().map(Item::getSnapshot).toList()))));return result;
    }
    public void lock(Request req,List<Long> ids){
        dramas.selectOne(new LambdaQueryWrapper<Drama>().eq(Drama::getId,req.getDramaId()).last("FOR UPDATE"));
        episodes.selectList(new LambdaQueryWrapper<DramaEpisode>().eq(DramaEpisode::getDramaId,req.getDramaId()).orderByAsc(DramaEpisode::getId).last("FOR UPDATE"));
        scenes.selectList(new LambdaQueryWrapper<DramaScene>().eq(DramaScene::getDramaId,req.getDramaId()).orderByAsc(DramaScene::getId).last("FOR UPDATE"));
        groups.selectList(new LambdaQueryWrapper<DramaShotGroup>().eq(DramaShotGroup::getDramaId,req.getDramaId()).orderByAsc(DramaShotGroup::getId).last("FOR UPDATE"));
        for(Long id:ids.stream().sorted().toList())if(shots.lockById(id)==null)throw new BizException(409,"分镜已变化，请重新检查");
    }
    private static String position(Map<String,Integer> positions,String key){return String.format(Locale.ROOT,"%03d",positions.computeIfAbsent(key,k->positions.size()+1));}
    public static String safe(String value,int maxBytes){
        String input=StringUtils.defaultIfBlank(value,"未命名").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").replaceAll("[. ]+$","");
        StringBuilder out=new StringBuilder();for(int cp:input.codePoints().toArray()){String c=new String(Character.toChars(cp));if((out.toString()+c).getBytes(StandardCharsets.UTF_8).length>maxBytes)break;out.append(c);}
        String result=out.toString();if(result.isBlank()||result.equals(".")||result.equals(".."))return "未命名";
        if(result.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))result="_"+result;
        return result;
    }
    public String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
