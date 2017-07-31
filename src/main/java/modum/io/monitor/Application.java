package modum.io.monitor;

import static spark.Spark.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Application {
  private final static Logger LOG = LoggerFactory.getLogger(Application.class);

  private final Long START_BLOCK;
  private final String ETHER_FULLNODE_URL;
  private final String MODUM_TOKENAPP_BITCOIN_NETWORK;

  private EthereumMonitor ethereumMonitor;
  private BitcoinMonitor bitcoinMonitor;
  private HikariDataSource databaseSource;

  public Application() throws SQLException {
    START_BLOCK = Long.valueOf(System.getenv("START_BLOCK_ETHER"));
    ETHER_FULLNODE_URL = System.getenv("ETHER_FULLNODE_URL");
    MODUM_TOKENAPP_BITCOIN_NETWORK = System.getenv("MODUM_TOKENAPP_BITCOIN_NETWORK");
  }

  public static void main(String[] args) throws Exception {
    Application app = new Application();
    app.initDatabase();
    app.initMonitors();
  }

  private void initDatabase() {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(System.getenv("JDBC_URL"));
    hikariConfig.setUsername(System.getenv("DATASOURCE_USERNAME"));
    hikariConfig.setPassword(System.getenv("DATASOURCE_PASSWORD"));
    databaseSource = new HikariDataSource(hikariConfig);
  }

  private void initMonitors() throws Exception {
    ethereumMonitor = new EthereumMonitor(ETHER_FULLNODE_URL);
    bitcoinMonitor = new BitcoinMonitor(MODUM_TOKENAPP_BITCOIN_NETWORK);


    new DatabaseWatcher(databaseSource,
        newBitcoinAddress -> bitcoinMonitor .addMonitoredAddress(newBitcoinAddress,
            Instant.now().getEpochSecond()), ethereumMonitor::addMonitoredAddress);

    monitorExistingAddresses();

    ethereumMonitor.start(START_BLOCK);
    bitcoinMonitor.start();

    LOG.info("All monitors started");
    initRoutes();
  }

  private void monitorExistingAddresses() throws SQLException {
    Statement stm = databaseSource.getConnection().createStatement();
    ResultSet rs = stm.executeQuery("SELECT pay_in_bitcoin_address, pay_in_ether_address, "
        + "creation_date FROM investor;");

    while(rs.next()) {
      String bitcoinAddress = rs.getString("pay_in_bitcoin_address");
      String ethereumAddress = rs.getString("pay_in_ether_address");
      Date creationDate = rs.getDate("creation_date");
      long timestamp = creationDate.getTime() / 1000L;

      if (bitcoinAddress != null)
        bitcoinMonitor.addMonitoredAddress(bitcoinAddress, timestamp);

      if (ethereumAddress != null)
        ethereumMonitor.addMonitoredAddress(ethereumAddress);
    }
  }

  private void initRoutes() {
    get("/", (req, res) -> getTotalRaisedUSD().toString());
  }

  private Long getTotalRaisedUSD() {
    return ethereumMonitor.getTotalRaisedUSD() + bitcoinMonitor.getTotalRaisedUSD();
  }

}
