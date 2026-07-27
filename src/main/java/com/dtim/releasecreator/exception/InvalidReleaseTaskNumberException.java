package com.dtim.releasecreator.exception;

public class InvalidReleaseTaskNumberException extends RuntimeException {

    public InvalidReleaseTaskNumberException(String releaseNumber) {
        super("Invalid release task number. Received: " + releaseNumber);
    }
}
