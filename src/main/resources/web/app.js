const element = (id) => document.getElementById(id);
let pendingId = null;
let busy = false;

async function api(path, method = "GET", body) {
  const options = { method, credentials: "same-origin" };
  if (body !== undefined) {
    options.headers = { "Content-Type": "application/json" };
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "REQUEST_FAILED");
  return data;
}

function feedback(message) {
  element("feedback").textContent = message;
}
function showSession(session) {
  element("login-panel").hidden = session.authenticated;
  element("request-panel").hidden = !session.authenticated;
  element("logout").hidden = !session.authenticated;
  element("mode").textContent =
    session.modelMode === "gemini"
      ? "Modo Gemini: el proveedor decide las herramientas; la aplicación valida y ejecuta las operaciones."
      : "Modo demo: las decisiones del modelo se simulan con reglas sencillas. Las herramientas, las consultas SQL y las reservas son reales. No interpreta libremente cualquier mensaje.";
}

const messages = {
  CONFIRMED: "Reserva confirmada y guardada en la base de datos.",
  NO_ACTION:
    "El turno ha terminado sin crear una reserva. Consulta la búsqueda en los intercambios.",
  SLOT_UNAVAILABLE:
    "La sala ya no está disponible en esa franja. Prepara una nueva búsqueda.",
  BUDGET_EXHAUSTED:
    "No se ha completado la reserva dentro del límite de intentos.",
  TURN_TIMEOUT: "Se ha agotado el tiempo del turno.",
  MODEL_UNAVAILABLE: "El proveedor de IA no ha respondido correctamente.",
  INSUFFICIENT_CAPACITY: "La sala no admite al número de asistentes indicado.",
  REQUEST_KEY_CONFLICT: "La clave de petición ya pertenece a otra operación.",
  INVALID_CREDENTIALS: "La contraseña no es correcta.",
  AUTHENTICATION_REQUIRED: "Inicia sesión para ejecutar la petición.",
  REQUEST_LIMIT_REACHED:
    "Esta sesión ya tiene veinte peticiones. Inicia una sesión nueva.",
  INVALID_SLOT: "Selecciona una franja futura dentro del próximo año.",
  INVALID_REQUEST:
    "Revisa el mensaje, los asistentes y la duración de la franja (15 minutos a 8 horas).",
  STORAGE_UNAVAILABLE:
    "No se ha podido consultar el almacenamiento. Repite la misma petición para comprobar su resultado.",
};

function display(result) {
  element("result").hidden = false;
  element("trace-panel").hidden = false;
  element("outcome").textContent =
    messages[result.status] ||
    `La aplicación ha detenido el turno: ${result.status}.`;
  element("reservation").hidden = !result.reservation;
  element("reservation").textContent = result.reservation
    ? JSON.stringify(result.reservation, null, 2)
    : "";
  element("counts").textContent =
    `Llamadas al modelo: ${result.modelCalls}. Ejecuciones de herramientas: ${result.toolCalls}.`;
  element("trace").replaceChildren();
  for (const event of result.trace) {
    const item = document.createElement("li");
    const title = document.createElement("strong");
    title.textContent = `${event.stage}${event.operation ? ` · ${event.operation}` : ""}`;
    const data = document.createElement("pre");
    data.textContent = JSON.stringify(event.data, null, 2);
    item.append(title, data);
    element("trace").append(item);
  }
  if (!result.trace.length) {
    const item = document.createElement("li");
    item.textContent =
      "Se ha recuperado la confirmación guardada para esta petición. No se han repetido las llamadas.";
    element("trace").append(item);
  }
}

async function run() {
  feedback("La aplicación está atendiendo la petición…");
  const result = await api(`/api/requests/${pendingId}/run`, "POST", {});
  display(result);
  feedback("Turno terminado.");
}

async function perform(action) {
  if (busy) return;
  busy = true;
  element("submit").disabled = true;
  element("retry").disabled = true;
  try {
    await action();
  } catch (error) {
    feedback(
      messages[error.message] ||
        "No se ha recibido un resultado. Si la petición ya está preparada, usa «Repetir la misma petición» para comprobarla.",
    );
  } finally {
    busy = false;
    element("submit").disabled = false;
    element("retry").disabled = !pendingId;
  }
}

element("login").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    showSession(
      await api("/api/session", "POST", {
        password: element("password").value,
      }),
    );
    element("password").value = "";
    feedback("Sesión iniciada.");
  } catch (error) {
    feedback(messages[error.message] || "No se ha podido iniciar la sesión.");
  }
});
element("logout").addEventListener("click", async () => {
  try {
    await api("/api/session", "DELETE");
    pendingId = null;
    showSession(await api("/api/session"));
    element("result").hidden = true;
    element("trace-panel").hidden = true;
    feedback("Sesión cerrada.");
  } catch {
    feedback("No se ha podido cerrar la sesión.");
  }
});
element("booking").addEventListener("input", () => {
  pendingId = null;
  element("retry").disabled = true;
});
element("booking").addEventListener("submit", (event) => {
  event.preventDefault();
  perform(async () => {
    pendingId = null;
    const request = await api("/api/requests", "POST", {
      message: element("message").value,
      startsAt: new Date(element("starts").value).toISOString(),
      endsAt: new Date(element("ends").value).toISOString(),
      attendeeCount: Number(element("attendees").value),
      reservationAllowed: element("allow").checked,
    });
    pendingId = request.requestId;
    await run();
  });
});
element("retry").addEventListener("click", () => {
  if (pendingId) perform(run);
});

function localInput(date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
}
const tomorrow = new Date();
tomorrow.setDate(tomorrow.getDate() + 1);
tomorrow.setHours(15, 0, 0, 0);
element("starts").value = localInput(tomorrow);
element("ends").value = localInput(new Date(tomorrow.getTime() + 3600000));
api("/api/session")
  .then(showSession)
  .catch(() => feedback("No se puede conectar con el servidor."));
