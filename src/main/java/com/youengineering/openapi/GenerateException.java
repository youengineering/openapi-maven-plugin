package com.youengineering.openapi;

public class GenerateException extends Exception {

    public GenerateException(String message) {
        super(message);
    }

    public GenerateException(Throwable cause) {
        super(cause);
    }

    public GenerateException(String message, Throwable cause) {
        super(message, cause);
    }
}

