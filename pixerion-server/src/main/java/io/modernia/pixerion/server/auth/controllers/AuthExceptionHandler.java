package io.modernia.pixerion.server.auth.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a failed login into a 401 instead of the 500 an unhandled
 * {@code AuthenticationException} would produce. Only applies to exceptions raised inside
 * a controller — failures in the security filter chain never reach here and are handled
 * by the {@code HttpStatusEntryPoint} configured in {@code SecurityConfig}.
 */
@RestControllerAdvice
class AuthExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail onBadCredentials(BadCredentialsException ignored) {
        // Deliberately vague: distinguishing "no such account" from "wrong password"
        // would turn this endpoint into an account-enumeration oracle.
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Authentication failed");
        problem.setDetail("Invalid email or password.");
        return problem;
    }

}
