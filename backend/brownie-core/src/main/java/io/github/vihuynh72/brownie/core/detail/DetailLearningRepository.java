package io.github.vihuynh72.brownie.core.detail;

import java.util.Optional;

/** The record of what each learn run wrote, owner-scoped like {@code saved_detail}. */
public interface DetailLearningRepository {

    /** Records a run's writes and answers with its id. */
    long record(long ownerUserId, long documentId, java.util.List<DetailLearning.WrittenKey> writes);

    /** Finds one run the owner made. */
    Optional<DetailLearning> find(long ownerUserId, long learningId);
}
