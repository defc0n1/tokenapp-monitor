package modum.io.monitor;

import static spark.Spark.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.apache.http.conn.HttpHostConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Application {
  private final static Logger LOG = LoggerFactory.getLogger(Application.class);

  private final Long START_BLOCK;
  private final String ETHER_FULLNODE_URL;
  private final String MODUM_TOKENAPP_BITCOIN_NETWORK;
  private final String JDBC_URL;
  private final String DATASOURCE_USERNAME;
  private final String DATASOURCE_PASSWORD;
  private boolean MODUM_TOKENAPP_ENABLE_CORS;
  private boolean MODUM_TOKENAPP_CREATE_SCHEMA;

  private EthereumMonitor ethereumMonitor;
  private BitcoinMonitor bitcoinMonitor;
  private ExchangeRateService fxService;
  private UserService userService;
  private HikariDataSource databaseSource;
  private DatabaseWatcher databaseWatcher;

  public Application() throws SQLException {
    // Required configurations
    ETHER_FULLNODE_URL = Optional.ofNullable(System.getenv("ETHER_FULLNODE_URL"))
        .orElseThrow(() -> new IllegalArgumentException("Missing env variable: ETHER_FULLNODE_URL"));
    JDBC_URL = Optional.ofNullable(System.getenv("JDBC_URL"))
        .orElseThrow(() -> new IllegalArgumentException("Missing env variable: JDBC_URL"));

    // Optional configurations
    DATASOURCE_USERNAME = System.getenv("DATASOURCE_USERNAME");
    DATASOURCE_PASSWORD = System.getenv("DATASOURCE_PASSWORD");
    MODUM_TOKENAPP_CREATE_SCHEMA = Boolean.parseBoolean(Optional.ofNullable(System.getenv("MODUM_TOKENAPP_CREATE_SCHEMA")).orElse("false"));
    MODUM_TOKENAPP_ENABLE_CORS = Boolean.parseBoolean(Optional.ofNullable(System.getenv("MODUM_TOKENAPP_ENABLE_CORS")).orElse("false"));
    MODUM_TOKENAPP_BITCOIN_NETWORK = Optional.ofNullable(System.getenv("MODUM_TOKENAPP_BITCOIN_NETWORK")).orElse("mainnet");
    START_BLOCK = Long.valueOf(Optional.ofNullable(System.getenv("START_BLOCK_ETHER")).orElse("1"));

  }

  public static void main(String[] args) throws Exception {
    Application app = new Application();
    app.init();
  }

  private void init() throws Exception {
    try {
      initDatabase();
      initExchangeRateService();
      initUserService();
      initMonitors();
      initRoutes();
    } catch (HttpHostConnectException e) {
      LOG.error("Could not connect to ethereum fullnode on {}: {}", ETHER_FULLNODE_URL, e.getMessage());
      System.exit(1);
    }
  }

  private void initUserService() {
    this.userService = new UserService(databaseSource);
  }

  private void initDatabase() {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(JDBC_URL);
    hikariConfig.setUsername(DATASOURCE_USERNAME);
    hikariConfig.setPassword(DATASOURCE_PASSWORD);
    databaseSource = new HikariDataSource(hikariConfig);
  }

  private void initMonitors() throws Exception {
    ethereumMonitor = new EthereumMonitor(userService, fxService, ETHER_FULLNODE_URL);
    bitcoinMonitor = new BitcoinMonitor(userService, fxService, MODUM_TOKENAPP_BITCOIN_NETWORK);

    databaseWatcher = new DatabaseWatcher(databaseSource,
        newBitcoinAddress -> {
          bitcoinMonitor.addMonitoredPublicKey(newBitcoinAddress, Instant.now().getEpochSecond());
        },
        newEthereumAddress -> {
          ethereumMonitor.addMonitoredEtherPublicKey(newEthereumAddress);
        },
        MODUM_TOKENAPP_CREATE_SCHEMA);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> databaseWatcher.stop()));

    monitorExistingAddresses();

    ethereumMonitor.start(START_BLOCK);
    bitcoinMonitor.start();

    LOG.info("All monitors started");
  }

  /***
   * Load all pay-in addresses from the database and start watching them
   * @throws SQLException
   */
  private void monitorExistingAddresses() throws SQLException {
    Statement stm = databaseSource.getConnection().createStatement();
    ResultSet rs = stm.executeQuery(""
        + "SELECT pay_in_bitcoin_public_key, pay_in_ether_public_key, creation_date FROM investor;");

    while(rs.next()) {
      String bitcoinPublicKey = rs.getString("pay_in_bitcoin_public_key");
      String etherPublicKey = rs.getString("pay_in_ether_public_key");
      Date creationDate = rs.getDate("creation_date");
      long timestamp = creationDate.getTime() / 1000L;

      if (bitcoinPublicKey != null)
        bitcoinMonitor.addMonitoredPublicKey(bitcoinPublicKey, timestamp);

      if (etherPublicKey != null)
        ethereumMonitor.addMonitoredEtherPublicKey(etherPublicKey);
    }
  }

  private void initExchangeRateService() {
    this.fxService = new ExchangeRateService(databaseSource);
  }

  /***
   * Configures the Spark endpoint
   */
  private void initRoutes() {
    if (MODUM_TOKENAPP_ENABLE_CORS) {
      options("/*", (request, response) -> {
        String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
        if (accessControlRequestHeaders != null) {
          response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
        }

        String accessControlRequestMethod = request.headers("Access-Control-Request-Method");
        if (accessControlRequestMethod != null) {
          response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
        }

        return "OK";
      });
    }

    get("/", (req, res) -> {
      if (MODUM_TOKENAPP_ENABLE_CORS) {
        res.header("Access-Control-Allow-Origin", "*");
        res.header("Access-Control-Request-Method", "*");
        res.header("Access-Control-Allow-Headers", "*");
      }
      return getTotalRaisedUSD().toString();
    });
  }

  private Long getTotalRaisedUSD() {
    return ethereumMonitor.getTotalRaisedUSD() + bitcoinMonitor.getTotalRaisedUSD();
  }

}
