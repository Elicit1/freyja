package com.astra.freyja.dto.drama;

import com.astra.freyja.common.BizException;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** Explicit creator choices. Strengths belong to different recipes, never to global denoise. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShotContinuityOptions {
    public enum Mode { GUIDE, GUIDE_CHROMA, LATENT_REDRAW, LATENT_PROTECTED }
    private Mode mode = Mode.GUIDE;
    private Chroma chroma;
    private Redraw redraw;
    private Protection protection;
    @Data public static class Chroma {
        private double strength = .45;
        private double seamStrength = .10;
        private int taperFrames = 3;
    }
    @Data public static class Redraw { private double strength = .10; }
    @Data public static class Protection {
        private double noiseStrength = 1;
        private boolean useFirstFrameAnchor = true;
        private boolean useTailAnchor;
        private double tailAnchorStartFraction = .75;
        private String anchorSourceSegmentId;
    }
    public boolean requiresLatent() { return mode == Mode.LATENT_REDRAW || mode == Mode.LATENT_PROTECTED; }
    public ShotContinuityOptions resolved(int overlap) {
        if (mode == null) throw new BizException(400, "请选择有效的接续方式");
        var out = new ShotContinuityOptions(); out.mode = mode;
        switch (mode) {
            case GUIDE -> { }
            case GUIDE_CHROMA -> {
                out.chroma = chroma == null ? new Chroma() : chroma;
                unit(out.chroma.strength); unit(out.chroma.seamStrength);
                if (out.chroma.seamStrength > out.chroma.strength || out.chroma.taperFrames < 1 || out.chroma.taperFrames > overlap)
                    throw new BizException(400, "彩噪渐弱帧数须在共享上下文范围内，接缝强度不能大于彩噪强度");
            }
            case LATENT_REDRAW -> { out.redraw = redraw == null ? new Redraw() : redraw; unit(out.redraw.strength); if(out.redraw.strength > .95)throw new BizException(400,"重绘幅度不能超过 0.95"); }
            case LATENT_PROTECTED -> {
                out.protection = protection == null ? new Protection() : protection;
                unit(out.protection.noiseStrength); unit(out.protection.tailAnchorStartFraction);
            }
        }
        return out;
    }
    private static void unit(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new BizException(400, "接续强度应在 0～1 范围内");
    }
}
