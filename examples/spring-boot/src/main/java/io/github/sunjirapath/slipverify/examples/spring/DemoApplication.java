package io.github.sunjirapath.slipverify.examples.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo: {@code POST /slips/verify} with {@code {"image": "<base64>"}} or {@code {"payload": "<qr
 * text>"}}. The endpoint comes from the starter (slipverify.web.enabled=true). Add authentication
 * and rate limiting before use.
 */
@SpringBootApplication
public class DemoApplication {
  public static void main(String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
