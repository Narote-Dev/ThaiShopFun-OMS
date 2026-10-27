package com.thaishopfun.oms.order.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.inbox.NonRetryableInboxException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaymentProviderTest {
  @Test
  void explicitProviderTakesPrecedence() {
    assertThat(PaymentProvider.resolve("OPN", "xnd_123")).isEqualTo("OPN");
    assertThat(PaymentProvider.resolve("XENDIT", "chrg_123")).isEqualTo("XENDIT");
  }

  @Test
  void infersSupportedReferencePrefixes() {
    assertThat(PaymentProvider.resolve(null, "xnd_123")).isEqualTo("XENDIT");
    assertThat(PaymentProvider.resolve(null, "chrg_123")).isEqualTo("OPN");
    assertThat(PaymentProvider.resolve(null, "opn_123")).isEqualTo("OPN");
    assertThat(PaymentProvider.resolve(null, "opn123")).isEqualTo("OPN");
  }

  @Test
  void missingAndUnrecognizedMetadataStayUnknown() {
    assertThat(PaymentProvider.resolve(null, null)).isNull();
    assertThat(PaymentProvider.resolve(null, "other_123")).isNull();
  }

  @Test
  void rejectsUnsupportedExplicitProviderWithoutEchoingInput() {
    assertThatThrownBy(() -> PaymentProvider.resolve("unexpected-private-value", "xnd_123"))
        .isInstanceOf(NonRetryableInboxException.class)
        .hasMessage("invalid payment provider");
  }

  @Test
  void legacyGetPayloadWithoutProviderStillDeserializes() {
    var payment =
        new JsonMapper()
            .readValue(
                "{\"order_id\":\"COD-1\",\"status\":\"UNPAID\",\"refunds\":[]}",
                PaymentStatus.class);
    assertThat(payment.provider()).isNull();
    assertThat(payment.providerRef()).isNull();
  }
}
