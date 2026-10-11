package io.github.sunjirapath.slipverify.examples;

import io.github.sunjirapath.promptparse.SlipVerify;
import io.github.sunjirapath.slipverify.DuplicateStore;
import io.github.sunjirapath.slipverify.InMemoryDuplicateStore;
import io.github.sunjirapath.slipverify.ReceiverRule;
import io.github.sunjirapath.slipverify.SlipRequest;
import io.github.sunjirapath.slipverify.SlipVerificationResult;
import io.github.sunjirapath.slipverify.SlipVerifier;
import io.github.sunjirapath.slipverify.easyslip.EasySlipConfig;
import io.github.sunjirapath.slipverify.easyslip.EasySlipProvider;
import io.github.sunjirapath.slipverify.zxing.ZxingQrExtractor;
import java.math.BigDecimal;
import java.time.Duration;

/**
 * Plain Java usage. Run with {@code ./gradlew :examples:java:run}. Without {@code EASYSLIP_API_KEY}
 * the result is {@code UNVERIFIED}: a slip QR alone proves nothing about a payment.
 */
public final class PlainJavaExample {
  private PlainJavaExample() {}

  /** Builds a verifier the way an application would, reading the API key from the environment. */
  public static SlipVerifier buildVerifier(String easySlipApiKey) {
    DuplicateStore store = new InMemoryDuplicateStore();
    SlipVerifier.Builder builder =
        SlipVerifier.builder().qrExtractor(new ZxingQrExtractor()).duplicateStore(store);
    if (easySlipApiKey != null && !easySlipApiKey.isBlank()) {
      builder.provider(new EasySlipProvider(new EasySlipConfig(easySlipApiKey)));
    }
    return builder.build();
  }

  /** Verifies a slip QR text that the frontend already scanned. */
  public static SlipVerificationResult verifyPayload(SlipVerifier verifier, String payload) {
    SlipRequest request =
        new SlipRequest(
            null, // imageBase64: send this instead of payload when the frontend sends the picture
            payload,
            new BigDecimal("150.00"), // expectedAmount
            new ReceiverRule("1234", null), // account ends with 1234
            Duration.ofMinutes(30)); // maxAge
    return verifier.verify(request);
  }

  public static void main(String[] args) {
    SlipVerifier verifier = buildVerifier(System.getenv("EASYSLIP_API_KEY"));
    String payload = SlipVerify.build("004", "DEMO-REF-0001");
    SlipVerificationResult result = verifyPayload(verifier, payload);
    System.out.println(result); // toString masks names and accounts
  }
}
