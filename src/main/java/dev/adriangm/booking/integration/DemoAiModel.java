package dev.adriangm.booking.integration;

import dev.adriangm.booking.application.turn.AiModel;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Explicit deterministic stand-in for an LLM; tools and storage remain real. */
public final class DemoAiModel implements AiModel {
    @Override
    public Reply respond(Input input)
    {
        String message = Normalizer
            .normalize(input.message().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");

        if (input.history().isEmpty()) {
            return new Reply(
                List
                    .of(
                        new Call(
                            "call-1",
                            "find_rooms",
                            Map.of("needsProjector", message.contains("proyector") || message.contains("projector"))
                        )
                    ),
                "",
                ""
            );
        }

        boolean canReserve = input.tools().stream().anyMatch(tool -> tool.name().equals("reserve_room"));
        boolean askedToReserve = message.contains(" y reserv") || message.contains("and reserve")
            || message.contains("and book");
        var observation = input.history().get(input.history().size() - 1).result();

        if (
            canReserve && askedToReserve && observation.name().equals("find_rooms")
                && observation.result().get("rooms") instanceof List<?> rooms && !rooms.isEmpty()
                && rooms.get(0) instanceof Map<?, ?> first && first.get("id") instanceof String id
        ) {
            return new Reply(List.of(new Call("call-2", "reserve_room", Map.of("roomId", id))), "", "");
        }

        return new Reply(List.of(), "No further action.", "");
    }
}
