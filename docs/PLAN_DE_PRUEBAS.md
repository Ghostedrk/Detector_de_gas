# Plan de pruebas y evidencias

Cubre el indicador 2.1.3.11: autenticación, comunicación con dispositivos IoT, manipulación de datos y seguridad. Versión probada: 2.1-local.

## 1. Pruebas automatizadas

### Cómo ejecutarlas

Desde Android Studio: clic derecho en `app/src/test` → **Run Tests**. Desde la terminal (requiere `JAVA_HOME` apuntando a `C:\Program Files\Android\Android Studio\jbr`):

```bash
gradlew.bat testDebugUnitTest
gradlew.bat connectedDebugAndroidTest
```

La segunda orden necesita un emulador o un teléfono conectado.

### Pruebas unitarias (JVM): 22 de 22 aprobadas

| # | Área | Qué verifica | Clase | Resultado |
| --- | --- | --- | --- | --- |
| U01 | Conexión IoT | Acepta solo IPv4 privadas literales (10/8, 172.16/12, 192.168/16) | `EndpointValidationTest` | Aprobada |
| U02 | Seguridad | Rechaza IP públicas, loopback, dominios, rutas, `https://` y `@` | `EndpointValidationTest` | Aprobada |
| U03 | Datos | Interpreta el JSON del ESP32 | `SensorProtocolTest` | Aprobada |
| U04 | Datos | La pérdida de datos no se convierte en 0 ni en "sin alerta" | `SensorProtocolTest` | Aprobada |
| U05 | Datos | Acepta la ausencia del campo `lectura` cuando no hay datos | `SensorProtocolTest` | Aprobada |
| U06 | Datos | Rechaza decimales, texto, valores fuera de rango, otra unidad y estados contradictorios | `SensorProtocolTest` | Aprobada |
| U07 | Seguridad | AES-GCM con IV aleatorio distinto por mensaje | `LocalSecurityTest` | Aprobada |
| U08 | Seguridad | Rechaza mensajes alterados, clave incorrecta y mensajes reflejados | `LocalSecurityTest` | Aprobada |
| U09 | Seguridad | El código de enlace exige 32 bytes completos | `LocalSecurityTest` | Aprobada |
| U10 | Autenticación | El hash PBKDF2 depende de la contraseña y de la sal | `LocalSecurityTest` | Aprobada |
| U11 | Autenticación | Algoritmo para Android 7 disponible | `LocalSecurityTest` | Aprobada |
| U12 | Comunicación entre Android | Intercambio TCP real de una lectura y de una revisión con confirmación | `LocalPeerTest` | Aprobada |
| U13 | Comunicación entre Android | Clave incorrecta o desafío ajeno no obtienen datos | `LocalPeerTest` | Aprobada |
| U14 | Comunicación entre Android | Rechaza operaciones repetidas y la confirmación de una solicitud propia | `LocalPeerTest` | Aprobada |
| U15 | Simulador | Espera de 5 s, umbrales 500/450 con histéresis y prueba de 3 s | `SimulatedDetectorTest` | Aprobada |
| U16 | Simulador | La alerta tiene prioridad sobre la prueba manual | `SimulatedDetectorTest` | Aprobada |
| U17 | Simulador | Las lecturas simuladas cumplen el protocolo del ESP32 | `SimulatedDetectorTest` | Aprobada |
| U18 | Seguridad IoT | Firmas HMAC iguales a vectores calculados aparte con Python | `EspAuthTest` | Aprobada |
| U19 | Seguridad IoT | Rechaza respuestas alteradas, con otro nonce, otra clave o sin firma | `EspAuthTest` | Aprobada |
| U20 | Seguridad IoT | La clave del ESP32 debe tener 64 caracteres hexadecimales | `EspAuthTest` | Aprobada |
| U21 | Conexión IoT | HTTP real contra un ESP32 falso en la IP privada del PC: lectura firmada, orden con desafío y rechazo con clave incorrecta | `EspClientTest` | Aprobada |
| U22 | Control IoT | La orden del receptor se encola una sola vez y ambos teléfonos ven el resultado | `RemoteControlTest` | Aprobada |

Ejecución: 10/11/2026 con `gradlew.bat testDebugUnitTest`, BUILD SUCCESSFUL, 0 fallos.

### Pruebas instrumentadas (SQLite real en Android): 4 de 4 aprobadas

| # | Qué verifica | Clase | Resultado |
| --- | --- | --- | --- |
| I01 | La primera cuenta es administradora y las siguientes operadoras; el rol se conserva al iniciar sesión | `LocalAccountsTest` | Aprobada |
| I02 | Rechaza usuarios duplicados y contraseñas incorrectas, y bloquea 30 s tras 5 fallos | `LocalAccountsTest` | Aprobada |
| I03 | El historial está separado por cuenta y el borrado solo afecta a la cuenta actual | `LocalAccountsTest` | Aprobada |
| I04 | El contexto corresponde al paquete `com.example.detectordegas` | `ExampleInstrumentedTest` | Aprobada |

Ejecución: 10/11/2026 en el emulador `Medium_Phone_API_37.0` (Android 17) con `gradlew.bat connectedDebugAndroidTest`, 0 fallos.

### Errores detectados y corregidos durante las pruebas

| Hallazgo | Corrección |
| --- | --- |
| El modo simulado no permitía demostrar el enlace entre dos Android sin circuito | Se agregó un ESP32 simulado en el emisor, que comparte lecturas con el receptor |
| El enlace HTTP con el ESP32 no tenía autenticación: cualquier equipo de la red podía leer o falsificar datos | Firmas HMAC-SHA256 en las solicitudes y respuestas, y un desafío de un solo uso para las órdenes |
| La app solo monitoreaba; no había control del dispositivo | Orden "Probar alarma" del emisor o del receptor hacia el ESP32 y el Arduino |
| No había control de acceso por rol | Roles administrador/operador comprobados en el ViewModel |
| El código de enlace era por cuenta, así que un operador no podía usar el enlace configurado | El código de enlace y la clave del ESP32 pasan a ser del dispositivo y se guardan cifrados con Keystore |
| `gradlew.bat` no encontraba Java | Se documentó `JAVA_HOME` con el JDK de Android Studio |
| En el emulador, al cambiar de pestaña se conservaba el desplazamiento de la anterior (Historial se abría a mitad de la lista) | Cada pestaña tiene su propio estado de desplazamiento y empieza arriba |

## 2. Pruebas manuales (completar en la demostración)

Escribe en **Obtenido** lo que ocurra y guarda una captura en `docs/evidencias/` con el número de la prueba (por ejemplo `M03.png`).

| # | Prueba | Pasos | Resultado esperado | Obtenido |
| --- | --- | --- | --- | --- |
| M01 | Registro del administrador | Crear la primera cuenta en el teléfono | Entra; Ajustes muestra "administrador" | **Correcto** (emulador): `admin_1 · administrador`. [Login](evidencias/M01_login.png), [sesión](evidencias/M01_sesion_administrador.png) |
| M02 | Registro de un operador | Cerrar sesión y crear otra cuenta | Entra como "operador"; Ajustes en solo lectura y "Probar alarma" desactivado | **Correcto** (emulador): aviso de operador, sin claves, botón desactivado. [Ajustes](evidencias/M02_ajustes_operador.png), [Monitor](evidencias/M02_monitor_operador.png) |
| M03 | Contraseña incorrecta | Cinco intentos con una contraseña errónea | Rechazo genérico; bloqueo de 30 s | |
| M04 | Login sin Internet | Modo avión con Wi-Fi local activo | El login y el historial funcionan | |
| M05 | Emisor con ESP32 simulado | Ajustes → Emisor → ESP32 simulado → Iniciar monitoreo | Lecturas cada 2 s; alerta al pasar de 500 | **Correcto** (emulador): espera inicial → 610 en alerta; Probar alarma pasa a estado de prueba. [Alerta](evidencias/M05_alerta_simulada.png), [prueba](evidencias/M05_prueba_alarma.png) |
| M06 | Enlace entre dos Android | Receptor con la IP del emisor y su código | El receptor muestra la lectura "Emisor · SIMULACIÓN" | |
| M07 | Código de enlace incorrecto | Cambiar un carácter del código en el receptor | "El código de enlace no coincide"; sin lecturas | |
| M08 | Revisión en ambos sentidos | Solicitar en uno y confirmar en el otro | Ambos muestran "Confirmada por …" | |
| M09 | Control desde el receptor | Probar alarma (3 s) en el receptor (administrador) | Estado "Prueba manual" en ambos; mensaje del resultado | |
| M10 | Historial | Abrir Historial tras 1 minuto de monitoreo | Registros con fecha, estado, origen y valor | **Correcto** (emulador): incluye "Prueba manual" y la histéresis (470 sigue en alerta); cada cuenta ve solo sus registros. [Historial](evidencias/M10_historial.png) |
| M11 | Pérdida del emisor | Detener el monitoreo del emisor | El receptor muestra "Sin conexión" y retira la lectura | |
| M12 | App en segundo plano | Salir al inicio y volver | Se pausa y se reanuda sola | |
| M13 | ESP32 real autenticado | Clave igual en la app y en el firmware | "ESP32 conectado y autenticado" | |
| M14 | Clave del ESP32 incorrecta | Cambiar la clave en la app | "El ESP32 rechazó la firma" | |
| M15 | Alarma física | Probar alarma con el circuito | LED rojo y buzzer durante 3 s | |
| M16 | Sin datos del Arduino | Desconectar el cable de GPIO16 | "Arduino sin datos"; nunca "sin alerta" | |

M13 a M16 requieren el circuito. M01 a M12 se pueden hacer con dos teléfonos o con dos emuladores.

## 3. Límites de la validación

- La interfaz se probó en un solo emulador (Android 17). El enlace entre dos Android (M06–M09, M11) necesita un segundo dispositivo; la lógica TCP está cubierta por U12–U14 y U22. [Pantalla Teléfonos del emisor](evidencias/M06_telefonos_emisor.png).
- El firmware no se compiló aquí (no hay arduino-cli instalado). Debe compilarse en el IDE de Arduino con el paquete ESP32.
- El enlace con el ESP32 está autenticado, pero no cifrado. No se acredita cumplimiento completo de ISO 27400.
- La app no da alertas en segundo plano: debe estar abierta durante la demostración.
