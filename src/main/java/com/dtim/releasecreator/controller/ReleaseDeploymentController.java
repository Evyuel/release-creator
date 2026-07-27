package com.dtim.releasecreator.controller;

import com.dtim.releasecreator.dto.ReleaseDeploymentRequest;
import com.dtim.releasecreator.dto.ReleaseDeploymentResult;
import com.dtim.releasecreator.service.ReleaseDeploymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/releases")
public class ReleaseDeploymentController {

    private final ReleaseDeploymentService releaseDeploymentService;

    public ReleaseDeploymentController(ReleaseDeploymentService releaseDeploymentService) {
        this.releaseDeploymentService = releaseDeploymentService;
    }

    @PostMapping("/deployments/uat")
    public ResponseEntity<ReleaseDeploymentResult> deployToUat(@Valid @RequestBody ReleaseDeploymentRequest request) {
        return ResponseEntity.ok(releaseDeploymentService.deployToUat(request.releaseVersion()));
    }
}
