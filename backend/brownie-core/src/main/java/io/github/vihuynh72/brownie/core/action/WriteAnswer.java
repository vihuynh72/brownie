package io.github.vihuynh72.brownie.core.action;

import java.util.List;

/**
 * What the provider's answer to a change means, sorted by what it proves.
 * A write is not a read: a timeout or a server error after the request left
 * says nothing about whether the change happened, so it is {@link Unknown},
 * never "try again later".
 */
public sealed interface WriteAnswer {

    Integer status();

    List<String> reasons();

    /** The provider says it made the change; {@code externalId} names it when the provider does. */
    record Applied(Integer status, List<String> reasons, String externalId, String link, String resultRevision) implements WriteAnswer {
        public Applied {
            reasons = List.copyOf(reasons);
        }
    }

    /** The provider refused to make it again because it already exists: an earlier attempt made it. */
    record Exists(Integer status, List<String> reasons) implements WriteAnswer {
        public Exists {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * The change certainly did not happen and may be sent again: nothing
     * reached the provider, or it refused before processing (a refused
     * token, a rate limit). {@code tokenRefused} when the provider refused
     * the access token, which means the person took Brownie's access away.
     */
    record NotAppliedRetryable(Integer status, List<String> reasons, boolean tokenRefused) implements WriteAnswer {
        public NotAppliedRetryable {
            reasons = List.copyOf(reasons);
        }
    }

    /** The change certainly did not happen and sending it again would be refused the same way. */
    record NotAppliedFinal(Integer status, List<String> reasons, ActionFailure failure) implements WriteAnswer {
        public NotAppliedFinal {
            reasons = List.copyOf(reasons);
        }
    }

    /** The change may or may not have happened. */
    record Unknown(Integer status, List<String> reasons) implements WriteAnswer {
        public Unknown {
            reasons = List.copyOf(reasons);
        }
    }
}
