package com.inventoryart.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ApiError> business(BusinessException ex, HttpServletRequest request) {
    return response(ex.getStatus(), ex.getCode(), ex.getMessage(), request, null);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> validation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors())
      fields.putIfAbsent(error.getField(), error.getDefaultMessage());
    return response(
        HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request, fields);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ApiError> constraint(ConstraintViolationException ex, HttpServletRequest request) {
    return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request, null);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest request) {
    return response(
        HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request could not be parsed", request, null);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiError> conflict(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "DATA_CONFLICT",
        "The request conflicts with existing data",
        request,
        null);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiError> denied(AccessDeniedException ex, HttpServletRequest request) {
    return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied", request, null);
  }

  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  ResponseEntity<ApiError> notFound(Exception ex, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found", request, null);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> methodNotAllowed(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    if (isRemovedOrderWriteEndpoint(request)) {
      return response(
          HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found", request, null);
    }
    return response(
        HttpStatus.METHOD_NOT_ALLOWED,
        "METHOD_NOT_ALLOWED",
        "Request method is not supported",
        request,
        null);
  }

  private boolean isRemovedOrderWriteEndpoint(HttpServletRequest request) {
    if (!"POST".equals(request.getMethod())) return false;
    String path = request.getRequestURI();
    return path.equals("/api/v1/orders")
        || path.equals("/api/v1/orders/batch-confirm")
        || path.matches("/api/v1/orders/[^/]+/(confirm|allocate|refunds)");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
    log.error(
        "Unexpected request failure for {} {}", request.getMethod(), request.getRequestURI(), ex);
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "An unexpected error occurred",
        request,
        null);
  }

  private ResponseEntity<ApiError> response(
      HttpStatus status,
      String code,
      String message,
      HttpServletRequest req,
      Map<String, String> fields) {
    return ResponseEntity.status(status)
        .body(
            new ApiError(
                Instant.now(),
                status.value(),
                code,
                message,
                req.getRequestURI(),
                fields,
                MDC.get("traceId")));
  }
}
