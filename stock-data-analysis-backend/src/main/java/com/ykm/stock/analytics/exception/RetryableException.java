package com.ykm.stock.analytics.exception;

public class RetryableException extends RuntimeException{

    private Integer errorCode;

    public RetryableException(Integer errorCode, String message){
        super(message);
        this.errorCode = errorCode;
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
