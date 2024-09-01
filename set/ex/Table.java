package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)


    /**
     * Mapping between a slot and the token state placed in it for each player.
     */
    private boolean[][] slotToToken;

    /**
     * An array of locks for each player.
     */
    private final Object[] playerLocks;

    /**
     * True iff the dealer is active.
     */
    private boolean dealerActive = false;


    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */

    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.slotToToken = new boolean[env.config.tableSize][env.config.players];
        playerLocks = new Object[env.config.players];
        for (int i = 0; i < env.config.players; i++) playerLocks[i] = new Object();
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     * 
     ** used by the dealer 
     ** read from slotToCard
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     * 
     ** used by the dealer
     ** write to slotToCard and cardToSlot
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        
        // Placing the card in UI
        env.ui.placeCard(card, slot); 
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     * 
     ** used by the dealer
     ** write to slotToCard and cardToSlot and slotToToken
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        // Removing the card
        if (slotToCard[slot] != null) {
            int card = slotToCard[slot];
            slotToCard[slot] = null;
            cardToSlot[card] = null;

            // Removing tokens from slot
            for (int i = 0; i < slotToToken[slot].length; i++) {
                slotToToken[slot][i] = false;
            }
            env.ui.removeTokens(slot);
            env.ui.removeCard(slot);
        }
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     * 
     ** used by the player
     ** write to slotToToken
     */
    public void placeToken(int player, int slot) {
            slotToToken[slot][player] = true;
            env.ui.placeToken(player, slot);
    }
    

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     * 
     ** used by the player 
     ** write to slotToToken
     */
    public boolean removeToken(int player, int slot) {
        if(slotToToken[slot][player]) {
            slotToToken[slot][player] = false;
            env.ui.removeToken(player, slot);
            return true;
        }
        return false;
    }

    /**********************
    // **new functions** //
    /**********************

    /**
     * 
     * @param player - the player the token belongs to.
     * @param slot - the slot on which to place the token.
     * @return - the amount of tokens the player has placed in the table after his action, -1 represent plca token isnt allowed.
     * 
     ** used by the player
     ** read and write to slotToToken 
     */

    public int placeOrRemoveToken (int player, int slot) {
        synchronized(playerLocks[player]) {
            while (dealerActive) {
                try {
                    playerLocks[player].wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            int numOfTokens = numOfTokens(player);
            if(getCard(slot) == null || (numOfTokens == env.config.featureSize && !slotToToken[slot][player]))
                return -1;
            else if (slotToToken[slot][player]){
                removeToken(player, slot);
                numOfTokens--;
            }
            else {
                placeToken(player, slot);
                numOfTokens++;
            }
            return numOfTokens;
        }
    }

    /**
     * @param playerID - the player the set belong to.
     * @return - An array of integers representing the card IDs of the set, if it's illegal set size, return null.
     * 
     ** used by the dealer
     ** read from all
     */
    public List<Integer> getPlayerSet(int playerID){
        List<Integer> tokenCards = getTokenCards(playerID);
        if(tokenCards.size() != env.config.featureSize)
            return null;
        else {
            return tokenCards;
        }
    }


    /**
     * The method places an amount of cards on the table
     * @param cards - List representing the cards that will be placed.
     * 
     ** used by the dealer
     ** write to cardToSlot and slotToCard 
     */
    public void placeCards(List<Integer> cards, List<Integer> slots){
        // sync to all players locks if not already active
        if (!dealerActive){
            dealerActive = true;
            for (Object playerLock : playerLocks) synchronized(playerLock) {}
        }
        int cardIndex = 0;
        for (int i = 0; i < slots.size() && cardIndex < cards.size(); i++){  // added extra must condition to avoid out of bound exception
            int slot = slots.get(i);
            if(getCard(slot) == null) {
                placeCard(cards.get(cardIndex), slot);
                cardIndex++;
            }
        }
        
        dealerActive = false;
        for (Object playerLock : playerLocks) { synchronized(playerLock) { playerLock.notify(); } } //release all wait players
        if(env.config.hints & cards.size() > 0) hints();//print hints if set in config
    }

    /**
     * @param set - an array representing a set of the player.
     * @return - 'true' - if the cards were removed accordingly, else return 'false'.
     * 
     ** used by the dealer
     ** write to all
     */
    public boolean removeSet(List<Integer> set){
        // sync to all players locks
        dealerActive = true;
        for (Object playerLock : playerLocks) synchronized(playerLock) {}

        for (Integer card : set) {
            Integer slot = getSlot(card);
            if(slot == null){
                return false;
            }
            removeCard(slot);
        }
        // release all wait players will be done in the placeCards method
        return true;
    }

    /**
     * The method removes all cards from the table.
     * @slots - List of slots that will be removed.
     * @return - A List of the card Ids that were removed.
     * 
     ** used by the dealer
     ** write to all
     */
    public List<Integer> removeAllCards(List<Integer> slots) {
        // sync to all players locks
        dealerActive = true;
        for (Object playerLock : playerLocks) synchronized(playerLock) {}

        List<Integer> cardsDeleted = new ArrayList<Integer>();
        for (Integer slot : slots) {
            Integer card = getCard(slot);
            if(card != null){
                removeCard(slot);
                cardsDeleted.add(card);
            }
        }
        // release all wait players will be done in the placeCards method   
        return cardsDeleted;
    }
    
    /****************
     * Simple getters
     ****************/

    private Integer getCard (int slot){
        return slotToCard[slot];
    }

    private Integer getSlot (int card){
        return cardToSlot[card];
    }

    /**********************
     * More private methods
     **********************/
    
    /**
     * 
     * @param player - the player the tokens belong to.
     * @return - the amount of tokens the player has placed in the table.
     * 
     ** used by the player
     ** read from slotToToken 
     */
    
     private int numOfTokens (int player) {
        int count = 0;
        for (int slot = 0; slot < slotToToken.length; slot++) {
            if(slotToToken[slot][player])
                count++;
        }
        return count;
    }

    /** 
     * @param player - the player who placed the tokens
     * @return - list of card IDs the players placed his token on
     * @inv for each player : 0 <= getTokens(player).size() <= 3
     * 
     ** used by the dealer
     ** read from slotToToken, cardToSlot, slotToCard  
     */
    private List<Integer> getTokenCards (int player) {
        List<Integer> cards = new ArrayList<Integer>();
        for (int slot = 0; slot < slotToToken.length; slot++) {
            if(slotToToken[slot][player])
                cards.add(getCard(slot));
        }
        return cards;
    }
}
