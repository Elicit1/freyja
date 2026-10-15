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
    public static final Set<String> LANGUAGES = Set.of("zh", "en", "ja", "other");
    private ShotAudioReferenceValidator() {}

    public static void validate(List<ShotRefAudioDTO> audios) {
        if (audios == null) return;
        if (audios.size() > MAX_AUDIOS) throw new BizException(400, "参考音频最多 " + MAX_AUDIOS + " 段，请明确选择后重试");
        BigDecimal total = BigDecimal.ZERO;
        Set<String> urls = new HashSet<>();
        for (int index = 0; index < audios.size(); index++) {
            ShotRefAudioDTO audio = audios.get(index);
            if (audio == null || StringUtils.isBlank(audio.getAudioUrl())) throw new BizException(400, "参考音频 URL 不能为空");
            if (!urls.add(audio.getAudioUrl())) throw new BizException(400, "同一参考音频不能重复占用槽位");
            if (!SOURCE_TYPES.contains(audio.getSourceType() == null ? "UPLOAD" : audio.getSourceType())) throw new BizException(400, "参考音频来源类型无效");
            if (audio.getUsageMode() != null && !USAGE_MODES.contains(audio.getUsageMode())) throw new BizException(400, "refAudios[" + index + "].usageMode 无效，允许值=" + USAGE_MODES);
            if ("CHARACTER".equals(audio.getSourceType()) && audio.getCharacterId() == null) throw new BizException(400, "角色母音必须明确绑定角色 ID");
            // Unknown metadata stays unknown. Rendering verifies the actual media bytes.
            if (audio.getDuration() != null) {
                if (audio.getDuration().compareTo(MIN_SECONDS) < 0 || audio.getDuration().compareTo(MAX_SECONDS) > 0) throw new BizException(400, "参考音频单段时长必须为 2–15 秒");
                total = total.add(audio.getDuration());
            }
        }
        if (total.compareTo(MAX_SECONDS) > 0) throw new BizException(400, "参考音频总时长不得超过 15 秒");
    }

    public static void validateForSave(List<ShotRefAudioDTO> audios) {
        validate(audios);
        if (audios == null) return;
        for (int i = 0; i < audios.size(); i++) {
            String language = audios.get(i).getLanguage();
            if (language != null && !LANGUAGES.contains(language)) throw new BizException(400, "refAudios[" + i + "].language 无效，允许值=" + LANGUAGES);
        }
    }
}
