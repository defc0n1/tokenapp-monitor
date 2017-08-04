package modum.io.monitor;

/**
 * Interface for the DatabaseWatcher. run(payload) is called for every NOTIFY.
 */
public interface TriggerAction {
  void run(String payload);
}
