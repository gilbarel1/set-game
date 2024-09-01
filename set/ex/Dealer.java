package bguspl.set.ex;

import bguspl.set.Env;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable, DealerObserver {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * True iff the dealer should create and start the players threads once.
     */
    private boolean createPlayerThreads;

    /**
     * The queue of player tasks to be executed by the dealer.
     */
    private BlockingQueue<PlayerTask> queue = new LinkedBlockingQueue<PlayerTask>();

    /**
     * The immediate task to be executed by the dealer.
     */
    PlayerTask immediateTask = null;

    /**
     * The max time to sleep between update the clock when < warningTime .
     */
    private final long warningTimeWake = 100;

    /**
     * The timer diplay between changes (in milliseconds).
     */
    private final long displayTimeMillis = 1000;

    private class PlayerTask {
        int playerID;
        CountDownLatch latch;

        PlayerTask(int playerID, CountDownLatch latch) {
            this.playerID = playerID;
            this.latch = latch;
        }
    }


    public Dealer(Env env, Table table, Player[] players) {
        terminate = false;
        createPlayerThreads = true;
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        //dealer program loop
        while (!shouldFinish()) {
            Collections.shuffle(deck);
            placeCardsOnTable();
            updateTimerDisplay(true);//reset the timer before start
            startPlayerThreads();// create and start players threads once.
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        if (!terminate) {
            terminate();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated, by X button or by the game end conditions.
     */
    public void terminate() {
        for (int player = players.length - 1; player >= 0; player--) {
            players[player].terminate();
        }
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if (immediateTask != null) {
            Player player = players[immediateTask.playerID];
            List<Integer> set = table.getPlayerSet(immediateTask.playerID);
            if (set != null) {
                int[] setArray = set.stream().mapToInt(i->i).toArray();
                if (!env.util.testSet(setArray)) {
                    player.penalty();
                } else {
                    table.removeSet(set);
                    updateTimerDisplay(true);
                    player.point();
                }
            }
            immediateTask.latch.countDown();
            immediateTask = null;
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        List<Integer> cards = new LinkedList<Integer>();
        if (deck.size()>0){
            int cardsMiss = env.config.tableSize - table.countCards();
            if (cardsMiss > 0) {
                for (int i = 0; i<cardsMiss & deck.size()>0; i++) cards.add(deck.remove(0));
            }
        }
        table.placeCards(cards, getShuffeledSlots());
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try {
            immediateTask = queue.poll(getSleepTime(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     * -1 the new time is to ensure that the display will be updated also when thread is quiq.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }
        long newTimeMillies = getTimeLeft();
        env.ui.setCountdown(newTimeMillies-1, newTimeMillies <= env.config.turnTimeoutWarningMillis);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        List<Integer> removedCards = table.removeAllCards(getShuffeledSlots());
        for (int card : removedCards) deck.add(card);
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int bestScore = -1;
        int numOfWinners = 0;
        for (Player player : players){
            int score = player.score();
            if (bestScore < score){
                bestScore = score;
                numOfWinners = 1;
            } else if(bestScore == score){
                numOfWinners++;
            }
        }
        int[] winners = new int[numOfWinners];
        for (Player player : players){
            if (player.score() == bestScore){
                winners[numOfWinners-1] = player.id;
                numOfWinners--;
            }
        } 
        env.ui.announceWinner(winners);
    }


    ///////////////////////
    // **new functions** //
    ///////////////////////

    /*
     * create and start the players threads
     */
    private void startPlayerThreads() {
        if (createPlayerThreads){
            for (Player player : players) {
                int logID = player.id +1;
                Thread playerThread = new Thread(player, "Player: " + logID);
                playerThread.start();
                env.logger.info("thread " + playerThread.getName() + " created.");
            }
            createPlayerThreads = false;
        }
    }

    /*
     * called by the player to notify the dealer that a set added to be checked
     */
    @Override
    public void onEventHappened(int playerID, CountDownLatch latch) throws InterruptedException {
            queue.put(new PlayerTask(playerID, latch));
    }

    /*
     * returne the time left for the next reshuffle, always positiv
     */
    private long getTimeLeft(){
        long current = reshuffleTime - System.currentTimeMillis();
        if (current <= 0) return 1;
        return current;
    }

    /*
     * returne the time to sleep
     */
    private long getSleepTime(){
        if (getTimeLeft() < env.config.turnTimeoutWarningMillis) 
            return warningTimeWake;
        return getTimeLeft()%displayTimeMillis;
    }

    /*
     * returne a list of shuffeled slots
     */
    private List<Integer> getShuffeledSlots(){
        List<Integer> slots = new ArrayList<Integer>();
        for (int slot = 0; slot < env.config.tableSize; slot++) slots.add(slot);
        Collections.shuffle(slots);
        return slots;
    }
}
