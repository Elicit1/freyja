package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefAudioDTO;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/** H3 protocol limits, not estimates of media duration or natural-language interpretation. */
public final class ShotAudioReferenceValidator {
    public static final int MAX_AUDIOS = 3;
    public static final BigDecimal MIN_SECONDS = BigDecimal.valueOf(2);
    public static final BigDecimal MAX_SECONDS = BigDecimal.valueOf(15);
    public static final Set<String> USAGE_MODES = Set.of("VOICE_TIMBRE", "DIALOGUE_REUSE", "BGM_REUSE", "BGM_STYLE", "SFX_REUSE", "SFX_REFERENCE", "RHYTHM_REFERENCE");
    public static final Set<String> SOURCE_TYPES = Set.of("CHARACTER", "TTS", "UPLOAD");
    private ShotAudioReferenceValidator() {}

    public static void validate(List<ShotRefAudioDTO> audios) {
        if (audios == null) return;
        if (audios.size() > MAX_AUDIOS) throw new BizException(400, "参考音频最多 " + MAX_AUDIOS + " 段，请明确选择后重试");
        BigDecimal total = BigDecimal.ZERO;
        Set<String> urls = new HashSet<>();
        for (ShotRefAudioDTO audio : audios) {
            if (audio == null || StringUtils.isBlank(audio.getAudioUrl())) throw new BizException(400, "参考音频 URL 不能为空");
            if (!urls.add(audio.getAudioUrl())) throw new BizException(400, "同一参考音频不能重复占用槽位");
            if (!SOURCE_TYPES.contains(audio.getSourceType() == null ? "UPLOAD" : audio.getSourceType())) throw new BizException(400, "参考音频来源类型无效");
            if (!USAGE_MODES.contains(StringUtils.defaultIfBlank(audio.getUsageMode(), "VOICE_TIMBRE"))) throw new BizException(400, "参考音频用途模式无效");
            if ("CHARACTER".equals(audio.getSourceType()) && audio.getCharacterId() == null) throw new BizException(400, "角色母音必须明确绑定角色 ID");
            // Unknown metadata stays unknown. Rendering verifies the actual media bytes.
            if (audio.getDuration() != null) {
                if (audio.getDuration().compareTo(MIN_SECONDS) < 0 || audio.getDuration().compareTo(MAX_SECONDS) > 0) throw new BizException(400, "参考音频单段时长必须为 2–15 秒");
                total = total.add(audio.getDuration());
            }
        }
        if (total.compareTo(MAX_SECONDS) > 0) throw new BizException(400, "参考音频总时长不得超过 15 秒");
    }
}
