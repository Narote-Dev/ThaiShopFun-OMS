package com.thaishopfun.mocktsf;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.contract.ContractValidator;
import org.junit.jupiter.api.Test;

/**
 * Envelope rules from section 4.4: optional version on membership, schema_version 1, bigint cap.
 */
class EnvelopeSchemaTest {

  private final ContractValidator validator =
      ContractValidator.directory(ContractExamplesTest.contractsRoot());

  @Test
  void membershipMayOmitAggregateVersion() {
    assertThat(validator.envelopeErrors(membership(false))).isEmpty();
    assertThat(validator.envelopeErrors(membership(true))).isEmpty();
  }

  @Test
  void businessEventsRequireAggregateVersionAndSchemaVersionOne() {
    assertThat(validator.envelopeErrors(stock(true, "1", "1"))).isEmpty();
    assertThat(validator.envelopeErrors(stock(false, "1", "1"))).isNotEmpty();
    assertThat(validator.envelopeErrors(stock(true, "2", "1"))).isNotEmpty();
    assertThat(validator.envelopeErrors(stock(true, "1", "9223372036854775807"))).isEmpty();
    assertThat(validator.envelopeErrors(stock(true, "1", "9223372036854775808"))).isNotEmpty();
    assertThat(validator.envelopeErrors(membershipEntVer("9223372036854775807"))).isEmpty();
    assertThat(validator.envelopeErrors(membershipEntVer("9223372036854775808"))).isNotEmpty();
  }

  private static String membership(boolean version) {
    String versionField = version ? "\"aggregate_version\":1," : "";
    return "{"
        + "\"event_id\":\"evt-m\","
        + "\"event_type\":\"membership.changed\","
        + "\"schema_version\":1,"
        + "\"occurred_at\":\"2026-09-29T08:15:02Z\","
        + "\"tsf_shop_id\":\"shop_active\","
        + "\"aggregate_id\":\"shop_active\","
        + versionField
        + "\"data\":{\"tier\":\"PRO\",\"status\":\"ACTIVE\",\"ent_ver\":1}"
        + "}";
  }

  private static String membershipEntVer(String entVer) {
    return "{"
        + "\"event_id\":\"evt-m\","
        + "\"event_type\":\"membership.changed\","
        + "\"schema_version\":1,"
        + "\"occurred_at\":\"2026-09-29T08:15:02Z\","
        + "\"tsf_shop_id\":\"shop_active\","
        + "\"aggregate_id\":\"shop_active\","
        + "\"data\":{\"tier\":\"PRO\",\"status\":\"ACTIVE\",\"ent_ver\":"
        + entVer
        + "}"
        + "}";
  }

  private static String stock(boolean version, String schemaVersion, String aggregateVersion) {
    String versionField = version ? "\"aggregate_version\":" + aggregateVersion + "," : "";
    return "{"
        + "\"event_id\":\"evt-s\","
        + "\"event_type\":\"stock.updated\","
        + "\"schema_version\":"
        + schemaVersion
        + ","
        + "\"occurred_at\":\"2026-09-29T08:15:02Z\","
        + "\"tsf_shop_id\":\"shop_active\","
        + "\"aggregate_id\":\"stock\","
        + versionField
        + "\"data\":{\"items\":[{\"listing_sku_id\":\"tsf_sku_7781\",\"seller_sku\":\"TSHIRT-BLK-M\",\"available\":18,\"stock_version\":1042}]}"
        + "}";
  }
}
