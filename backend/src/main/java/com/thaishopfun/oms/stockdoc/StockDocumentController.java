package com.thaishopfun.oms.stockdoc;

import com.thaishopfun.oms.catalog.PageResult;
import com.thaishopfun.oms.stock.DocumentMovement;
import com.thaishopfun.oms.stockdoc.StockDocumentView.LineView;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock-documents")
class StockDocumentController {

  private final StockDocumentService documents;
  private final StockDocumentPosting posting;

  StockDocumentController(StockDocumentService documents, StockDocumentPosting posting) {
    this.documents = documents;
    this.posting = posting;
  }

  @GetMapping
  ResponseEntity<PageResult<StockDocumentView>> list(
      @RequestParam(name = "type", required = false) String type,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", required = false) Integer offset) {
    return ok(documents.list(type, status, from, to, limit, offset));
  }

  @PostMapping
  ResponseEntity<StockDocumentView> create(@RequestBody StockDocumentRequest request) {
    StockDocumentView created = documents.create(request);
    return ResponseEntity.created(URI.create("/api/v1/stock-documents/" + created.id()))
        .cacheControl(CacheControl.noStore())
        .body(created);
  }

  @GetMapping("/{id}")
  ResponseEntity<StockDocumentView> get(@PathVariable UUID id) {
    return ok(documents.get(id));
  }

  @PutMapping("/{id}")
  ResponseEntity<StockDocumentView> update(
      @PathVariable UUID id, @RequestBody StockDocumentRequest request) {
    return ok(documents.update(id, request));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID id) {
    documents.delete(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/lines")
  ResponseEntity<LineView> addLine(
      @PathVariable UUID id, @RequestBody StockDocumentLineRequest request) {
    LineView line = documents.addLine(id, request);
    return ResponseEntity.created(
            URI.create("/api/v1/stock-documents/" + id + "/lines/" + line.id()))
        .cacheControl(CacheControl.noStore())
        .body(line);
  }

  @PutMapping("/{id}/lines/{lineId}")
  ResponseEntity<LineView> updateLine(
      @PathVariable UUID id,
      @PathVariable UUID lineId,
      @RequestBody StockDocumentLineRequest request) {
    return ok(documents.updateLine(id, lineId, request));
  }

  @DeleteMapping("/{id}/lines/{lineId}")
  ResponseEntity<Void> deleteLine(@PathVariable UUID id, @PathVariable UUID lineId) {
    documents.deleteLine(id, lineId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/start-count")
  ResponseEntity<StockDocumentView> startCount(@PathVariable UUID id) {
    return ok(documents.startCount(id));
  }

  @PostMapping("/{id}/post")
  ResponseEntity<DocumentMovement> post(@PathVariable UUID id) {
    return ok(posting.post(id));
  }

  @PostMapping("/{id}/void")
  ResponseEntity<DocumentMovement> voidDocument(@PathVariable UUID id) {
    return ok(posting.voidDocument(id));
  }

  static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
