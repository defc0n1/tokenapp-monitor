package modum.io.monitor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.utils.Convert;
import org.web3j.utils.Convert.Unit;

/**
 * Service providing (historical) exchange rates for bitcoin and ethereum.
 * Uses the database table 'exchange_rate' to fetch exchange rates, which must be filled
 * by another service.
 *
 * Each exchange rate requires the block height, for which the exchange rate should be fetched.
 * If the block height is not in the database the next older block is used instead.
 *
 */
public class ExchangeRateService {
  private final static Logger LOG = LoggerFactory.getLogger(ExchangeRateService.class);

  private final DataSource dataSource;

  public ExchangeRateService(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public BigDecimal getUSDperETH(Long blockHeight) throws SQLException {
    try (
        Connection conn = dataSource.getConnection();
        PreparedStatement preparedStatement = conn.prepareStatement(
        "SELECT rate_eth FROM exchange_rate \n"
            + "WHERE block_nr_eth = \n"
            + "  (SELECT MAX(block_nr_eth) FROM exchange_rate \n"
            + "   WHERE block_nr_eth <= ?\n"
            + "   AND rate_eth IS NOT NULL) \n"
            + "AND rate_eth IS NOT NULL\n"
            + "ORDER BY creation_date ASC LIMIT 1;\n");
    ) {
      preparedStatement.setLong(1, blockHeight);
      try (ResultSet rs = preparedStatement.executeQuery()) {
        if (rs.next()) {
          return new BigDecimal(rs.getString("rate_eth"));
        } else {
          throw new RuntimeException("Result set empty from get exchange rate for eth");
        }
      }
    }
  }

  public BigDecimal getUSDPerBTC(Long timestamp) throws SQLException {
    try (
        Connection conn = dataSource.getConnection();
        PreparedStatement preparedStatement = conn.prepareStatement(
            "SELECT rate_btc FROM exchange_rate \n"
                + "WHERE creation_date = \n"
                + "  (SELECT MAX(creation_date) FROM exchange_rate \n"
                + "   WHERE creation_date <= ?\n"
                + "   AND rate_btc IS NOT NULL) \n"
                + "AND rate_btc IS NOT NULL\n"
                + "ORDER BY creation_date ASC LIMIT 1;\n");
    ) {
      preparedStatement.setTimestamp(1, Timestamp.from(Instant.ofEpochSecond(timestamp)));
      try (ResultSet rs = preparedStatement.executeQuery()) {
        if (rs.next()) {
          return new BigDecimal(rs.getString("rate_btc"));
        } else {
          throw new RuntimeException("Result set empty from get exchange rate for btc");
        }
      }
    }
  }

  public BigDecimal weiToUSD(BigInteger weiAmount, Long blockHeight) throws SQLException {
    BigDecimal wei = new BigDecimal(weiAmount);
    BigDecimal ethers = Convert.fromWei(wei, Unit.ETHER);
    return ethers.multiply(getUSDperETH(blockHeight));
  }
}
