package io.github.sunjirapath.slipverify.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sunjirapath.promptparse.SlipVerify;
import io.github.sunjirapath.slipverify.ReasonCode;
import io.github.sunjirapath.slipverify.SlipRequest;
import io.github.sunjirapath.slipverify.SlipVerificationResult;
import io.github.sunjirapath.slipverify.SlipVerifier;
import io.github.sunjirapath.slipverify.VerificationStatus;
import org.junit.jupiter.api.Test;

class PlainJavaExampleTest {
  @Test
  void offlineOnlyIsNeverValid() {
    SlipVerifier verifier = PlainJavaExample.buildVerifier(null);
    SlipVerificationResult result =
        PlainJavaExample.verifyPayload(verifier, SlipVerify.build("004", "JAVA-REF-1"));
    assertEquals(VerificationStatus.UNVERIFIED, result.getStatus());
    assertTrue(result.getReasons().contains(ReasonCode.NO_PROVIDER_CONFIGURED));
    assertFalse(result.isValid());
    assertEquals("JAVA-REF-1", result.getTransRef());
  }

  @Test
  void javaFriendlyConstructorsAndBuilder() {
    SlipVerifier verifier = SlipVerifier.builder().debug(true).build();
    SlipVerificationResult result = verifier.verify(new SlipRequest(null, "not a slip"));
    assertEquals(VerificationStatus.INVALID, result.getStatus());
    assertEquals(ReasonCode.NOT_A_SLIP_QR, result.getReasons().get(0));
  }
}
