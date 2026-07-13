package com.dtim.releasecreator.controller;

import com.dtim.releasecreator.dto.ReleaseDeploymentResult;
import com.dtim.releasecreator.service.ReleaseDeploymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/releases")
public class ReleaseDeploymentController {

    private final ReleaseDeploymentService releaseDeploymentService;

    public ReleaseDeploymentController(ReleaseDeploymentService releaseDeploymentService) {
        this.releaseDeploymentService = releaseDeploymentService;
    }

    @PostMapping("/{releaseVersion}/deployments/uat")
    public ResponseEntity<ReleaseDeploymentResult> deployToUat(
            @PathVariable String releaseVersion) {
        return ResponseEntity.ok(releaseDeploymentService.deployToUat(releaseVersion));
    }
}
