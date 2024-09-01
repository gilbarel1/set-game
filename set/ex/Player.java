package bguspl.set.ex;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import bguspl.set.Env;

import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated, initialized false as default.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The current slot player chose.
     */
    private int slot;

    /*
     * The dealer observer object for callbacks.
     */
    private DealerObserver dealerObserver;

    /*
     * true if point, false if penalty
     */
    private volatile boolean pointOrPaneltyFlag;

    private volatile boolean freezeFlag = false;
 
    /*
     * The queue of key presses.
     */
    private BlockingQueue<Integer> queue = null;

    /**
     * The timer diplay between changes (in milliseconds).
     */
    private final int displayTimeMillis = 1000;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        terminate = false;
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        score = 0;
        dealerObserver = dealer;
        queue = new ArrayBlockingQueue<Integer>(env.config.featureSize);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            try {
                slot = queue.take();
                act();
            } catch (InterruptedException ignored) {}
        }
        if (!human) {
            while (aiThread.isAlive()) { //for the case iterrupt while waiting to the AI thread to finish 
                try {
                    aiThread.join();
                } catch (InterruptedException ignored) {}
            }
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        int logID = id+1;
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                Random random = new Random();
                int randomSlot = random.nextInt(env.config.tableSize);
                AiKeyPressed(randomSlot);
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + logID);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     * Called by the dealer thread. 
     */
    public void terminate() {
        terminate = true;
        if (!human) aiThread.interrupt();
        playerThread.interrupt();
        try {
            playerThread.join();
        } catch (InterruptedException ignored) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot){
        if (human) {
                queue.offer(slot);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        freezeFlag = true;
        pointOrPaneltyFlag = true;

        //int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        freezeFlag = true;
        pointOrPaneltyFlag = false;
    }

    public int score() {
        return score;
    }


    ///////////////////////
    // **new functions** //
    ///////////////////////

    /**
     * This method is called when player has keypressed to act
     * @throws InterruptedException
     */
    private void act() throws InterruptedException{
        int tokens = table.placeOrRemoveToken(id, slot);
        if (tokens == env.config.featureSize) {
            try {
                CountDownLatch latch = new CountDownLatch(1);
                dealerObserver.onEventHappened(id, latch);
                latch.await();
                freeze();
                queue.clear();
            }catch (InterruptedException e) {
                throw e;
            }
        }
    }

    /**
     * This method is called after player being checked by the dealer.
     * @throws InterruptedException
     */
    private void freeze() throws InterruptedException {
        if (freezeFlag){
            long wait = pointOrPaneltyFlag ? env.config.pointFreezeMillis : env.config.penaltyFreezeMillis;
            while (wait >= displayTimeMillis) {
                try {
                    env.ui.setFreeze(id, wait);
                    Thread.sleep(displayTimeMillis);
                } catch (InterruptedException e) {
                    throw e;
                }
                wait = wait - displayTimeMillis;
            }
            if (wait > 0 & wait <= displayTimeMillis) {
                try {
                    env.ui.setFreeze(id, wait);
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    throw e;
                }
                wait = 0;
            }
            freezeFlag = false;
            env.ui.setFreeze(id, wait);
        }
    }


    /**
     * This method is called when a key is pressed by the AI.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    private void AiKeyPressed(int slot){
        try {
            queue.put(slot);
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }
}
