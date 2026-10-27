# Room Booking Agent

Ejemplo ejecutable de [Herramientas y agentes: cómo una aplicación coordina un turno](https://adriangm.dev/categoria/inteligencia-artificial/integracion-ia-software/herramientas-y-agentes-coordinar-un-turno).

El modelo solicita buscar y reservar una sala. La aplicación valida los argumentos, consulta datos reales y solo muestra una confirmación después de guardar la reserva. La pantalla permite seguir los intercambios y repetir una petición sin duplicar su efecto.

## Ejecutar con Docker

```sh
cp .env.example .env
# Cambia DEMO_PASSWORD en .env (al menos ocho caracteres).
docker compose up --build
```

Abre http://localhost:8080 e introduce esa contraseña. En PowerShell, copia el archivo con `Copy-Item .env.example .env`. El servicio se expone únicamente en la interfaz local del equipo; la base H2 se conserva en el volumen `bookings`.

El modo predeterminado es `demo`: **no utiliza un LLM**. `DemoAiModel` simula dos decisiones con reglas explícitas para el mensaje del formulario («Busca una sala con proyector y resérvala»). Las herramientas, las consultas SQL y las reservas sí son reales. Para probar otros significados del lenguaje, utiliza el modo Gemini.

Con seis asistentes y proyector, la búsqueda inicial devuelve Norte, con ocho plazas. Sur tiene cuatro y queda fuera. Tras reservar Norte, repetir la misma petición devuelve su identificador guardado, con cero llamadas nuevas al modelo.

## Utilizar Gemini

En `.env`, configura `MODEL_MODE=gemini`, `GEMINI_API_KEY` y `GEMINI_MODEL` con un modelo disponible en tu proyecto de Google AI. Después recrea el servicio con `docker compose up --build`. La clave permanece en el servidor y no se incluye en las trazas de la pantalla.

El adaptador usa la API REST `generateContent`, conserva el contenido completo devuelto por el proveedor al continuar el turno (incluidas las firmas de pensamiento) y aplica tiempos máximos de espera. Las pruebas utilizan un servidor HTTP local, sin llamadas de pago ni credenciales reales. Referencias: [function calling](https://ai.google.dev/gemini-api/docs/function-calling) y [thought signatures](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures).

## Ejecutar sin Docker y comprobar

Requisitos: JDK 25 y Maven 3.9.

```sh
mvn verify
DEMO_PASSWORD=local-example-password java -jar target/room-booking-agent-1.0.0.jar
```

En PowerShell, define antes `$env:DEMO_PASSWORD='local-example-password'` y ejecuta `java -jar target/room-booking-agent-1.0.0.jar`. Esta forma usa `127.0.0.1:8080` y guarda la base en `data/`, excluida de Git. El programa lee variables de entorno; `.env` lo carga Docker Compose, no el JAR.

`mvn verify` ejecuta pruebas de dominio, concurrencia, persistencia, herramientas, HTTP y contrato del proveedor, además de ArchUnit, el formato y Checkstyle con las reglas Google para fuentes y pruebas. `mvn spotless:apply` aplica el formato Java.

## Qué contiene cada fichero

Las fuentes están en [`src/main/java/dev/adriangm/booking`](src/main/java/dev/adriangm/booking). Lee primero el coordinador y sigue sus llamadas.

La aplicación se divide por responsabilidad:

```text
application/
├── booking/         Búsqueda, reserva, contexto y contrato de almacenamiento
├── turn/            Coordinación, herramientas, presupuesto y contrato del modelo
└── TurnFailure.java Error técnico compartido por el turno y los adaptadores
```

`turn` utiliza `booking`; los casos de reserva no conocen el modelo de IA ni la coordinación del turno. `BookingRepository` pertenece a `booking` porque define el almacenamiento que necesitan esos casos de uso. `AiModel` pertenece a `turn` porque define el intercambio con el modelo. Son contratos hacia adaptadores, no servicios ejecutables por separado. ArchUnit comprueba esa dirección y la ausencia de ciclos entre los paquetes.

El paquete `domain` conserva un conjunto pequeño y cohesionado: `Room` representa una sala, `Reservation` una reserva, `TimeSlot` su intervalo y `BookingFailure` los rechazos de sus reglas. Sus funciones son distintas, pero pertenecen al mismo negocio de reservas.

| Pieza | Datos y responsabilidad |
| --- | --- |
| [`Main`](src/main/java/dev/adriangm/booking/Main.java) | Lee la configuración y conecta los adaptadores con los casos de uso. |
| [`ReservationController`](src/main/java/dev/adriangm/booking/web/ReservationController.java) y [`SessionStore`](src/main/java/dev/adriangm/booking/web/SessionStore.java) | Reciben HTTP, autentican la cuenta demo y conservan las peticiones recientes de cada sesión. La identidad nunca se acepta del cuerpo JSON. |
| [`ReservationContext`](src/main/java/dev/adriangm/booking/application/booking/ReservationContext.java) | Transporta identidad, franja, asistentes, clave de petición y permiso para reservar. |
| [`ReservationTurnCoordinator`](src/main/java/dev/adriangm/booking/application/turn/ReservationTurnCoordinator.java) | Conserva historial y límites; solicita el siguiente paso al modelo y encarga su ejecución. |
| [`AiModel`](src/main/java/dev/adriangm/booking/application/turn/AiModel.java) | Define las entradas, respuestas e intercambios técnicos con el modelo. No contiene reglas del negocio. |
| [`RoomTools`](src/main/java/dev/adriangm/booking/application/turn/RoomTools.java) | Describe las dos herramientas, valida sus argumentos y las conecta con los casos de uso. |
| [`FindAvailableRooms`](src/main/java/dev/adriangm/booking/application/booking/FindAvailableRooms.java) y [`ReserveRoom`](src/main/java/dev/adriangm/booking/application/booking/ReserveRoom.java) | Buscan y confirman sobre datos reales. Las decisiones de la reserva se toman dentro de la transacción. |
| [`Room`, `Reservation`, `TimeSlot`](src/main/java/dev/adriangm/booking/domain) | Encapsulan aforo, identidad, duración y solapamiento de franjas. |
| [`BookingRepository`](src/main/java/dev/adriangm/booking/application/booking/BookingRepository.java) y [`SqlBookingRepository`](src/main/java/dev/adriangm/booking/persistence/SqlBookingRepository.java) | Separan el contrato de almacenamiento de JDBC/H2. Guardan la reserva y su clave de repetición de forma atómica. |
| [`TurnBudget`](src/main/java/dev/adriangm/booking/application/turn/TurnBudget.java) | Cuenta los intentos del turno: por defecto, tres llamadas al modelo y cuatro herramientas. |
| [`AiModelAdapter`](src/main/java/dev/adriangm/booking/integration/AiModelAdapter.java) y [`DemoAiModel`](src/main/java/dev/adriangm/booking/integration/DemoAiModel.java) | Implementan el contrato del modelo con Gemini o con la simulación identificada. |
| [`schema.sql`](src/main/resources/db/schema.sql) y [`web/`](src/main/resources/web) | Definen el almacenamiento inicial y la interfaz HTML/CSS/JavaScript. |

`ReserveRoom.execute` abre una `ReservationTransaction`, consulta su estado, aplica las reglas, guarda y confirma. Al salir sin confirmar, la transacción revierte la escritura. El caso de uso sigue un recorrido secuencial y no entrega una función de decisión al repositorio.

El dominio y la aplicación no dependen de HTTP, SQL, Jackson ni de un SDK. ArchUnit comprueba esas fronteras. El controlador no contiene consultas SQL ni decisiones de reserva.

## Recorrido HTTP y límites de la demostración

1. `POST /api/session` autentica con `password` y crea una cookie HttpOnly.
2. `POST /api/requests` recibe `message`, `startsAt`, `endsAt` (ISO 8601 en UTC), `attendeeCount` y `reservationAllowed`. El servidor asigna `requestId` y conserva esos datos en la sesión.
3. `POST /api/requests/{requestId}/run`, con `{}`, ejecuta el turno. Repetir esta ruta recupera una reserva confirmada para esa petición. Otra sesión no puede ejecutar su identificador.

La reserva solo puede solicitar una sala ofrecida en la búsqueda del turno y vuelve a comprobar su disponibilidad. Las respuestas con varias herramientas, nombres desconocidos o campos adicionales se rechazan. Si no se permite reservar, la herramienta de escritura tampoco se anuncia al modelo. Un texto generado no sirve como confirmación.

H2 conserva salas y reservas. Una fila de bloqueo serializa las escrituras para impedir reservas solapadas y repeticiones concurrentes; es una solución deliberadamente sencilla, con menor concurrencia que un diseño de producción. Las sesiones y las últimas veinte peticiones viven en memoria durante una hora: tras reiniciar hay que iniciar sesión y las solicitudes anteriores dejan de estar disponibles por HTTP, aunque las reservas sigan guardadas.

La interfaz usa un diseño administrativo básico, fuentes del sistema y colores neutros con azul para las acciones. Esta demo local tiene una sola cuenta; no implementa gestión de usuarios, un despliegue público ni MCP. El modo real necesita conectividad y una cuenta de Google AI y puede generar consumo en ese proveedor.
