package com.astra.freyja.dto.drama;

public record DirectorPlanGenerationCheckVO(boolean inputsChanged, boolean overwriteRequired,
        String inputHash, int directorPlanRevision) { }
