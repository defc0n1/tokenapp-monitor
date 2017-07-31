package modum.io.monitor;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.utils.Convert;
import org.web3j.utils.Convert.Unit;

public class ExchangeRateService {
  private final static Logger LOG = LoggerFactory.getLogger(ExchangeRateService.class);

  private final static BigDecimal USD_PER_ETHER = new BigDecimal("201.1337");
  private final static BigDecimal USD_PER_BTC = new BigDecimal("2549.1337");

  public static BigDecimal getUSDperEther(Long timestamp) {
    return USD_PER_ETHER;
  }

  public static BigDecimal getUSDPerBTC(Long timestamp) {
    return USD_PER_BTC;
  }

  public static BigDecimal weiToUSD(BigInteger weiAmount, Long timestamp) {
    BigDecimal wei = new BigDecimal(weiAmount);
    BigDecimal ethers = Convert.fromWei(wei, Unit.ETHER);
    return ethers.multiply(getUSDperEther(timestamp));
  }
}
