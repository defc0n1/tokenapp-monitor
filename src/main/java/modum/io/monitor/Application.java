package modum.io.monitor;

import static spark.Spark.*;

import java.sql.SQLException;
import java.time.Instant;
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

    new DatabaseWatcher(JDBC_URL,
        newBitcoinAddress -> bitcoinMonitor .addMonitoredAddress(newBitcoinAddress,
            Instant.now().getEpochSecond()), ethereumMonitor::addMonitoredAddress);

    // TODO: add all addresses already in database

    ethereumMonitor.start(START_BLOCK);
    bitcoinMonitor.start();

    LOG.info("All monitors started");
    initRoutes();
  }

  private void initRoutes() {
    get("/", (req, res) -> getTotalRaisedUSD().toString());
  }

  private Long getTotalRaisedUSD() {
    return ethereumMonitor.getTotalRaisedUSD() + bitcoinMonitor.getTotalRaisedUSD();
  }

}
