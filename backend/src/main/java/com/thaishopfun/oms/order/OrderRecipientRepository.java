package com.thaishopfun.oms.order;

import com.thaishopfun.oms.pii.PiiCipher;
import com.thaishopfun.oms.pii.PiiColumn;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code order_recipient}, the only table with PII. Values are encrypted by {@link PiiCipher}
 * before they reach SQL and decrypted after they come back, so plaintext never appears in a
 * statement, a bind log, or the table. Runs under the caller's transaction and tenant (RLS). Never
 * logs.
 */
@Repository
public class OrderRecipientRepository {

  private final JdbcTemplate jdbc;
  private final PiiCipher cipher;

  public OrderRecipientRepository(JdbcTemplate jdbc, PiiCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  public void insert(UUID orderId, Recipient recipient, Instant redactAfter) {
    // Step 1: Validate. The AAD binds each value to this tenant, order, and column.
    UUID tenantId = TenantContext.requireTenantId();
    boolean hasPhone = recipient.phone() != null && !recipient.phone().isBlank();

    // Step 2: Encrypt name/phone/address. Hash and last 4 come from the normalized phone.
    byte[] name = cipher.encrypt(recipient.name(), tenantId, orderId, PiiColumn.NAME);
    byte[] phone =
        hasPhone ? cipher.encrypt(recipient.phone(), tenantId, orderId, PiiColumn.PHONE) : null;
    byte[] address = cipher.encrypt(recipient.address(), tenantId, orderId, PiiColumn.ADDRESS);
    byte[] phoneHash = hasPhone ? cipher.phoneHash(recipient.phone()) : null;
    String phoneLast4 = hasPhone ? PiiCipher.phoneLast4(recipient.phone()) : null;

    // Step 3: Insert as ACTIVE. RLS WITH CHECK rejects a tenant other than the context.
    jdbc.update(
        "INSERT INTO order_recipient (order_id, tenant_id, name_enc, phone_enc, phone_hash, "
            + "phone_last4, address_enc, province, postcode, pii_status, redact_after) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
        orderId,
        tenantId,
        name,
        phone,
        phoneHash,
        phoneLast4,
        address,
        recipient.province(),
        recipient.postcode(),
        SalesOrderRepository.timestamp(redactAfter));
  }

  public Optional<StoredRecipient> find(UUID orderId) {
    // Step 1: Read the row. The row's own tenant_id and order_id are the AAD for decryption.
    List<StoredRecipient> rows =
        jdbc.query(
            "SELECT order_id, tenant_id, name_enc, phone_enc, phone_last4, address_enc, province, "
                + "postcode, pii_status, redact_after FROM order_recipient WHERE order_id = ?",
            this::map,
            orderId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Nulls name/phone/address, phone_hash, and phone_last4, and marks the row REDACTED. Only
   * province and postcode remain (03 PII lifecycle), so the order can no longer be found by phone.
   * Returns false when no visible row exists.
   */
  public boolean redact(UUID orderId) {
    // Step 1: One UPDATE. order_recipient_redacted_check and order_recipient_phone_check back this
    // up in the database.
    return jdbc.update(
            "UPDATE order_recipient SET name_enc = NULL, phone_enc = NULL, address_enc = NULL, "
                + "phone_hash = NULL, phone_last4 = NULL, pii_status = 'REDACTED', "
                + "updated_at = now() WHERE order_id = ?",
            orderId)
        == 1;
  }

  /**
   * Orders of this tenant whose recipient phone matches after normalization, via phone_hash.
   * Redacted recipients are never returned.
   */
  public List<UUID> findOrderIdsByPhone(String phone) {
    // Step 1: Hash the normalized phone. The index (tenant_id, phone_hash) serves the lookup.
    // Redacted rows have no hash; the ACTIVE filter keeps that true even for a hand-edited row.
    byte[] hash = cipher.phoneHash(phone);
    return jdbc.query(
        "SELECT order_id FROM order_recipient WHERE tenant_id = ? AND phone_hash = ? "
            + "AND pii_status = 'ACTIVE' ORDER BY order_id",
        (rows, rowNum) -> rows.getObject("order_id", UUID.class),
        TenantContext.requireTenantId(),
        hash);
  }

  private StoredRecipient map(ResultSet rows, int rowNum) throws SQLException {
    UUID orderId = rows.getObject("order_id", UUID.class);
    UUID tenantId = rows.getObject("tenant_id", UUID.class);
    return new StoredRecipient(
        orderId,
        tenantId,
        cipher.decrypt(rows.getBytes("name_enc"), tenantId, orderId, PiiColumn.NAME),
        cipher.decrypt(rows.getBytes("phone_enc"), tenantId, orderId, PiiColumn.PHONE),
        cipher.decrypt(rows.getBytes("address_enc"), tenantId, orderId, PiiColumn.ADDRESS),
        rows.getString("phone_last4"),
        rows.getString("province"),
        rows.getString("postcode"),
        rows.getString("pii_status"),
        SalesOrderRepository.instant(rows.getTimestamp("redact_after")));
  }
}
