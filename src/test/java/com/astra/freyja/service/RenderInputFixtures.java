package com.astra.freyja.service;

public final class RenderInputFixtures {
    private RenderInputFixtures() {}
    public static String reference(String labels) {
        return "subject_definitions:\nN/A\nsummary:\nA continuous scene.\nretention_analysis:\n" + (labels.isBlank() ? "N/A" : labels)
                + "\ndetailed_description:\nThe camera observes the scene.\noverall_soundscape:\nN/A\nnon_diegetic_music:\nN/A";
    }
    public static final String FL = "How the reference pictures align with the target video — Picture 1 aligns with the 0.00-second mark of the target video; Picture 2 aligns with the 3.00-second mark of the target video.\n"
            + "integrated_multimodal_description: A continuous scene.\noverall_soundscape: N/A\nnon_diegetic_music: N/A";
}
