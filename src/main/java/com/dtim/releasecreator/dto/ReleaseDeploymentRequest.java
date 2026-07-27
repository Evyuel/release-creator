package com.dtim.releasecreator.dto;

import jakarta.validation.constraints.NotBlank;

public record ReleaseDeploymentRequest(@NotBlank String releaseVersion) {
}
