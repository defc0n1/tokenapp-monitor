package modum.io.monitor;

import static org.bitcoinj.core.TransactionConfidence.ConfidenceType.*;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.Date;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionConfidence.Listener;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BitcoinMonitor {
  private final static Logger LOG = LoggerFactory.getLogger(BitcoinMonitor.class);

  private final Context context;
  private final NetworkParameters chainParams;
  private final Wallet wallet;
  private final PeerGroup peerGroup;
  private final SPVBlockStore blockStore;
  private Set<TransactionOutput> processedUTXOs = new HashSet<>();
  private BigDecimal totalRaised = BigDecimal.ZERO;

  public BitcoinMonitor(String bitcoinNetwork) throws Exception {
    chainParams = BitcoinNet.getNetworkParams(BitcoinNet.of(bitcoinNetwork));
    context = new Context(chainParams);
    File blockStoreFile = Files.createTempFile("chain", "tmp").toFile();
    blockStoreFile.deleteOnExit();
    if (blockStoreFile.exists()) blockStoreFile.delete();
    wallet = new Wallet(context);
    blockStore = new SPVBlockStore(chainParams, blockStoreFile);
    BlockChain blockChain = new BlockChain(context, blockStore);
    peerGroup = new PeerGroup(context, blockChain);
    blockChain.addWallet(wallet);
    peerGroup.addWallet(wallet);

    // Regtest has no peer-to-peer functionality
    if (!chainParams.equals(RegTestParams.get()))
      peerGroup.addPeerDiscovery(new DnsDiscovery(chainParams));

    addCoinsReceivedListener();
  }

  /**
   * Add an address we want to monitor
   * @param addressString Bitcoin address in Base58 String
   * @param timestamp Timestamp in seconds when this address was created
   */
  public void addMonitoredAddress(String addressString, long timestamp) {
    LOG.info("Add monitored Bitcoin Address: {}", addressString);
    Address address = Address.fromBase58(chainParams, addressString);
    wallet.addWatchedAddress(address, timestamp);
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
              LOG.info("Pending: {} coins received in {}", utxo.getValue(), tx.getHashAsString());
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
        .min().orElseThrow(() -> new RuntimeException("Could not get time of block"));

    BigDecimal USDperBTC = ExchangeRateService.getUSDPerBTC(timestamp);
    BigDecimal usdReceived = BigDecimal.valueOf(satoshi)
        .multiply(USDperBTC)
        .divide(BigDecimal.valueOf(100000000L), BigDecimal.ROUND_DOWN);

    LOG.info("Received {} USD / {} satoshi / {} timestamp / {} fx-rate / {} txid",
        usdReceived,
        utxo.getValue(),
        timestamp,
        USDperBTC,
        utxo.getParentTransaction().getHashAsString());

    processedUTXOs.add(utxo);
    totalRaised = totalRaised.add(usdReceived);
  }

}
