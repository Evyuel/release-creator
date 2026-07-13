package com.dtim.releasecreator.service;

import com.dtim.releasecreator.exception.InvalidReleaseNumberException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ReleaseVersionValidator {

    private static final Pattern RELEASE_NUMBER_PATTERN = Pattern.compile("^\\d{3}\\.\\d+\\.\\d+$");

    public void validate(String releaseVersion) {
        if (releaseVersion == null || !RELEASE_NUMBER_PATTERN.matcher(releaseVersion).matches()) {
            throw new InvalidReleaseNumberException(releaseVersion);
        }
    }
}
