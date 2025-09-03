package com.ayush.chat.exception;

import com.ayush.chat.model.logger.RequestLogger;
import com.ayush.chat.service.LoggerService;
import com.ayush.chat.util.ThreadMemory;
import com.google.firebase.auth.FirebaseAuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.*;

@ControllerAdvice
@RestController
@Slf4j
public class CustomizedExceptionHandling extends ResponseEntityExceptionHandler {
 @Autowired
 private LoggerService loggerService;

 @ExceptionHandler(Exception.class)
 public final ResponseEntity<Error> handleAllExceptions(Exception e, WebRequest request, HttpServletRequest req, HttpServletResponse res) {
  String exception = null;
  StackTraceElement[] elements = e.getStackTrace();
  for (StackTraceElement element : elements) {
   if (element.getClassName().equals(this.getClass().getName())) {
    exception =" Class:-" + element.getClassName() + "=" + element.getLineNumber() + ". ErrorMsg:-" + e.getMessage();
   }
  }
  Error errorDetails = new Error(new Date(), HttpStatus.INTERNAL_SERVER_ERROR.value(), "failure", "something went wrong",exception,false);
  String message = "Exception occurs at :"+new Date();

  log.error(message, e);
  RequestLogger requestLogger = loggerService.getRequestLoggerById(ThreadMemory.getRequestLogger().getId());
  //RequestLogger requestLogger = ThreadMemory.getRequestLogger();
  RequestLogger logger = loggerService.updateRequestLogger(requestLogger,req.getMethod(),req.getRemoteAddr(),HttpStatus.INTERNAL_SERVER_ERROR.value(),exception );
  return new ResponseEntity<>(errorDetails, HttpStatus.INTERNAL_SERVER_ERROR);
 }

 @ExceptionHandler(NoAPIClientFoundException.class)
 public final ResponseEntity<Error> handleNoApiClientExceptions(NoAPIClientFoundException e, WebRequest request) {
  Error errorDetails = new Error(new Date(), HttpStatus.NOT_FOUND.value(), "failure", "not found");
  return new ResponseEntity<>(errorDetails, HttpStatus.NOT_FOUND);
 }

 @ExceptionHandler(FirebaseAuthException.class)
 public final ResponseEntity<Error> handleAuthException(NoAPIClientFoundException e, WebRequest request) {
  Error errorDetails = new Error(new Date(), HttpStatus.UNAUTHORIZED.value(), "failure", "token expired or invalid");
  return new ResponseEntity<>(errorDetails, HttpStatus.UNAUTHORIZED);
 }

 @ExceptionHandler(HttpClientErrorException.class)
 public final ResponseEntity<String> handleHttpClientErrorExceptions(HttpClientErrorException e, WebRequest request) {
  return new ResponseEntity<>(e.getResponseBodyAsString(), e.getStatusCode());
 }

 @ExceptionHandler(HttpServerErrorException.class)
 public final ResponseEntity<String> handleHttpServerErrorExceptions(HttpServerErrorException e, WebRequest request) {
  return new ResponseEntity<>(e.getResponseBodyAsString(), e.getStatusCode());
 }

// @Override
// protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatus status, WebRequest request) {
//  List<ValidationError> errors = new ArrayList<>();
//  for(ObjectError error : ex.getBindingResult().getAllErrors()) {
//   Map<String, Boolean> params =  new HashMap<String, Boolean>();
//   params.put(((FieldError) error).getField(), true);
//   errors.add(new ValidationError("400", ((FieldError) error).getField()+" "+error.getDefaultMessage(), params));
//  }
//  ValidationErrorList error = new ValidationErrorList(errors);
//  return new ResponseEntity(error, HttpStatus.BAD_REQUEST);
// }

// @Override
// protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatus status, WebRequest request) {
//  Map<String, Boolean> params =  new HashMap<String, Boolean>();
//  params.put(ex.getParameterName(), true);
//  ValidationErrorList error = new ValidationErrorList(Arrays.asList(new ValidationError("400", ex.getMessage(), params)));
//  return new ResponseEntity(error, HttpStatus.BAD_REQUEST);
// }
//
// @Override
// protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpHeaders headers, HttpStatus status, WebRequest request) {
//  Error errorDetails = new Error(new Date(), 405, "failure", "method not supported");
//  return new ResponseEntity(errorDetails, HttpStatus.METHOD_NOT_ALLOWED);
// }
//
// @Override
// protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatus status, WebRequest request) {
//  ValidationErrorList error = new ValidationErrorList(Arrays.asList(new ValidationError("400", "body missing or incorrect format", new HashMap<String, Boolean>())));
//  return new ResponseEntity(error, HttpStatus.BAD_REQUEST);
// }
//
// @Override
// protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers, HttpStatus status, WebRequest request) {
//  ValidationErrorList error = new ValidationErrorList(Arrays.asList(new ValidationError("400", "input type mismatch", new HashMap<String, Boolean>())));
//  return new ResponseEntity(error, HttpStatus.BAD_REQUEST);
// }
}
