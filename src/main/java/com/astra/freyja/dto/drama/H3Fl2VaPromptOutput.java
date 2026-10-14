package com.astra.freyja.dto.drama;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

/** The three editable prompt fields produced for a first/last-frame shot. */
@Data
public class H3Fl2VaPromptOutput {

    @JsonPropertyDescription("从统一剧本提取的声音列表；逐字保留对白、画外音、旁白及独白，无口语声音时显式返回 []。不是新的剧本事实源。")
    private java.util.List<ShotSpeechLine> speechLines;

    @JsonPropertyDescription("English still-image prompt for the first frame; describe only the state at the start")
    private String firstFramePrompt;

    @JsonPropertyDescription("English still-image prompt for the last frame; describe only the state at the end")
    private String endFramePrompt;

    @JsonPropertyDescription("Complete MiniMax H3 FL2VA video prompt: frame alignment declaration followed by integrated_multimodal_description, overall_soundscape, non_diegetic_music in this order")
    private String videoPrompt;
}
