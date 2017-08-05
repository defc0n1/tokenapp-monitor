package modum.io.monitor;

import static java.time.temporal.ChronoUnit.MINUTES;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.response.EthBlock.Block;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Convert.Unit;

/**
 * Connects to an ethereum full-node and scans the blockchain transactions for watched addresses.
 * Keeps track of the total amount in USD send to any watched address.
 */
class EthereumMonitor {
  private final static Logger LOG = LoggerFactory.getLogger(EthereumMonitor.class);

  private final Web3j web3;
  private final ExchangeRateService fxService;
  private boolean started = false;
  private Set<String> monitoredAddresses = new HashSet<>();
  private BigDecimal totalRaised = BigDecimal.ZERO;

  public EthereumMonitor(ExchangeRateService fxService, String ipcPath) {
    this.fxService = fxService;
    this.web3 = Web3j.build(new HttpService(ipcPath));
  }

  public void fundsReceived(BigInteger wei, Long blockHeight) {
    BigDecimal usd = null;
    try {
      usd = fxService.weiToUSD(wei, blockHeight);
    } catch (SQLException e) {
      LOG.error("Could not fetch exchange rate for ether block {}. {} {}",
          blockHeight, e.getMessage(), e.getCause());
      return;
    }
    totalRaised = totalRaised.add(usd);
    LOG.info("Payin: {} ether / {} USD ", Convert.fromWei(new BigDecimal(wei), Unit.ETHER).toString(), usd);
    LOG.info("New total: {} USD", totalRaised);
  }

  public Long getTotalRaisedUSD() {
    return totalRaised.setScale(0, BigDecimal.ROUND_UP).longValue();
  }

  public void addMonitoredAddress(String addressString) {
    LOG.info("Add monitored Ethereum Address: {}", addressString);
    monitoredAddresses.add(addressString.toLowerCase());
  }

  public void start(Long startBlock) throws IOException {
    if (!started) {
      // Check if node is up-to-date
      BigInteger blockNumber = web3.ethBlockNumber().send().getBlockNumber();
      Block highestBlock = web3.ethGetBlockByNumber(() -> new DefaultBlockParameterNumber(blockNumber).getValue(), false).send().getBlock();
      Instant latestBlockTime = Instant.ofEpochSecond(highestBlock.getTimestamp().longValue());
      LOG.info("Highest ethereum block number from fullnode: {}. Time: {}", blockNumber, latestBlockTime);
      if (latestBlockTime.isBefore(Instant.now().minus(10, MINUTES)))
        LOG.warn("Ethereum fullnode does not seem to be up-to-date");
      else
        LOG.info("Ethereum fullnode seems to be up-to-date");

      started = true;

      web3.catchUpToLatestAndSubscribeToNewTransactionsObservable(
          new DefaultBlockParameterNumber(startBlock))
          .subscribe(tx -> {
            if (monitoredAddresses.contains(tx.getTo())) {
              // Money was paid to a monitored address
              fundsReceived(tx.getValue(), tx.getBlockNumber().longValue());
            }

            if (monitoredAddresses.contains(tx.getFrom().toLowerCase())) {
              // This should normally not happen as it means funds are stolen!
              LOG.error("WARN: Removed: {} wei from payin address", tx.getValue().toString());
            }
          }, throwable -> {
            LOG.error("Error during scanning of txs: {} {}", throwable.getMessage(),
                throwable.getCause());
          });
    } else {
      LOG.warn("modum.io.monitor.EthereumMonitor is already started");
    }
  }
}
