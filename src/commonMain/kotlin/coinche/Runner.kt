package coinche


enum class Update {
    NEW_GAME,
    NEW_BIDDING,
    NEW_BIDDING_STEP,
    END_BIDDING,
    NEW_ROUND,
    NEW_TRICK,
    ADVANCE_TRICK,
    END_TRICK,
    END_ROUND,
    END_GAME
}

fun GameState.shouldPlay(): Boolean =
    (first == Update.NEW_TRICK || first == Update.ADVANCE_TRICK) && second.currentTrick.isNotDone()

fun GameState.shouldBid(): Boolean =
    (first == Update.NEW_BIDDING_STEP || first == Update.NEW_BIDDING) &&
        shouldContinueBidding(second.currentRound.biddingSteps.map { it.second })

fun updateGameAfterTrickIsDone(
    game: Game
): Game {
    val winningCard = findWinningCard(game.currentTrick, game.currentRound.bid.suit)
    val winnerPosition = game.currentTrick.startingPosition + game.currentTrick.cards.indexOf(winningCard)
    val isLastTrick = game.currentTrick.players.areEmptyHanded()

    val trickPoints =
        computeTrickPoints(game.currentTrick, game.currentRound.bid.suit, winnerPosition, isLastTrick)

    val updatedRound = game.currentRound
        .updatePlayers(game.currentTrick.players)
        .updateStartingPosition(winnerPosition)
        .addPoints(trickPoints)
    return game.updateCurrentRound(updatedRound)
}

fun computeRoundScore(round: Round): Score {
    // Missing : other scoring rules (like point done + contract, steal belote)
    require(round.isDone())
    val bid = round.bid
    val belotePoints = round.belotePosition?.let {
        when (it) {
            Position.NORTH, Position.SOUTH -> 20 to 0
            Position.EAST, Position.WEST -> 0 to 20
        }
    } ?: 0 to 0
    val points = round.currentPoints + belotePoints
    val attackerPoints = when (bid.position) {
        Position.NORTH, Position.SOUTH -> points.first
        Position.EAST, Position.WEST -> points.second
    }
    val bidSuccess = when (bid) {
        is Bid.Contract -> attackerPoints >= bid.contract
        is Bid.Capot -> round.findWinners().all { it == bid.position || it == bid.position + 2 }
        is Bid.Generale -> round.findWinners().all { it == bid.position }
    }
    val bidValue = when (bid) {
        is Bid.Contract -> bid.contract
        is Bid.Capot -> 250
        is Bid.Generale -> 500
    }
    val baseAttackerScore = if (bidSuccess) bidValue else 0
    val attackerScore = baseAttackerScore * when (bid.coincheStatus) {
        CoincheStatus.NONE -> 1
        CoincheStatus.COINCHE -> 2
        CoincheStatus.SURCOINCHE -> 4
    }
    val defenderScore = if (bidSuccess) 0 else when (bid.coincheStatus) {
        CoincheStatus.NONE -> 160
        CoincheStatus.COINCHE -> 2 * bidValue
        CoincheStatus.SURCOINCHE -> 4 * bidValue
    }
    val contractScore = when (bid.position) {
        Position.NORTH, Position.SOUTH -> attackerScore to defenderScore
        Position.EAST, Position.WEST -> defenderScore to attackerScore
    }
    return contractScore + belotePoints
}

fun computeTrickPoints(
    trick: Trick,
    trumpSuit: Suit,
    winnerPosition: Position,
    isLastTrick: Boolean
): Score {
    require(trick.isDone())
    val points = trick.cards.map { if (it.suit == trumpSuit) it.rank.trumpValue else it.rank.value }.sum()
    val total = points + if (isLastTrick) 10 else 0
    return when (winnerPosition) {
        Position.NORTH, Position.SOUTH -> total to 0
        Position.EAST, Position.WEST -> 0 to total
    }
}

fun findWinningCard(trick: Trick, trumpSuit: Suit): Card {
    require(trick.isDone())

    val trumpCards = trick.cards.filter { it.suit == trumpSuit }.sortedByDescending { it.rank.trumpValue }
    if (trumpCards.isNotEmpty()) return trumpCards.first()

    val playedSuit = trick.cards.first().suit
    return trick.cards.filter { it.suit == playedSuit }.sortedByDescending { it.rank.value }.first()
}

internal fun shouldContinueBidding(steps: List<BiddingStep>) =
    steps.size < 4 || steps.takeLast(3).any { it != Pass }

fun advanceTrick(trick: Trick, trumpSuit: Suit, card: Card): Trick {
    if (trick.isDone()) return trick
    val currentPlayer = trick.currentPlayer!! // Contract
    val currentPosition = trick.startingPosition + trick.cards.size
    val validCards = playableCards(trick, trumpSuit)
    require(validCards.contains(card)) { "Invalid move playing $card. Valid cards are $validCards." }
    val newHand = currentPlayer.hand - card
    val newPlayers = trick.players.mapValues {
        when (it.key) {
            currentPosition -> currentPlayer.updateHand(newHand)
            else -> it.value
        }
    }
    return trick.addCard(card).updatePlayers(newPlayers)
}

internal fun drawCards(): Map<Position, Player> {
    val shuffled = allCards.shuffled()
    return Position.values().associate { position ->
        val lowerBound = 8 * position.ordinal
        val higherBound = 8 * (position.ordinal + 1)
        val playerHand = shuffled.subList(lowerBound, higherBound).toSet()
        position to Player(playerHand)
    }
}

fun partnerIsWinningTrick(trick: Trick, trumpSuit: Suit): Boolean {
    val partnerPosition = trick.currentPosition - 2
    val partnerCard = trick.cards.getOrNull(partnerPosition.ordinal) ?: return false
    return trick.cards.all {
        partnerCard.isBetterThan(it, trumpSuit) &&
                (partnerCard.suit == trumpSuit || partnerCard.suit == trick.cards.first().suit)
    }
}
