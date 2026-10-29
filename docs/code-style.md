# Perfil de estilo inspirado en Clean Code

El proyecto utiliza **Clean Code - Project Style 1.0**: una política de estilo propia, versionada y comprobable desde Maven. Recoge criterios de legibilidad inspirados en Clean Code y decisiones concretas del proyecto. El resultado de la validación acredita estas reglas; no certifica todos los principios de diseño del libro.

No depende de un IDE. `config/checkstyle-clean-code.xml` utiliza comprobaciones nativas de Checkstyle. `config/eclipse-formatter.xml` define el formato exacto que Spotless aplica y verifica. No se ha implementado un analizador de código propio. Spotless es la autoridad para la indentación exacta y el reparto de continuaciones y paréntesis; Checkstyle no duplica esa decisión con otro algoritmo de indentación. Así se evita que un comprobador rechace la distribución que exige el otro.

## Reglas del perfil

| Regla | Aplicación y comprobación |
| --- | --- |
| UTF-8, finales LF, salto final y ausencia de espacios al final | Spotless; Checkstyle comprueba también el salto final y los tabuladores. |
| Cuatro espacios por nivel y cuatro para las continuaciones | Perfil de formato, comprobado por Spotless. |
| Hasta 120 columnas, exceptuando `package` e imports | Formateador y `LineLength`. |
| Llave de apertura de métodos, constructores y constructores compactos en la línea siguiente | Formateador y `LeftCurly`, con `nl` para `METHOD_DEF`, `CTOR_DEF` y `COMPACT_CTOR_DEF`. |
| Llaves de tipos y estructuras de control al final de su cabecera | Formateador y otra instancia de `LeftCurly`, con `eol`. |
| Llaves obligatorias en las estructuras de control | `NeedBraces`. |
| Cadenas de llamadas que necesitan varias líneas, con una llamada por línea | Perfil de formato, comprobado por `spotless:check`. Las cadenas cortas pueden conservarse en una línea. |
| Argumentos y parámetros distribuidos cuando no caben; cierres de llamadas multilínea separados por niveles | Perfil de formato, comprobado por Spotless. |
| Una sentencia y una declaración de variable por línea | `OneStatementPerLine` y `MultipleVariableDeclarations`. |
| Espacios coherentes en operadores, comas, paréntesis y genéricos | Formateador, `WhitespaceAfter`, `WhitespaceAround`, `ParenPad`, `MethodParamPad` y `GenericWhitespace`. |
| Una línea en blanco entre miembros, sin separar mecánicamente cada campo | Formateador y `EmptyLineSeparator`, permitiendo campos consecutivos. |
| Imports explícitos y sin imports sin utilizar | Spotless, `AvoidStarImport` y `UnusedImports`. |
| Nombres con convenciones de Java y orden de modificadores | Reglas nativas de nombres y `ModifierOrder`. |

La separación en fases lógicas dentro de un método se revisa manualmente: las instrucciones relacionadas permanecen juntas y una línea en blanco puede separar preparación, ejecución y construcción del resultado. El formateador conserva esa separación. Una herramienta no puede deducir siempre dónde cambia la intención del código.

El perfil no obliga a añadir comentarios que repitan el código. La pertinencia de los nombres y la documentación se revisa al leer el cambio. Las comprobaciones de diseño y las pruebas del proyecto conservan su propio alcance y no se utilizan como criterios de formato en este perfil.

## Comprobar y corregir el estilo

Con Docker en ejecución, desde la raíz del proyecto, en PowerShell, Bash o cualquier terminal:

```sh
docker compose -f compose.style.yaml run --rm --no-deps check
```

Para aplicar el formato de manera explícita:

```sh
docker compose -f compose.style.yaml run --rm --no-deps format
```

`check` ejecuta `mvn validate`: Spotless comprueba el formato y Checkstyle comprueba las reglas. Un incumplimiento devuelve un código distinto de cero. `format` ejecuta `spotless:apply`; después se deben revisar las diferencias y volver a comprobar. Ninguno de los comandos crea commits ni prepara archivos en Git.

La imagen de Maven con Java 25 se fija por etiqueta y digest en `compose.style.yaml`. Las versiones de Spotless, Eclipse JDT y Checkstyle se fijan en el POM. Una caché Maven en un volumen de Docker conserva las dependencias; la primera ejecución puede necesitar descargarlas. No se arranca la aplicación ni PostgreSQL.

El formato se ejecuta con JDK 25; `maven.compiler.release` conserva Java 17 como destino de compilación de la aplicación. Con Maven instalado:

```sh
mvn validate
mvn spotless:apply
```

Los mismos comandos Maven sirven en PowerShell.

## Instalar precommit

El hook usa pre-commit 4.7.0, fijado en `requirements-style.txt`. Python 3.12 o posterior se utiliza para esta herramienta de desarrollo; la aplicación sigue siendo Java. Su instalación es local a cada clon.

En PowerShell:

```powershell
python -m venv .venv-style
.\.venv-style\Scripts\python.exe -m pip install -r requirements-style.txt
.\.venv-style\Scripts\python.exe -m pre_commit install
.\.venv-style\Scripts\python.exe -m pre_commit run --all-files
```

En Linux y macOS:

```sh
python3 -m venv .venv-style
.venv-style/bin/python -m pip install -r requirements-style.txt
.venv-style/bin/python -m pre_commit install
.venv-style/bin/python -m pre_commit run --all-files
```

`.pre-commit-config.yaml` llama al mismo servicio `check` de Docker Compose. El hook se activa al preparar Java, POM o configuraciones de estilo. La invocación de Maven comprueba todos los módulos; no se presenta como una comprobación limitada a los archivos cambiados. `pass_filenames: false` evita pasar nombres de archivos a Maven como si fueran argumentos suyos.

El hook comprueba y rechaza; no aplica el formateador ni ejecuta `git add`. Pre-commit aparta temporalmente los cambios no preparados de los archivos seguidos y los restaura al terminar. Esto permite comprobar la versión preparada cuando solo se quiere incorporar una parte de un archivo.

La configuración versionada no instala el hook en otros equipos: cada desarrollador debe ejecutar el paso de instalación. Los hooks locales pueden omitirse, por lo que CI vuelve a comprobar las mismas reglas.

## Integración continua y alcance

El workflow `Java checks` incluye el job independiente `Code style`, visible en las pull requests como `Java checks / Code style`. Ejecuta `docker compose -f compose.style.yaml run --rm --no-deps check`, el mismo comando del precommit, sobre las fuentes y pruebas Java de todos los módulos. Se ejecuta en paralelo con `verify` y devuelve su propio resultado; un incumplimiento hace fallar este check.

Las tareas completas de calidad y pruebas siguen ejecutándose en `verify`. Sus ejecuciones Maven también alcanzan `validate`, por lo que conservan la comprobación del perfil.

Modificar una regla exige sincronizar Checkstyle y el formateador, aplicar el formato y verificar ejemplos correctos e incorrectos. La migración de estilo se revisa separadamente de los cambios de comportamiento. Las reglas de diseño, las pruebas funcionales y los estándares de otros proyectos mantienen su alcance propio.

## Referencias

- [Checkstyle: configuración de reglas](https://checkstyle.org/config.html).
- [Checkstyle: posición de llaves](https://checkstyle.org/checks/blocks/leftcurly.html).
- [Checkstyle: separación de miembros](https://checkstyle.org/checks/whitespace/emptylineseparator.html).
- [Spotless para Maven y su integración con Eclipse JDT](https://github.com/diffplug/spotless/tree/main/plugin-maven#eclipse-jdt).
- [Pre-commit: instalación y funcionamiento](https://pre-commit.com/).
