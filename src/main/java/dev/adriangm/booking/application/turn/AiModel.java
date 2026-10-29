package dev.adriangm.booking.application.turn;

import dev.adriangm.booking.domain.TimeSlot;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Exchanges provider-neutral messages and tool observations with a language model. */
public interface AiModel {
    /** Requests the next model decision using only the supplied public task context. */
    Reply respond(Input input);

    /** Describes a tool name, purpose and permitted argument schema. */
    record Definition(String name, String description, Map<String, Object> parameters) {
    }

    /** Identifies one tool operation requested by the model and its proposed arguments. */
    record Call(String id, String name, Map<String, Object> arguments) {
        public Call
        {
            arguments = Map.copyOf(arguments);
        }
    }

    // continuationData is an opaque provider response. Only its adapter interprets it.
    /** Carries model output and opaque continuation data interpreted only by its adapter. */
    record Reply(List<Call> calls, String text, String continuationData) {
        public Reply
        {
            calls = List.copyOf(calls);
        }
    }

    /** Associates the observed result of a tool with its original request. */
    record Result(String toolCallId, String name, Map<String, Object> result) {
        public Result
        {
            result = Map.copyOf(result);
        }
    }

    /** Preserves a model reply together with the application result returned to it. */
    record Exchange(Reply reply, Result result) {
    }

    /** Supplies task data, permitted tools and history without exposing authenticated identity. */
    record Input(
        String message,
        TimeSlot slot,
        int attendeeCount,
        List<Definition> tools,
        List<Exchange> history,
        Duration timeout
    ) {
        public Input
        {
            tools = List.copyOf(tools);
            history = List.copyOf(history);
        }
    }
}
