package com.testpay.shield.web;

import com.testpay.shield.ingest.SchemaException;
import com.testpay.shield.scoring.ModelStore;
import com.testpay.shield.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Turns errors into {"error": ..., "problems": [...]} so the UI can show them. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(SchemaException.class)
    public ResponseEntity<Map<String, Object>> schema(SchemaException e) {
        return body(HttpStatus.BAD_REQUEST, "The uploaded files do not match the expected format.", e.getProblems());
    }

    @ExceptionHandler(SessionService.SessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> session(RuntimeException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), List.of());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), List.of());
    }

    @ExceptionHandler(ModelStore.ModelNotReadyException.class)
    public ResponseEntity<Map<String, Object>> notReady(RuntimeException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooBig(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "Upload too large: the limit is 30 MB in total.", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error: " + e.getMessage(), List.of());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String msg, List<String> problems) {
        return ResponseEntity.status(status).body(Map.of("error", msg == null ? status.getReasonPhrase() : msg, "problems", problems));
    }
}
