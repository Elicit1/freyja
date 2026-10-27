package com.astra.freyja.dto.res;
import com.astra.freyja.entity.ResAudio;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ResAudioVO extends ResAudio {
    private String dramaTitle;
    private String shotName;
}
