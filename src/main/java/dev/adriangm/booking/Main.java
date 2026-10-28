package dev.adriangm.booking;

import dev.adriangm.booking.application.booking.BookingRepository;
import dev.adriangm.booking.application.booking.FindAvailableRooms;
import dev.adriangm.booking.application.booking.ReserveRoom;
import dev.adriangm.booking.application.turn.AiModel;
import dev.adriangm.booking.application.turn.ReservationTurnCoordinator;
import dev.adriangm.booking.application.turn.RoomTools;
import dev.adriangm.booking.integration.AiModelAdapter;
import dev.adriangm.booking.integration.DemoAiModel;
import dev.adriangm.booking.persistence.SqlBookingRepository;
import dev.adriangm.booking.web.ReservationController;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/** Composes the application ports and adapters and starts the local demonstration server. */
public final class Main {
    /** Loads configuration, selects the model implementation and starts the HTTP server. */
    public static void main(String[] args) throws Exception
    {
        String mode = System.getenv().getOrDefault("MODEL_MODE", "demo");
        String password = System.getenv("DEMO_PASSWORD");
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Set DEMO_PASSWORD to at least eight characters.");
        }

        AiModel model = switch (mode) {
            case "demo" -> new DemoAiModel();
            case "gemini" -> new AiModelAdapter(System.getenv("GEMINI_MODEL"), System.getenv("GEMINI_API_KEY"));
            default -> throw new IllegalArgumentException("MODEL_MODE must be demo or gemini.");
        };

        Files.createDirectories(Path.of("data"));
        Clock clock = Clock.systemUTC();
        BookingRepository repository = new SqlBookingRepository(
            System.getenv().getOrDefault("DB_URL", "jdbc:h2:file:./data/bookings")
        );

        RoomTools tools = new RoomTools(new FindAvailableRooms(repository), new ReserveRoom(repository, clock));
        ReservationController controller = new ReservationController(
            new ReservationTurnCoordinator(model, tools),
            clock,
            password,
            mode
        );

        String bindAddress = System.getenv().getOrDefault("BIND_ADDRESS", "127.0.0.1");
        int port = controller
            .start(new InetSocketAddress(bindAddress, Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"))));

        Runtime.getRuntime().addShutdownHook(new Thread(controller::close));
        System.out.println("Room booking is running on port " + port + " (model mode: " + mode + ").");
    }
}
