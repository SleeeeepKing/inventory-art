package com.inventoryart.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends BusinessException {
  public NotFoundException(String resource) {
    super("RESOURCE_NOT_FOUND", resource + " not found", HttpStatus.NOT_FOUND);
  }
}
