package com.ykm.common.common_lib.advice;

import com.ykm.common.common_lib.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Global advice that wraps all controller responses in a standard API response format.
 * <p>
 * This advice intercepts the response body before it is written to the HTTP response
 * and ensures it is encapsulated in an {@link ApiResponse} object when necessary.
 *
 * @since 1.0.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalResponseAdvice extends ResponseEntityExceptionHandler {

//    @Override
//    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
//        if (converterType == ByteArrayHttpMessageConverter.class) {
//            return false;
//        }
//
//        // 排除 StringHttpMessageConverter 也可能有類似問題
//        if (converterType == StringHttpMessageConverter.class) {
//            return false;
//        }
//        return true;
//    }
//
//    @Override
//    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
//        if (body instanceof ApiResponse) {
//            return body;
//        }
//        return new ApiResponse(true, "Success");
//    }
//
//    @ResponseBody
//    @ExceptionHandler(ValidationException.class)
//    public Object validationException(ValidationException exception) {
//        log.error(exception.getMessage(), exception);
//        return new ApiResponse(false, exception.toString());
//    }
//
//    @ResponseBody
//    @ExceptionHandler(HandlerMethodValidationException.class)
//    public Object handlerMethodValidationException(Throwable t) {
//        log.error(t.getMessage(), t);
//        return new ApiResponse(false, t.getMessage());
//    }
//
//    @ResponseBody
//    @ExceptionHandler(HttpMessageNotReadableException.class)
//    public Object httpMessageNotReadableException(HttpMessageNotReadableException e) {
//        log.error(e.getMessage(), e);
//        return new ApiResponse(false, "Http message not readable");
//    }
//
//    @ResponseBody
//    @ExceptionHandler(IllegalArgumentException.class)
//    public Object illegalArgumentException(IllegalArgumentException t) {
//        log.error(t.getMessage(), t);
//        return new ApiResponse(false, t.getMessage());
//    }
//
//    @ResponseBody
//    @ExceptionHandler(MethodArgumentNotValidException.class)
//    public Object methodArgumentNotValidException(MethodArgumentNotValidException e) {
//        BindingResult bindingResult = e.getBindingResult();
//
//        List<String> errorMessages = new ArrayList<>();
//
//        for (FieldError fieldError : bindingResult.getFieldErrors()) {
//            String message = fieldError.getField() + ": " + fieldError.getDefaultMessage();
//            errorMessages.add(message);
//        }
//
//        for (ObjectError globalError : bindingResult.getGlobalErrors()) {
//            String message = globalError.getObjectName() + ": " + globalError.getDefaultMessage();
//            errorMessages.add(message);
//        }
//
//        String combinedMessage = String.join("; ", errorMessages);
//
//        return new ApiResponse(false, combinedMessage);
//    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException ex,
                                                                   HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {  // 改為 HttpStatusCode
        log.error(ex.getMessage(), ex);
        return super.handleNoHandlerFoundException(ex, headers, statusCode, request);
    }
}
