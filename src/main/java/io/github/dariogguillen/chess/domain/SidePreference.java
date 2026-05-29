package io.github.dariogguillen.chess.domain;

/**
 * The room creator's <em>intent</em> for which side they want to play, expressed at room-create
 * time. Distinct from {@link Side}, which models a concrete piece owner / side-to-move: a real game
 * is only ever {@link Side#WHITE} or {@link Side#BLACK}, never "random".
 *
 * <p>{@link #RANDOM} is resolved to a concrete {@link Side} server-side at create time (a coin flip
 * the client cannot bias), so the domain never persists {@code RANDOM}; only the resolved {@link
 * Side} is stored on the {@code Room}. Keeping the request-level intent in its own enum prevents
 * {@code RANDOM} from leaking into the rest of the system where a concrete side is required.
 */
public enum SidePreference {
  WHITE,
  BLACK,
  RANDOM
}
