package modum.io.monitor;

import static org.bitcoinj.core.TransactionConfidence.ConfidenceType.*;

import com.subgraph.orchid.encoders.Hex;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionConfidence.Listener;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bitconin SPV wallet that scans the blockchain transactions for watched addresses.
 * Keeps track of the total amount in USD send to any watched address.
 */
public class BitcoinMonitor {
  private final static Logger LOG = LoggerFactory.getLogger(BitcoinMonitor.class);

  private final Context context;
  private final NetworkParameters chainParams;
  private final Wallet wallet;
  private final PeerGroup peerGroup;
  private final SPVBlockStore blockStore;
  private final ExchangeRateService fxService;
  private final UserService userService;
  private Set<TransactionOutput> processedUTXOs = new HashSet<>();
  private Map<String, String> monitoredAddresses = new HashMap<>(); // public key -> address
  private BigDecimal totalRaised = BigDecimal.ZERO;

  public BitcoinMonitor(UserService userService, ExchangeRateService fxService,
      String bitcoinNetwork) throws Exception {
    this.fxService = fxService;
    chainParams = BitcoinNet.getNetworkParams(BitcoinNet.of(bitcoinNetwork));
    this.userService = userService;
    context = new Context(chainParams);
    File blockStoreFile = Files.createTempFile("chain", "tmp").toFile();
    blockStoreFile.deleteOnExit();
    if (blockStoreFile.exists()) blockStoreFile.delete();
    wallet = new Wallet(context);
    blockStore = new SPVBlockStore(chainParams, blockStoreFile);
    if (chainParams.equals(MainNetParams.get())) {
      InputStream checkPoints = ClassLoader.getSystemResourceAsStream("checkpoints.txt");
      CheckpointManager.checkpoint(chainParams, checkPoints, blockStore, 1498867200L);
    } else if (chainParams.equals(TestNet3Params.get())) {
      InputStream checkPoints = ClassLoader.getSystemResourceAsStream("checkpoints-testnet.txt");
      CheckpointManager.checkpoint(chainParams, checkPoints, blockStore, 1498867200L);
    }
    BlockChain blockChain = new BlockChain(context, blockStore);
    peerGroup = new PeerGroup(context, blockChain);
    blockChain.addWallet(wallet);
    peerGroup.addWallet(wallet);

    // Regtest has no peer-to-peer functionality
    if (chainParams.equals(MainNetParams.get())) {
      peerGroup.addAddress(Inet4Address.getByName("192.41.136.217"));
      peerGroup.addAddress(Inet4Address.getByName("212.51.140.183"));
      peerGroup.addAddress(Inet4Address.getByName("85.5.108.217"));
      peerGroup.addAddress(Inet4Address.getByName("212.51.159.248"));
      peerGroup.addAddress(Inet4Address.getByName("83.76.178.6"));
      peerGroup.addAddress(Inet4Address.getByName("213.144.135.202"));
      peerGroup.addAddress(Inet4Address.getByName("194.15.231.236"));
      peerGroup.addAddress(Inet4Address.getByName("95.183.48.62"));
    } else if (chainParams.equals(TestNet3Params.get())) {
      peerGroup.addPeerDiscovery(new DnsDiscovery(chainParams));
    }

    addCoinsReceivedListener();
  }

  /**
   * Add a public key we want to monitor
   * @param publicKey Bitcoin public key as hex string
   * @param timestamp Timestamp in seconds when this key was created
   */
  public void addMonitoredPublicKey(String publicKey, long timestamp) {
    final Address address = ECKey.fromPublicOnly(Hex.decode(publicKey))
        .toAddress(chainParams);
    final String addressString = address.toBase58();
    LOG.info("Add monitored Bitcoin Address: {}", addressString);
    wallet.addWatchedAddress(address, timestamp);
    monitoredAddresses.put(addressString, publicKey);
  }

  public Long getTotalRaisedUSD() {
    return totalRaised.setScale(0, BigDecimal.ROUND_UP).longValue();
  }

  public void start() throws InterruptedException {
    peerGroup.start();

    // Download block chain (blocking)
    final DownloadProgressTracker downloadListener = new DownloadProgressTracker() {
      @Override
      protected void doneDownload() {
        LOG.info("Download done");
      }
      @Override
      protected void progress(double pct, int blocksSoFar, Date date) {
        LOG.info("Downloading chain: {}%", (int) pct);
      }
    };
    peerGroup.startBlockChainDownload(downloadListener);
    LOG.info("Downloading SPV blockchain...");
    downloadListener.await();
  }

  /**
   * Listens for changes to watched addresses
   */
  private void addCoinsReceivedListener() {
    wallet.addCoinsReceivedEventListener((wallet1, tx, prevBalance, newBalance) -> {
      Context.propagate(context);
      // Check outputs
      tx.getOutputs().forEach(utxo -> {

        // If not already processed and this output sends to one of our watched addresses
        if (!processedUTXOs.contains(utxo) && utxo.getScriptPubKey().isSentToAddress()) {
          Address address = utxo.getAddressFromP2PKHScript(chainParams);
          if (wallet1.getWatchedAddresses().contains(address)) {

            // If the confidence is already BUILDING (1 block or more on best chain)
            // we have a hit
            if (BitcoinUtils.isBuilding(tx)) {
              coinsReceived(utxo);

              // If pending or unknown we add a confidence changed listener and wait for block inclusion
            } else if (BitcoinUtils.isPending(tx) || BitcoinUtils.isUnknown(tx)) {
              LOG.info("Pending: {} satoshi received in {}", utxo.getValue(), tx.getHashAsString());
              Listener listener = new Listener() {
                @Override
                public void onConfidenceChanged(TransactionConfidence confidence, ChangeReason reason) {
                  if (!processedUTXOs.contains(utxo)) {
                    if (confidence.getConfidenceType().equals(BUILDING)) {
                      coinsReceived(utxo);
                      tx.getConfidence().removeEventListener(this);
                    } else if (confidence.getConfidenceType().equals(DEAD) || confidence
                        .getConfidenceType().equals(IN_CONFLICT)) {
                      tx.getConfidence().removeEventListener(this);
                    }
                  }
                }
              };
              tx.getConfidence().addEventListener(listener);
            }
          }
        }
      });
    });
  }

  /**
   * We have some funds send to us. This is called live or when catching-up at startup.
   * @param utxo The transaction output we received
   */
  private void coinsReceived(TransactionOutput utxo) {
    long satoshi = utxo.getValue().getValue();
    final String address = utxo.getAddressFromP2PKHScript(chainParams).toBase58();

    // Retrieve the timestamp from the first block that this transaction was seen in
    long timestamp = utxo.getParentTransaction().getAppearsInHashes().keySet().stream()
        .map((blockHash) -> {
          try {
            return blockStore.get(blockHash);
          } catch (BlockStoreException e) {
            return null; // This can happen if the transaction was seen in a side-chain
          }
        })
        .filter(Objects::nonNull)
        .map(StoredBlock::getHeader)
        .map(Block::getTime)
        .mapToLong(date -> (date.getTime() / 1000L))
        .min().orElse(0L);
    if (timestamp == 0L) {
      LOG.error("Could not get time for utxo in tx {} with satoshi value {}",
          address, satoshi);
      return;
    }

    // Calculate USD value
    BigDecimal USDperBTC = null;
    try {
      USDperBTC = fxService.getUSDPerBTC(timestamp);
    } catch (SQLException e) {
      LOG.error("Could not fetch exchange rate for utxo in tx {} with satoshi value {}. {} {}",
          address, satoshi, e.getMessage(), e.getCause());
      return;
    }
    BigDecimal usdReceived = BigDecimal.valueOf(satoshi)
        .multiply(USDperBTC)
        .divide(BigDecimal.valueOf(100_000_000L), BigDecimal.ROUND_DOWN);

    // Fetch email
    String publicKey = monitoredAddresses.get(address);
    String email = null;
    try {
      email = userService.getEmailForBitcoinPublicKey(publicKey);
    } catch (SQLException e) {
      LOG.error("Could not fetch email address for public key {} / address {}. {} {}",
          publicKey, address, e.getMessage(), e.getCause());
    }


    final String identifier = utxo.getParentTransaction().getHashAsString() + "_"
        + String.valueOf(utxo.getIndex());
    BigInteger value = new BigInteger(String.valueOf(satoshi));
    Instant blockTime = Instant.ofEpochSecond(timestamp);
    boolean inserted = false;
    try {
      inserted = userService.savePayIn(identifier, "BTC", value, USDperBTC, usdReceived, email );
    } catch (SQLException e) {
      LOG.error("Could not save payin: {} / {} USD / {} FX / {} / Time: {] / Address: {}",
          utxo.getValue().toFriendlyString(),
          usdReceived,
          USDperBTC,
          email,
          timestamp,
          address);
    }

    LOG.info("Payin: new:{} / {} / {} USD / {} FX / {} / Time: {] / Address: {}",
        inserted,
        utxo.getValue().toFriendlyString(),
        usdReceived,
        USDperBTC,
        email,
        timestamp,
        address);

    processedUTXOs.add(utxo);
    totalRaised = totalRaised.add(usdReceived);
  }

}
