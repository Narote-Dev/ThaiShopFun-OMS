package com.thaishopfun.oms.listing;

import com.thaishopfun.oms.catalog.CatalogApiException;
import com.thaishopfun.oms.catalog.SqlErrors;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ListingTransactions {

  private final TransactionTemplate read;
  private final TransactionTemplate write;

  ListingTransactions(PlatformTransactionManager transactions) {
    this.read = new TransactionTemplate(transactions);
    read.setReadOnly(true);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  <T> T read(Supplier<T> work) {
    return read.execute(status -> work.get());
  }

  <T> T write(Supplier<T> work) {
    try {
      return write.execute(status -> work.get());
    } catch (ListingApiException | CatalogApiException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      CatalogApiException mapped = SqlErrors.translate(ex, null);
      if (mapped != null) {
        throw mapped;
      }
      throw ex;
    }
  }
}
