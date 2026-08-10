package com.dtim.releasecreator.exception;

import com.dtim.releasecreator.dto.ReleasePreflightCheckResult;
import java.util.List;

public class ReleasePreflightException extends RuntimeException {

    private final String csvReportPath;
    private final List<ReleasePreflightCheckResult> failures;

    public ReleasePreflightException(String csvReportPath, List<ReleasePreflightCheckResult> failures) {
        super("Release preflight failed for " + failures.stream()
                .map(ReleasePreflightCheckResult::repository)
                .distinct()
                .count() + " repositories; report: " + csvReportPath);
        this.csvReportPath = csvReportPath;
        this.failures = List.copyOf(failures);
    }

    public String getCsvReportPath() {
        return csvReportPath;
    }

    public List<ReleasePreflightCheckResult> getFailures() {
        return failures;
    }
}
