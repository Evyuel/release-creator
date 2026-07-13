package com.dtim.releasecreator.exception;

public class ReleaseInProgressException extends RuntimeException {

    public ReleaseInProgressException() {
        super("Another release is already in progress in this service instance");
    }
}
