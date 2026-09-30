package com.thaishopfun.oms.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CatalogUnitTest {

  @Test
  void triggerErrorsMapByConstraintNameNotMessage() {
    // Step 1: The message text is deliberately unrelated. Only SQLSTATE + constraint count.
    assertMapped("23514", "sku_bundle_nesting", 422, "NESTED_BUNDLE");
    assertMapped("23514", "sku_bundle_parent", 422, "BUNDLE_REQUIRED");
    assertMapped("23514", "sku_bundle_stock", 422, "BUNDLE_NOT_STOCKABLE");
    assertMapped("23505", "sku_tenant_sku_code_key", 409, "SKU_CODE_EXISTS");
    assertMapped("23505", "warehouse_tenant_code_key", 409, "WAREHOUSE_CODE_EXISTS");
    assertMapped("23514", "sku_weight_g_check", 422, "VALIDATION_FAILED");
    assertThat(SqlErrors.translate(wrapped("23503", "inventory_sku_fkey"), "SKU_IN_USE").code())
        .isEqualTo("SKU_IN_USE");

    // Step 2: The same prefix in the message with another constraint is not NESTED_BUNDLE.
    assertThat(
            SqlErrors.translate(wrapped("23514", "other_check", "NESTED_BUNDLE: x"), null).code())
        .isEqualTo("VALIDATION_FAILED");

    // Step 3: READ_COMMITTED_REQUIRED is a bug, never a user error.
    assertThat(SqlErrors.translate(wrapped("23514", "sku_bundle_isolation"), null)).isNull();
    assertThat(SqlErrors.translate(wrapped("40P01", null), null)).isNull();
  }

  @Test
  void writesRunAtReadCommittedAndRetryDeadlocks() {
    RecordingManager manager = new RecordingManager();
    CatalogTransactions tx = new CatalogTransactions(manager);
    AtomicInteger calls = new AtomicInteger();

    // Step 1: Two deadlocks, then success. Every attempt is a fresh READ COMMITTED transaction.
    String result =
        tx.write(
            null,
            () -> {
              if (calls.incrementAndGet() < 3) {
                throw new PessimisticLockingFailureException("deadlock", psql("40P01", null, "x"));
              }
              return "ok";
            });
    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(3);
    assertThat(manager.isolation).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(manager.rollbacks).isEqualTo(2);

    // Step 2: Bounded. The third deadlock surfaces.
    calls.set(0);
    assertThatThrownBy(
            () ->
                tx.write(
                    null,
                    () -> {
                      calls.incrementAndGet();
                      throw new PessimisticLockingFailureException(
                          "deadlock", psql("40P01", null, "x"));
                    }))
        .isInstanceOf(PessimisticLockingFailureException.class);
    assertThat(calls).hasValue(CatalogTransactions.MAX_ATTEMPTS);

    // Step 3: A constraint error is mapped and not retried.
    calls.set(0);
    assertThatThrownBy(
            () ->
                tx.write(
                    "SKU_IN_USE",
                    () -> {
                      calls.incrementAndGet();
                      throw new DataIntegrityViolationException(
                          "fk", psql("23503", "inventory_sku_fkey", "x"));
                    }))
        .isInstanceOfSatisfying(
            CatalogApiException.class, ex -> assertThat(ex.code()).isEqualTo("SKU_IN_USE"));
    assertThat(calls).hasValue(1);
  }

  @Test
  void csvParserHandlesQuotesLineEndingsAndLineNumbers() throws Exception {
    String text =
        "\uFEFFa,b,c\r\n" + "1,\"x, \"\"y\"\"\",3\r\n" + "\n" + "4,\"multi\nline\",6\n" + "7,8,9";
    List<CsvParser.Record> records = CsvParser.parse(text.getBytes(StandardCharsets.UTF_8));
    assertThat(records).hasSize(4);
    assertThat(records.get(0).fields()).containsExactly("a", "b", "c");
    assertThat(records.get(1).fields()).containsExactly("1", "x, \"y\"", "3");
    assertThat(records.get(1).line()).isEqualTo(2);
    assertThat(records.get(2).line()).isEqualTo(4);
    assertThat(records.get(2).fields().get(1)).isEqualTo("multi\nline");
    assertThat(records.get(3).line()).isEqualTo(6);

    assertThatThrownBy(() -> CsvParser.parse("a,\"b\n".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(CsvParser.CsvException.class)
        .hasMessageContaining("unterminated");
    assertThatThrownBy(() -> CsvParser.parse(new byte[] {'a', (byte) 0xE9, '\n'}))
        .hasMessageContaining("UTF-8");
  }

  @Test
  void fieldRules() {
    assertThat(Fields.skuCodeError("ABC-1")).isNull();
    assertThat(Fields.skuCodeError("A B")).isNotNull();
    assertThat(Fields.skuCodeError("A:1")).isNotNull();
    assertThat(Fields.skuCodeError("A|B")).isNotNull();
    assertThat(Fields.weightError(-1)).isNotNull();
    assertThat(Fields.qtyError(0)).isNotNull();
    assertThat(Fields.likeEscape("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
  }

  private static void assertMapped(String state, String constraint, int status, String code) {
    CatalogApiException mapped = SqlErrors.translate(wrapped(state, constraint), null);
    assertThat(mapped).as(constraint).isNotNull();
    assertThat(mapped.status()).as(constraint).isEqualTo(status);
    assertThat(mapped.code()).as(constraint).isEqualTo(code);
  }

  private static RuntimeException wrapped(String state, String constraint) {
    return wrapped(state, constraint, "unrelated text");
  }

  private static RuntimeException wrapped(String state, String constraint, String message) {
    return new DataIntegrityViolationException("wrapped", psql(state, constraint, message));
  }

  private static PSQLException psql(String state, String constraint, String message) {
    StringBuilder raw = new StringBuilder("SERROR\0C").append(state).append("\0M").append(message);
    if (constraint != null) {
      raw.append("\0n").append(constraint);
    }
    raw.append('\0');
    return new PSQLException(new ServerErrorMessage(raw.toString()));
  }

  private static final class RecordingManager implements PlatformTransactionManager {

    int isolation = -2;
    int rollbacks;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      isolation = definition.getIsolationLevel();
      return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {
      rollbacks++;
    }
  }
}
