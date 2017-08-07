package modum.io.monitor;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

/***
 * Class that watches the (postgres) database for NOTIFYs of 'ether' and 'bitcoin' and triggers
 * the passed-in actions.
 */
public class DatabaseWatcher {
  private PostgresTriggerListener listener;
  private final DataSource dataSource;

  DatabaseWatcher(DataSource dataSource, TriggerAction newBitcoinAddress,
      TriggerAction newEtherAddress, boolean createSchema) throws SQLException {
    this.dataSource = dataSource;
    if (createSchema) setUpTrigger();

    Map<String, TriggerAction> actionMap = new HashMap<>();
    actionMap.put("bitcoin", newBitcoinAddress);
    actionMap.put("ether", newEtherAddress);
    listener = new PostgresTriggerListener(dataSource.getConnection(), actionMap);
    listener.start();
  }

  void stop() {
    listener.gracefulStop();
  }

  private void setUpTrigger() throws SQLException {
    try (
        Connection conn = dataSource.getConnection();
        Statement statement = conn.createStatement();
    ) {
      statement.execute(""
          + "CREATE OR REPLACE FUNCTION notify_new_payin_address()\n"
          + "RETURNS TRIGGER AS $$\n"
          + "BEGIN\n"
          + "  PERFORM pg_notify(CAST('bitcoin' AS TEXT),NEW.pay_in_bitcoin_public_key);\n"
          + "  PERFORM pg_notify(CAST('ether' AS TEXT),NEW.pay_in_ether_public_key);\n"
          + "  RETURN NEW;\n"
          + "END;\n"
          + "$$ LANGUAGE 'plpgsql';");
      statement.execute(
          "DROP TRIGGER IF EXISTS notify_new_payin_address ON investor;\n"
              + "CREATE TRIGGER notify_new_payin_address\n"
              + "  AFTER UPDATE OF pay_in_ether_public_key, pay_in_bitcoin_public_key ON investor\n"
              + "  FOR EACH ROW\n"
              + "  EXECUTE PROCEDURE notify_new_payin_address()");
    }
  }

}

