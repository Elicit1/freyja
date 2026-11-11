package com.astra.freyja.dto.drama;

import com.astra.freyja.common.BizException;
import lombok.Data;

/** Authored choices, separate from continuation. First release fixes scale at 2x. */
@Data
public class DynamicWorkflowOptions {
    private boolean upscale;
    private boolean faceRepair;
    private int scale = 2;
    private double upscaleDenoise = .25;
    private double faceDenoise = .4;
    private UpscaleTiling upscaleTiling = new UpscaleTiling();
    // Missing fields in historical tasks keep their original pixel-stage semantics.
    private String upscaleEngine = "PIXEL";
    private String upscaleMethod = "H3_LEARNED";
    private String upscaleLatentModel;
    private String upscaleOutput = "DOUBLE";
    private int upscaleSteps = 2;
    private String upscaleMemory = "TEMPORAL";
    private String upscaleSampler = "STANDARD";
    public void validate() {
        if(scale!=2 || !Double.isFinite(upscaleDenoise) || upscaleDenoise<=0 || upscaleDenoise>.6
                || !Double.isFinite(faceDenoise) || faceDenoise<=0 || faceDenoise>.6)
            throw new BizException(400,"二采倍率须为 2，重绘强度须大于 0 且不超过 0.6");
        if(upscaleTiling==null)throw new BizException(400,"二采分块设置不能为空");
        upscaleTiling.validate();
        if(upscaleEngine==null||upscaleMethod==null||upscaleOutput==null||upscaleMemory==null
                ||!java.util.Set.of("PIXEL","FREYJA").contains(upscaleEngine)
                ||!java.util.Set.of("LATENT_BICUBIC","H3_LEARNED").contains(upscaleMethod)
                ||!java.util.Set.of("DOUBLE","FULL_HD").contains(upscaleOutput)
                ||!java.util.Set.of("FULL","TEMPORAL","SPATIAL").contains(upscaleMemory)
                ||!java.util.Set.of("STANDARD","SELFLIFT").contains(java.util.Objects.toString(upscaleSampler,""))
                ||upscaleSteps<1||upscaleSteps>32)throw new BizException(400,"FREYJA 二采组件参数无效");
        if("PIXEL".equals(upscaleEngine)&&(upscaleTiling.isEnabled()||!"DOUBLE".equals(upscaleOutput)))
            throw new BizException(400,"旧分块插件已移除，请选择 FREYJA 二采组件后重新保存");
        if(upscale&&"FREYJA".equals(upscaleEngine)&&!"H3_LEARNED".equals(upscaleMethod))
            throw new BizException(400,"内置放大已移除，请重新提交以使用默认专用放大模型");
    }
    @Data public static class UpscaleTiling {
        private boolean enabled;
        private int tileWidth=768;
        private int tileHeight=512;
        private int overlap=192;
        private boolean temporal;
        private int windowFrames=56;
        private int overlapFrames=22;
        public void validate(){
            if(tileWidth<256||tileWidth>2048||tileWidth%32!=0||tileHeight<256||tileHeight>2048||tileHeight%32!=0
                    ||overlap<32||overlap>512||overlap%32!=0||overlap>=Math.min(tileWidth,tileHeight))
                throw new BizException(400,"二采分块宽高须为 256–2048 的 32 倍数；重叠须为 32–512 的 32 倍数且小于块宽高");
            if(windowFrames<39||windowFrames>124||(windowFrames-5)%17!=0||overlapFrames<5
                    ||(overlapFrames-5)%17!=0||overlapFrames>=windowFrames)
                throw new BizException(400,"二采时间窗口须为 39–124 帧，窗口和重叠须满足 17n+5，重叠小于窗口");
        }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"presetId","version"})
    @Data public static class Selection {
        private DynamicWorkflowOptions options;
        private boolean reusePrevious;
        private String reuseFromTaskId;
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"presetId","version"})
    @Data public static class Plan {
        private boolean nativeLatentInput;
        // Missing in historical snapshots: retain their independent enhancement semantics.
        private boolean secondPassContinuity;
        private String name;
        private String capabilityRevision;
        private String nodeId;
        private String baseSize;
        private String outputSize;
        private String processingSize;
        private DynamicWorkflowOptions options;
        private String reuseFromTaskId;
    }
}
