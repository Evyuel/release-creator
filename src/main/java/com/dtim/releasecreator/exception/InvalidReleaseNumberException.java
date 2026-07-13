package com.dtim.releasecreator.exception;

public class InvalidReleaseNumberException extends RuntimeException {

    public InvalidReleaseNumberException(String releaseNumber) {
        super("Release number must match XXX.Y.Z, for example 180.0.0. Received: " + releaseNumber);
    }
}
