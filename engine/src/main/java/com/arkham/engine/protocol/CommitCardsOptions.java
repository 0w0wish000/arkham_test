package com.arkham.engine.protocol;

import com.arkham.engine.model.HandCard;
import com.arkham.engine.model.SkillType;

import java.util.List;

/**
 * Options for a COMMIT_CARDS choice (docs/05 §2.1). Mirrors {@code CommitCardsOptions}
 * in protocol/messages.ts. Sent once to every eligible committer during the commit
 * barrier: {@code maxCommit} is large for the performer and 1 for same-location allies.
 *
 * @param skill         the skill being tested
 * @param base          the performer's base skill value (for the client's live readout)
 * @param difficulty    the difficulty to beat
 * @param eligibleCards this committer's hand cards with a matching/wild icon
 * @param maxCommit     max cards this committer may commit (performer: large; ally: 1)
 */
public record CommitCardsOptions(
        SkillType skill,
        int base,
        int difficulty,
        List<HandCard> eligibleCards,
        int maxCommit) implements ChoiceOptions {}
