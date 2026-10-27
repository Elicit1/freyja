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
    public static final Set<String> REFERENCE_TYPES = Set.of("fully_copy", "partially_copy", "reference", "weak_reference");
    /** Platform library categories aligned with the documented H3 audio uses. */
    public static final Set<String> AUDIO_KINDS = Set.of("VOICE", "DIALOGUE", "LYRICS", "MUSIC", "SOUND_EFFECT", "RHYTHM", "CONTINUITY", "FULL_TRACK");
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
            if (audio.getReferenceType() == null || !REFERENCE_TYPES.contains(audio.getReferenceType())) throw new BizException(400, "refAudios[" + index + "].referenceType 必填，允许值=" + REFERENCE_TYPES);
            if (audio.getResAudioId() != null && (audio.getResAudioId() <= 0 || audio.getAudioId() != null)) throw new BizException(400, "音频素材来源无效");
            if (audio.getAudioKind() != null && !AUDIO_KINDS.contains(audio.getAudioKind())) throw new BizException(400, "音频素材种类无效");
            if (audio.getAudioId() != null && (audio.getAudioId() <= 0 || audio.getCharacterId() == null)) throw new BizException(400, "配音中心音频必须填写有效 audioId 和 characterId");
            if (audio.getReferenceDescription() != null && audio.getReferenceDescription().length() > 1000) throw new BizException(400, "参考音频说明不能超过1000字");
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
