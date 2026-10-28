package com.thaishopfun.oms.fulfillment;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pick-lists")
class PickListController {

  private final PickListService pickLists;

  PickListController(PickListService pickLists) {
    this.pickLists = pickLists;
  }

  @GetMapping("/candidates")
  FulfillmentViews.CandidatesView candidates() {
    return pickLists.candidates();
  }

  @PostMapping
  ResponseEntity<FulfillmentViews.CreatePickListResponse> create(
      @RequestBody FulfillmentViews.CreatePickListBody body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pickLists.create(body));
  }

  @GetMapping("/{id}")
  FulfillmentViews.PickListDetail detail(@PathVariable UUID id) {
    return pickLists.detail(id);
  }

  @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
    byte[] bytes = pickLists.pdf(id);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_PDF)
        .header("Content-Disposition", "inline; filename=\"pick-list-" + id + ".pdf\"")
        .body(bytes);
  }
}
