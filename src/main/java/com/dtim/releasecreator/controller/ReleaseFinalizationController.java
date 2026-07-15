package com.dtim.releasecreator.controller;

import com.dtim.releasecreator.dto.ReleaseFinalizationResult;
import com.dtim.releasecreator.service.ReleaseFinalizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseFinalizationController {

    private final ReleaseFinalizationService releaseFinalizationService;

    public ReleaseFinalizationController(ReleaseFinalizationService releaseFinalizationService) {
        this.releaseFinalizationService = releaseFinalizationService;
    }

    @PostMapping("/{releaseNumber}/finalize")
    public ResponseEntity<ReleaseFinalizationResult> finalizeRelease(
            @PathVariable String releaseNumber) {
        return ResponseEntity.ok(releaseFinalizationService.finalizeRelease(releaseNumber));
    }
}
