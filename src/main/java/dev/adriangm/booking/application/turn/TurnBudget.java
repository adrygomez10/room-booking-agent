package dev.adriangm.booking.application.turn;

import dev.adriangm.booking.application.TurnFailure;

/** Counts attempted model and tool calls for one sequential reservation turn. */
public final class TurnBudget {
  private final int maxModelCalls;
  private final int maxToolCalls;
  private int modelCalls;
  private int toolCalls;

  /** Sets positive limits for model attempts and tool executions. */
  public TurnBudget(int maxModelCalls, int maxToolCalls) {
    if (maxModelCalls < 1 || maxToolCalls < 1) {
      throw new IllegalArgumentException("Budgets must be positive.");
    }

    this.maxModelCalls = maxModelCalls;
    this.maxToolCalls = maxToolCalls;
  }

  /** Consumes one model attempt or rejects the call when its limit is reached. */
  public void beforeModelCall() {
    if (modelCalls >= maxModelCalls) {
      throw new TurnFailure("BUDGET_EXHAUSTED");
    }

    modelCalls++;
  }

  /** Consumes one tool attempt or rejects the operation when its limit is reached. */
  public void beforeToolCall() {
    if (toolCalls >= maxToolCalls) {
      throw new TurnFailure("BUDGET_EXHAUSTED");
    }

    toolCalls++;
  }

  /** Returns model call attempts consumed by this turn. */
  public int modelCalls() {
    return modelCalls;
  }

  /** Returns tool attempts consumed by this turn. */
  public int toolCalls() {
    return toolCalls;
  }
}
