package modum.io.monitor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

}
