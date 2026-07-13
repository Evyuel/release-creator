package com.dtim.releasecreator.controller;

import com.dtim.releasecreator.dto.CreateReleaseRequest;
import com.dtim.releasecreator.dto.ReleaseResult;
import com.dtim.releasecreator.service.ReleaseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @PostMapping
    public ResponseEntity<ReleaseResult> createRelease(@Valid @RequestBody CreateReleaseRequest request) {
        return ResponseEntity.ok(releaseService.createRelease(request.releaseNumber()));
    }
}
