package modum.io.monitor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses posgres pub-sub functionality to listen for updates to the pay-in address fiels
 * in the investor database.
 */
public class PostgresTriggerListener extends Thread {
  private final static Logger LOG = LoggerFactory.getLogger(PostgresTriggerListener.class);

  private volatile boolean stop = false;
  private org.postgresql.PGConnection pgconn;
  private Map<String, TriggerAction> actions;

  PostgresTriggerListener(Connection conn, Map<String, TriggerAction> actions) throws SQLException
  {
    if (actions == null || actions.size() == 0)
      throw new RuntimeException("Need at least one channel to listen to");

    this.actions = actions;
    this.pgconn = conn.unwrap(org.postgresql.PGConnection.class);

    // Init listener
    Statement stmt = conn.createStatement();
    actions.keySet().forEach(channel -> {
      try {
        stmt.execute("LISTEN " + channel);
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    });
    stmt.close();
  }

  public void run() {
    try {
      while (!stop) {
        PGNotification notifications[] = pgconn.getNotifications(1000);
        if (notifications != null) {
          for (PGNotification notification : notifications) {
            TriggerAction action = actions.get(notification.getName());
            if (action != null) {
              try {
                action.run(notification.getParameter());
              } catch (Throwable e) {
                LOG.error("Error during trigger: {}", e.getMessage());
              }
            } else {
              LOG.error("Warning: no modum.io.monitor.TriggerAction provided for {}",
                  notification.getName());
            }
          }
        }
      }
      LOG.info("Trigger listener stopped");
    }
    catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  public void gracefulStop() {
    stop = true;
    LOG.info("Graceful stopping trigger listener...");
  }

}
