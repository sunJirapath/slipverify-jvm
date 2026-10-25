package io.github.sunjirapath.slipverify;

import io.github.sunjirapath.promptparse.SlipVerify;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyQuotaGuardTest {
    @Test
    void existingJavaGuardNeedsOnlyItsOriginalTwoMethods() {
        List<String> calls = new ArrayList<>();
        QuotaGuard guard = new QuotaGuard() {
            @Override public boolean tryReserve(String provider) {
                calls.add("reserve:" + provider);
                return true;
            }
            @Override public void settle(String provider, Charge charge) {
                calls.add("settle:" + provider + ":" + charge);
            }
        };
        SlipProvider provider = new SlipProvider() {
            @Override public String getName() { return "legacy"; }
            @Override public boolean supports(SlipType type) { return true; }
            @Override public ProviderOutcome verify(ProviderQuery query) {
                return ProviderOutcome.notFound(Charge.NO);
            }
        };
        SlipVerificationResult result = SlipVerifier.builder().provider(provider).quotaGuard(guard).build()
            .verify(new SlipRequest(null, SlipVerify.build("004", "LEGACY"), null, null, null));
        assertEquals(VerificationStatus.INVALID, result.getStatus());
        assertEquals(List.of("reserve:legacy", "settle:legacy:NO"), calls);
    }
}
