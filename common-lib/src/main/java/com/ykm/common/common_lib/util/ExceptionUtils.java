package com.ykm.common.common_lib.util;

public class ExceptionUtils {

    public static Throwable getRootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable cause = throwable.getCause();
        if (cause != null) {
            return getRootCause(cause);
        }

        return throwable;
    }
}
