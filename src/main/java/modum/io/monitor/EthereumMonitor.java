package modum.io.monitor;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Convert.Unit;

class EthereumMonitor {
  private final static Logger LOG = LoggerFactory.getLogger(EthereumMonitor.class);

  private final Web3j web3;
  private boolean started = false;
  private Set<String> monitoredAddresses = new HashSet<>();
  private BigDecimal totalRaised = BigDecimal.ZERO;

  public EthereumMonitor(String ipcPath) {
    this.web3 = Web3j.build(new HttpService(ipcPath));
  }

  public void fundsReceived(BigInteger wei, Long timestamp) {
    BigDecimal usd = ExchangeRateService.weiToUSD(wei, timestamp);
    totalRaised = totalRaised.add(usd);
    LOG.info("Payin: {} ether / {} USD / FX-Rate: {}", Convert.fromWei(new BigDecimal(wei), Unit.ETHER).toString(), usd, ExchangeRateService.getUSDPerBTC(timestamp));
    LOG.info("New total: {} USD", totalRaised);
  }

  public Long getTotalRaisedUSD() {
    return totalRaised.setScale(0, BigDecimal.ROUND_UP).longValue();
  }

  public void addMonitoredAddress(String addressString) {
    LOG.info("Add monitored Ethereum Address: {}", addressString);
    monitoredAddresses.add(addressString.toLowerCase());
  }

  public void start(Long startBlock) {
    if (!started) {
      started = true;
      web3.catchUpToLatestAndSubscribeToNewTransactionsObservable(
          new DefaultBlockParameterNumber(startBlock))
          .subscribe(tx -> {
            if (monitoredAddresses.contains(tx.getTo())) {
              // Money was paid to a monitored address
              try {
                Long timestamp = web3.ethGetBlockByHash(tx.getBlockHash(), false).send().getBlock().getTimestamp().longValue();
                fundsReceived(tx.getValue(), timestamp);
              } catch (IOException e) {
                LOG.error("Could not fetch block {} for payin transaction {}: {}", tx.getBlockHash(), tx.getHash(), e.getMessage());
              }
            }

            if (monitoredAddresses.contains(tx.getFrom().toLowerCase())) {
              // This should not normally not happen as it means funds are stolen!
              LOG.error("WARN: Removed: {} wei from payin address", tx.getValue().toString());
            }
          }, throwable -> {
            LOG.error("Error during scanning of txs: {}", throwable.getMessage());
            throwable.printStackTrace();
          });
    } else {
      LOG.warn("modum.io.monitor.EthereumMonitor is already started");
    }
  }
}
