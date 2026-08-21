package com.finalearth.sales.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * A dependency answered "no". Its status is passed straight through — a 404 stays a
     * 404 and a declined card stays a 402, because the caller's problem is the same one
     * the dependency described.
     */
    @ExceptionHandler(DependencyBusinessException.class)
    public ResponseEntity<ProblemDetail> handleDependencyBusiness(DependencyBusinessException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setType(URI.create("about:blank"));
        return ResponseEntity.status(ex.getStatus()).body(pd);
    }

    /**
     * A dependency could not be reached. Always 503, and always with a
     * {@code Retry-After} — the client is being told to come back, not that its request
     * was wrong. {@code circuitOpen} is surfaced so the caller can see the difference
     * between "we tried and failed" and "we have stopped trying for now".
     */
    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDependencyUnavailable(DependencyUnavailableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        pd.setTitle("Dependency unavailable");
        pd.setType(URI.create("about:blank"));
        pd.setProperty("dependency", ex.getDependency());
        pd.setProperty("circuitOpen", ex.isCircuitOpen());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "10")
                .body(pd);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        pd.setType(URI.create("about:blank"));
        return ResponseEntity.status(ex.getStatusCode()).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Validation failed");
        pd.setType(URI.create("about:blank"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneral(Exception ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        pd.setTitle("Internal server error");
        pd.setType(URI.create("about:blank"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }
}
