package com.thaishopfun.oms.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChannelAccountLookup {

  public record TsfAccount(UUID id, String mode, String status) {}

  private final JdbcTemplate jdbc;

  public ChannelAccountLookup(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** TSF account for this shop id ({@code envelope.tsf_shop_id}), never "oldest TSF account". */
  public Optional<TsfAccount> tsfByExternalShopId(String externalShopId) {
    List<TsfAccount> rows =
        jdbc.query(
            """
            SELECT id, mode, status
            FROM channel_account
            WHERE channel = 'TSF' AND external_shop_id = ?
            LIMIT 1
            """,
            (rs, row) ->
                new TsfAccount(
                    rs.getObject("id", UUID.class), rs.getString("mode"), rs.getString("status")),
            externalShopId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }
}
