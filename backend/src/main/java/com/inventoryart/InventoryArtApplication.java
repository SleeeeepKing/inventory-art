package com.inventoryart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class InventoryArtApplication {
  public static void main(String[] args) {
    SpringApplication.run(InventoryArtApplication.class, args);
  }
}
