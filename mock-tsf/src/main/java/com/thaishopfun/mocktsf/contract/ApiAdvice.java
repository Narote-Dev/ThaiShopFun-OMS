package com.thaishopfun.mocktsf.contract;

import com.thaishopfun.mocktsf.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiAdvice {

  private final ContractResponses responses;

  public ApiAdvice(ContractResponses responses) {
    this.responses = responses;
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<String> api(ApiException ex, HttpServletRequest request) {
    return responses.error(request, ex.status(), ex.code(), ex.getMessage());
  }
}
