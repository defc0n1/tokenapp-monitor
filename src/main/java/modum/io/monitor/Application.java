package modum.io.monitor;

import static spark.Spark.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Application {
  private final static Logger LOG = LoggerFactory.getLogger(Application.class);

  private final Long START_BLOCK;
  private final String ETHER_FULLNODE_URL;
  private final String JDBC_URL;
  private final String MODUM_TOKENAPP_BITCOIN_NETWORK;

  private EthereumMonitor ethereumMonitor;
  private BitcoinMonitor bitcoinMonitor;
  private Connection dbConnection;

  public Application() throws SQLException {
    START_BLOCK = Long.valueOf(System.getenv("START_BLOCK_ETHER"));
    ETHER_FULLNODE_URL = System.getenv("ETHER_FULLNODE_URL");
    JDBC_URL = System.getenv("JDBC_URL");
    MODUM_TOKENAPP_BITCOIN_NETWORK = System.getenv("MODUM_TOKENAPP_BITCOIN_NETWORK");
  }

  public static void main(String[] args) throws Exception {
    Application app = new Application();
    app.init();
  }

  private void init() throws Exception {
    ethereumMonitor = new EthereumMonitor(ETHER_FULLNODE_URL);
    bitcoinMonitor = new BitcoinMonitor(MODUM_TOKENAPP_BITCOIN_NETWORK);

    Properties databaseProps = new Properties();
    databaseProps.setProperty("user", System.getenv("DATASOURCE_USERNAME"));
    databaseProps.setProperty("password", System.getenv("DATASOURCE_PASSWORD"));
    dbConnection = DriverManager.getConnection(JDBC_URL, databaseProps);

    new DatabaseWatcher(dbConnection,
        newBitcoinAddress -> bitcoinMonitor .addMonitoredAddress(newBitcoinAddress,
            Instant.now().getEpochSecond()), ethereumMonitor::addMonitoredAddress);

    monitorExistingAddresses();

    ethereumMonitor.start(START_BLOCK);
    bitcoinMonitor.start();

    LOG.info("All monitors started");
    initRoutes();
  }

  private void monitorExistingAddresses() throws SQLException {
    Statement stm = dbConnection.createStatement();
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
