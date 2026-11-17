package com.ykm.stock.analytics.exception;

import lombok.Data;

import java.util.concurrent.TimeoutException;

@Data
public class ApplicationException extends RuntimeException {

    private Integer errorCode;

    public ApplicationException(Integer errorCode, String message){
        super(message);
        this.errorCode = errorCode;
    }

    public ApplicationException(String message, Throwable cause){
        super(message, cause);
    }

    public ApplicationException(String message){
        super(message);
    }

    public ApplicationException(Throwable throwable){
        super(throwable);
    }

    public ApplicationException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
