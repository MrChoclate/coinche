package presentation

import coinche.*
import coinche.Update.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

typealias Event = Either<BiddingStep, Card>

class GamePresenter: Presenter<GameState, Event> {
    private val _models = ConflatedBroadcastChannel<GameState>()
    override val models: ReceiveChannel<GameState> get() = _models.openSubscription()

    private val _events = Channel<Event>()
    override val events: SendChannel<Event> get() = _events

    override suspend fun start() = coroutineScope {
        var game = Game(firstToPlay = Position.NORTH)
        fun sendGame(update: Update, newGame: Game) {
            game = newGame
            _models.offer(update to newGame)
        }
        fun newRound() {
            val round = Round(emptyList(), drawCards(), game.firstToPlay, emptyList(), belotePosition = null)
            sendGame(NEW_ROUND, game.addRound(round))
            sendGame(NEW_BIDDING, game.addRound(round))
        }
        fun handleCard(card: Card) {
            sendGame(ADVANCE_TRICK, game.playCard(card))
            if (game.currentTrick.isDone()) {
                sendGame(END_TRICK, updateGameAfterTrickIsDone(game))
                if (game.currentRound.isNotDone()) {
                    val trick = Trick(emptyList(), game.currentRound.players, game.currentRound.startingPosition)
                    sendGame(NEW_TRICK, game.updateCurrentRound(game.currentRound.addTrick(trick)))
                } else {
                    val roundPoints = game.currentRound.currentPoints
                    require(roundPoints.first + roundPoints.second == 162)
                    val roundScore = computeRoundScore(game.currentRound)
                    sendGame(END_ROUND, game.addScore(roundScore).changeDealer())

                    if (game.isNotDone()) {
                        newRound()
                    } else {
                        sendGame(END_GAME, game)
                    }
                }
            }
        }
        fun handleBiddingStep(biddingStep: BiddingStep) {
            sendGame(NEW_BIDDING_STEP, game.doBidding(biddingStep))
            if (game.currentRound.biddingIsOver()) {
                sendGame(END_BIDDING, game)
                if (game.currentRound.isDone()) {
                    sendGame(END_ROUND, game)
                } else {
                    val trick = Trick(emptyList(), game.currentRound.players, game.currentRound.startingPosition)
                    sendGame(NEW_TRICK, game.updateCurrentRound(game.currentRound.addTrick(trick)))
                }
            }
        }

        sendGame(NEW_GAME, game)
        newRound()

        launch {
            for(event in _events) {
                when(event) {
                    is Left -> handleBiddingStep(event.value)
                    is Right -> handleCard(event.value)
                }
            }
        }

        return@coroutineScope
    }
}