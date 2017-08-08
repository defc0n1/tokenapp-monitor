package modum.io.monitor;

import static java.time.temporal.ChronoUnit.MINUTES;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.codec.binary.Hex;
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
  private final UserService userService;
  private boolean started = false;
  private Map<String, String> monitoredAddresses = new HashMap<>(); // public key -> address
  private BigDecimal totalRaised = BigDecimal.ZERO;

  public EthereumMonitor(UserService userService, ExchangeRateService fxService, String ipcPath) {
    this.userService = userService;
    this.fxService = fxService;
    this.web3 = Web3j.build(new HttpService(ipcPath));
  }

  public void fundsReceived(String hash, String address, BigInteger wei, Long blockHeight) {
    // Get exchange rate
    BigDecimal USDperETH;
    try {
      USDperETH = fxService.getUSDperETH(blockHeight);
    } catch (SQLException e) {
      LOG.error("Could not fetch exchange rate for ether block {}. {} {}",
          blockHeight, e.getMessage(), e.getCause());
      return;
    }
    BigDecimal ethers = Convert.fromWei(new BigDecimal(wei), Unit.ETHER);
    BigDecimal usdReceived = ethers.multiply(USDperETH);

    // Get email of investor
    String email = null;
    try {
      email = userService.getEmailForEtherPublicKey(monitoredAddresses.get(address));
    } catch (SQLException e) {
      LOG.error("Could not fetch email address for public key {} / address {}. {} {}",
          monitoredAddresses.get(address), address, e.getMessage(), e.getCause());
    }

    boolean inserted = false;
    try {
      inserted = userService.savePayIn(hash, "ETH", wei, USDperETH, usdReceived, email );
    } catch (SQLException e) {
      LOG.info("Could not save payin: {} ETH / {} USD / {} FX / {} / Block: {}",
          ethers,
          usdReceived,
          USDperETH,
          email,
          blockHeight);
    }

    LOG.info("Payin: new:{} / {} ETH / {} USD / {} FX / {} / Block: {}",
        inserted,
        ethers,
        usdReceived,
        USDperETH,
        email,
        blockHeight);

    totalRaised = totalRaised.add(usdReceived);
  }

  public Long getTotalRaisedUSD() {
    return totalRaised.setScale(0, BigDecimal.ROUND_UP).longValue();
  }

  /**
   * Add a public key we want to monitor
   * @param publicKey Ethereum public key as hex string
   */
  public void addMonitoredEtherPublicKey(String publicKey) {
    String addressString = Hex.encodeHexString(org.ethereum.crypto.ECKey.fromPublicOnly(
        org.spongycastle.util.encoders.Hex.decode(publicKey)).getAddress());
    if (!addressString.startsWith("0x"))
      addressString = "0x" + addressString;
    LOG.info("Add monitored Ethereum Address: {}", addressString);
    monitoredAddresses.put(addressString.toLowerCase(), publicKey);
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
            if (monitoredAddresses.get(tx.getTo()) != null) {
              // Money was paid to a monitored address
              try {
                fundsReceived(tx.getHash(), tx.getTo(), tx.getValue(), tx.getBlockNumber().longValue());
              } catch (Throwable e) {
                LOG.error("Error in fundsReceived: {} {}", e.getMessage(), e.getCause());
              }
            }

            if (monitoredAddresses.get(tx.getFrom().toLowerCase()) != null) {
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
