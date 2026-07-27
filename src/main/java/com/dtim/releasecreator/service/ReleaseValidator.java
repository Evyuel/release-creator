package com.dtim.releasecreator.service;

import com.dtim.releasecreator.exception.InvalidReleaseNumberException;
import java.util.regex.Pattern;

import com.dtim.releasecreator.exception.InvalidReleaseTaskNumberException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReleaseValidator {
    private final Pattern releaseNumberPattern;
    private final Pattern releaseTaskNumberPattern;

    public ReleaseValidator(@Value("${validation.task-number-pattern}") String taskNumberPattern) {
        this.releaseNumberPattern  = Pattern.compile("^\\d{3}\\.\\d+\\.\\d+$");
        this.releaseTaskNumberPattern  = Pattern.compile(taskNumberPattern);
    }

    public void validateVersion(String releaseVersion) {
        if (!releaseNumberPattern.matcher(releaseVersion).matches()) {
            throw new InvalidReleaseNumberException(releaseVersion);
        }
    }

    public void validateTaskNumber(String releaseTaskNumber) {
        if (!releaseTaskNumberPattern.matcher(releaseTaskNumber).matches()) {
            throw new InvalidReleaseTaskNumberException(releaseTaskNumber);
        }
    }
}
