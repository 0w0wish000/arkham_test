package com.arkham.engine.protocol;

import com.arkham.engine.model.HandCard;
import com.arkham.engine.model.SkillType;

import java.util.List;

/**
 * Options for a COMMIT_CARDS choice (docs/05 §2.1). Mirrors {@code CommitCardsOptions}
 * in protocol/messages.ts. Sent once to every eligible committer during the commit
 * barrier — the performer and every investigator at their location, each of whom may
 * commit any number of matching cards.
 *
 * @param skill         the skill being tested
 * @param base          the performer's base skill value (for the client's live readout)
 * @param difficulty    the difficulty to beat
 * @param eligibleCards this committer's hand cards with a matching/wild icon
 * @param maxCommit     how many of them may be committed (i.e. all of them)
 */
public record CommitCardsOptions(
        SkillType skill,
        int base,
        int difficulty,
        List<HandCard> eligibleCards,
        int maxCommit) implements ChoiceOptions {}
