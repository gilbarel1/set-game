package bguspl.set.ex;
import java.util.concurrent.CountDownLatch;

public interface DealerObserver {
    void onEventHappened(int playerID, CountDownLatch latch) throws InterruptedException;
}
