package com.thaishopfun.oms.stockdoc;

import com.thaishopfun.oms.catalog.CatalogAccess;
import com.thaishopfun.oms.catalog.CatalogAudit;
import com.thaishopfun.oms.stock.DocumentMovement;
import com.thaishopfun.oms.stock.StockDocumentType;
import com.thaishopfun.oms.stock.StockMovements;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Post and void. The stock work is {@link StockMovements}, which owns its transaction, so nothing
 * here opens one around it. Roles: RECEIVE may be posted by any member; OPENING, ADJUSTMENT, COUNT,
 * and WRITE_OFF, and every void, need OWNER or ADMIN, because they change stock without a supplier
 * trail. GRACE never gets here (entitlement gate).
 */
@Service
public class StockDocumentPosting {

  private final StockDocumentService documents;
  private final StockMovements movements;
  private final CatalogAccess access;
  private final CatalogAudit audit;

  public StockDocumentPosting(
      StockDocumentService documents,
      StockMovements movements,
      CatalogAccess access,
      CatalogAudit audit) {
    this.documents = documents;
    this.movements = movements;
    this.access = access;
    this.audit = audit;
  }

  public DocumentMovement post(UUID documentId) {
    // Step 1: Visible document (404 otherwise), then the role rule for its type.
    StockDocumentType type = documents.typeOf(documentId);
    CatalogAccess.Actor actor = type.requiresManager() ? access.requireWriter() : member();
    // Step 2: The engine post; the audit row is written inside its transaction.
    return movements.post(
        documentId,
        result ->
            audit.write(
                actor,
                "STOCK_DOCUMENT_POSTED",
                "stock_document",
                documentId,
                Map.of("status", "DRAFT"),
                after(result)));
  }

  public DocumentMovement voidDocument(UUID documentId) {
    // Step 1: Visible document, then OWNER or ADMIN for every type.
    documents.typeOf(documentId);
    CatalogAccess.Actor actor = access.requireWriter();
    // Step 2: Reversal in the engine, audit in the same transaction.
    return movements.voidDocument(
        documentId,
        result ->
            audit.write(
                actor,
                "STOCK_DOCUMENT_VOIDED",
                "stock_document",
                documentId,
                Map.of("status", "POSTED"),
                after(result)));
  }

  private static CatalogAccess.Actor member() {
    return new CatalogAccess.Actor(TenantContext.requireTenantId(), TenantContext.requireUserId());
  }

  private static Map<String, Object> after(DocumentMovement result) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("status", result.status());
    values.put("type", result.type().name());
    values.put("ledger_entries", result.movements().size());
    return values;
  }
}
