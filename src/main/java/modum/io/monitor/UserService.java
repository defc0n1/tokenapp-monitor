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

public class UserService {
  private final static Logger LOG = LoggerFactory.getLogger(UserService.class);

  private final DataSource dataSource;

  public UserService(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public String getEmailForEtherPublicKey(String ethereumPublicKey) throws SQLException {
    try (
        Connection conn = dataSource.getConnection();
        PreparedStatement preparedStatement = conn.prepareStatement(""
        + "SELECT email FROM investor WHERE pay_in_ether_public_key = ?");
    ) {
      preparedStatement.setString(1, ethereumPublicKey);
      try (ResultSet rs = preparedStatement.executeQuery()) {
        if (rs.next()) {
          return rs.getString("email");
        } else {
          throw new RuntimeException("Result set empty from getEmailForEtherPublicKey");
        }
      }
    }
  }

  public String getEmailForBitcoinPublicKey(String bitcoinPublicKey) throws SQLException {
    try (
        Connection conn = dataSource.getConnection();
        PreparedStatement preparedStatement = conn.prepareStatement(""
            + "SELECT email FROM investor WHERE pay_in_bitcoin_public_key = ?");
    ) {
      preparedStatement.setString(1, bitcoinPublicKey);
      try (ResultSet rs = preparedStatement.executeQuery()) {
        if (rs.next()) {
          return rs.getString("email");
        } else {
          throw new RuntimeException("Result set empty from getEmailForBitcoinPublicKey");
        }
      }
    }
  }

  public void savePayIn(String identifier, String currency, BigInteger value, BigDecimal fxRate,
      BigDecimal usd, String email)
      throws SQLException {
    try (
        Connection conn = dataSource.getConnection();
        PreparedStatement preparedStatement = conn.prepareStatement(""
            + "INSERT INTO payment_log (tx_identifier, creation_date, currency, paymentvalue, fx_rate,"
            + "usd, email) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)");
    ) {
      Timestamp now = Timestamp.from(Instant.now());
      BigDecimal paymentValue = new BigDecimal(value);
      preparedStatement.setString(1, identifier);
      preparedStatement.setTimestamp(2, now);
      preparedStatement.setString(3, currency);
      preparedStatement.setBigDecimal(4, paymentValue);
      preparedStatement.setBigDecimal(5, fxRate);
      preparedStatement.setBigDecimal(6, usd);
      preparedStatement.setString(7, email);
      preparedStatement.execute();
    }
  }

}
