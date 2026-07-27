package com.dtim.releasecreator.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateReleaseRequest(@NotBlank String releaseNumber ,@NotBlank String releaseTaskNumber) {
}
