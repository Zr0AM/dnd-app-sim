package org.omnomnom.dnd.sim.adapter.in.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.omnomnom.dnd.sim.application.BadRequestException;
import org.omnomnom.dnd.sim.application.BusyException;
import org.omnomnom.dnd.sim.application.ConflictException;
import org.omnomnom.dnd.sim.application.NotFoundException;
import org.omnomnom.dnd.sim.application.SimException;
import org.omnomnom.dnd.sim.application.SimException.FieldError;
import org.omnomnom.dnd.sim.application.UnprocessableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps failures to RFC 9457 {@code application/problem+json} with a stable {@code code} and field-level {@code errors}. */
@RestControllerAdvice
class ProblemHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title, String detail, List<FieldError> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        p.setType(URI.create("urn:dnd-app-sim:problem:" + code));
        p.setProperty("code", code);
        if (!errors.isEmpty()) {
            p.setProperty("errors", errors);
        }
        return ResponseEntity.status(status).body(p);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException e) {
        List<FieldError> errors = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(fe -> errors.add(new FieldError(fe.getField(), fe.getDefaultMessage(), fe.getCode())));
        e.getBindingResult().getGlobalErrors().forEach(ge -> errors.add(new FieldError(ge.getObjectName(), ge.getDefaultMessage(), ge.getCode())));
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", "One or more fields failed validation.", errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> invalidParameter(HandlerMethodValidationException e) {
        List<FieldError> errors = new ArrayList<>();
        e.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(re -> errors.add(
                new FieldError(r.getMethodParameter().getParameterName(), re.getDefaultMessage(), re.getCodes() == null || re.getCodes().length == 0 ? null : re.getCodes()[re.getCodes().length - 1]))));
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", "A parameter failed validation.", errors);
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ProblemDetail> invalidRequest(RequestValidationException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage(), e.errors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "malformed-json", "Malformed request body",
                "The body is not valid JSON or holds a value of the wrong type or an unknown option.", List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParameter(MissingServletRequestParameterException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", "Parameter '" + e.getParameterName() + "' is required.",
                List.of(new FieldError(e.getParameterName(), "is required", "required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> badParameterType(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", "Parameter '" + e.getName() + "' has the wrong type.",
                List.of(new FieldError(e.getName(), "has the wrong type", "type-mismatch")));
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ProblemDetail> badRequest(BadRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage(), e.errors());
    }

    @ExceptionHandler(UnprocessableException.class)
    ResponseEntity<ProblemDetail> unprocessable(UnprocessableException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), "Unprocessable request", e.getMessage(), e.errors());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.code(), "Not found", e.getMessage(), e.errors());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, e.code(), "Conflict", e.getMessage(), e.errors());
    }

    @ExceptionHandler(BusyException.class)
    ResponseEntity<ProblemDetail> busy(BusyException e) {
        ResponseEntity<ProblemDetail> base = problem(HttpStatus.TOO_MANY_REQUESTS, e.code(), "Busy", e.getMessage(), e.errors());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(base.getBody());
    }

    @ExceptionHandler(SimException.class)
    ResponseEntity<ProblemDetail> other(SimException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, e.code(), "Error", e.getMessage(), e.errors());
    }

    /**
     * Everything else. Spring MVC's own failures (an unknown path, a wrong method or media type, ...) carry their HTTP
     * status as an {@link ErrorResponse}; they keep it, with their headers (for example {@code Allow} on a 405), and are
     * not logged as errors. Anything without a status is an unexpected 500 whose details stay in the log.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            String code = switch (status) {
                case NOT_FOUND -> "not-found";
                case METHOD_NOT_ALLOWED -> "method-not-allowed";
                case UNSUPPORTED_MEDIA_TYPE -> "unsupported-media-type";
                case NOT_ACCEPTABLE -> "not-acceptable";
                default -> "invalid-request";
            };
            LOG.debug("client error {}: {}", status.value(), e.toString());
            ResponseEntity<ProblemDetail> base = problem(status, code, status.getReasonPhrase(), er.getBody().getDetail() != null
                    ? er.getBody().getDetail() : status.getReasonPhrase(), List.of());
            return ResponseEntity.status(status).headers(er.getHeaders()).body(base.getBody());
        }
        LOG.error("unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error", "The request could not be completed.", List.of());
    }
}
