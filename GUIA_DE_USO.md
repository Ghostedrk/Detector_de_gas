# Instalación y demostración

Esta versión conecta el detector y dos teléfonos dentro de una misma red Wi-Fi. El monitoreo no necesita Internet. La nube con Firebase (sección 10) es opcional y requiere Internet.

## 1 Instalar la aplicación

Instala el APK de esta entrega en ambos teléfonos o ejecuta el proyecto desde Android Studio.

Si instalaste el APK anterior y Android rechaza la actualización por una firma diferente, desinstala esa versión antes de instalar la local. La desinstalación borra sus datos locales. El proyecto mantiene el identificador `com.example.detectordegas`.

Para ver las pantallas sin preparar el circuito, pulsa **Explorar con datos simulados** y luego **Iniciar monitoreo**. Los datos de esa demostración se identifican como simulados y su historial está separado del real.

Para demostrar la **comunicación entre dos Android sin circuito**, inicia sesión en ambos y, en el emisor, elige **Ajustes → Fuente del sensor → ESP32 simulado (sin circuito)**. El emisor genera lecturas con la misma lógica del Arduino (espera de 5 s, umbrales 500/450, prueba de 3 s) y las comparte cifradas con el receptor. Sigue los pasos 4 a 7 omitiendo el paso 3.

## 2 Preparar la red

Puedes utilizar:

- Un router Wi-Fi al que se conecten los dos Android y el ESP32, aunque el router no tenga conexión a Internet.
- El punto de acceso de uno de los teléfonos, al que se conecten el ESP32 y el segundo teléfono. Configúralo en 2,4 GHz para que pueda conectarse el ESP32.

No necesitas activar datos móviles. Si Android pregunta si quieres mantener una conexión Wi-Fi sin Internet, mantén esa conexión. Algunos equipos aíslan los clientes de un punto de acceso: si no se comunican, desactiva ese aislamiento o utiliza un router local.

## 3 Preparar Arduino y ESP32

Carga los programas incluidos en `firmware/`, cada uno en su placa correspondiente.

El programa Arduino conserva las conexiones del archivo adjunto: MQ-2 en A0, LED verde en D4, LED rojo en D5 y control del buzzer en D8. La espera inicial es **5000 milisegundos**. Los umbrales son **500 para activar** y **450 para retornar**, según el archivo de texto original. El informe anterior tenía otra versión con 220/150; presenta una única versión coherente.

Conexiones de datos, con GND común:

- D1/TX del Uno → divisor resistivo → GPIO16/RX2 del ESP32 (lecturas).
- GPIO17/TX2 del ESP32 → D0/RX del Uno (orden de prueba desde la app). **Desconecta este cable mientras cargas el programa del Uno.**

En el programa ESP32:

1. Sustituye `TU_RED_WIFI` y `TU_CLAVE_WIFI` por los datos del router o punto de acceso local. No se incluyó la contraseña personal que aparecía en tu informe.
2. En la app emisora, abre **Ajustes → Fuente del sensor → ESP32 real**, pulsa **Generar clave nueva** y copia los 64 caracteres en `CLAVE_DISPOSITIVO`. Sin esa clave el ESP32 responde 503 y no entrega lecturas.

Abre el Monitor serie del ESP32 a **115200 baudios** y anota la IP que muestra. La página `http://IP_DEL_ESP32/` ya no muestra lecturas: `/estado` solo responde a solicitudes firmadas con la clave, así que un navegador recibe 401. El cuerpo firmado tiene esta estructura:

```json
{"lectura":620,"estado":"alerta","conexion_arduino":true,"unidad":"ADC"}
```

Protocolo (implementado igual en la app y en el firmware):

| Ruta | Uso | Autenticación |
| --- | --- | --- |
| `GET /estado` | Lectura actual | `X-Nonce` aleatorio + `X-Firma = HMAC-SHA256(clave, "GET /estado\n" + nonce)`; respuesta con `X-Firma = HMAC(clave, "RESPUESTA\n" + nonce + "\n" + cuerpo)` |
| `GET /desafio` | Desafío de 16 bytes, un solo uso, válido 10 s | Ninguna (no entrega datos) |
| `POST /prueba` | Envía `T` al Arduino | `X-Nonce` = desafío + firma `"POST /prueba\n" + desafío`; respuesta firmada |

Las lecturas son ADC, no ppm calibradas. La espera de cinco segundos es un ajuste del programa; no asegura que el MQ-2 esté estabilizado ni calibrado.

## 4 Crear una cuenta local en cada teléfono

En cada Android:

1. Abre la aplicación y pulsa **Crear una cuenta local**.
2. Elige un usuario de entre 3 y 30 letras, números o guion bajo.
3. Elige una contraseña de entre 8 y 128 caracteres.
4. Pulsa **Crear cuenta local**.

Las cuentas son independientes: puedes usar usuarios diferentes en los dos teléfonos. Crear una cuenta en el primero no la crea en el segundo. No se necesita correo electrónico. La contraseña no se comparte con el otro teléfono.

Para volver a entrar, usa **Entrar** con las credenciales creadas en ese mismo dispositivo. No existe recuperación externa de contraseñas; para otra cuenta local, vuelve al registro.

### Roles

- **Administrador**: la primera cuenta creada en el teléfono. Configura el rol del teléfono, las IP, la fuente del sensor, la clave del ESP32 y el código de enlace. También envía **Probar alarma** y borra el historial.
- **Operador**: las cuentas siguientes. Monitorea, consulta su historial y solicita o confirma revisiones. Usa la configuración que dejó el administrador y no ve las claves.

Para la demostración, crea primero la cuenta administradora en cada teléfono y después, si quieres mostrar el control de acceso, una cuenta operadora.

## 5 Configurar el teléfono emisor

Elige uno de los teléfonos para consultar directamente el circuito y compartir las lecturas:

1. En **Ajustes**, selecciona **Emisor: consulta el ESP32**.
2. En **Fuente del sensor** elige **ESP32 real** (circuito) o **ESP32 simulado** (sin circuito).
3. Con ESP32 real: escribe únicamente su IP, por ejemplo `192.168.1.50`, guarda la dirección y comprueba que la clave del ESP32 coincide con `CLAVE_DISPOSITIVO`.
4. Pulsa **Mostrar código de enlace**. Mantén pulsado el texto para copiar sus 64 caracteres. Este código se genera en el teléfono; no es la contraseña Wi-Fi ni la contraseña de tu cuenta.
5. Vuelve a **Monitor** y pulsa **Iniciar monitoreo**.
6. Si Android solicita acceso a dispositivos de la red local, concédelo.
7. Abre **Teléfonos**. Allí se muestran las IP privadas de este teléfono con el nombre de sus interfaces. Anota la dirección correspondiente al Wi-Fi, normalmente `wlan0`, o a su punto de acceso. La otra app necesita esa IP, no la del ESP32.

En el emisor existen dos direcciones distintas: la **IP del ESP32**, usada para consultar el sensor, y la **IP del teléfono emisor**, usada por el segundo Android para conectarse.

## 6 Configurar el receptor

En el segundo teléfono:

1. Inicia sesión con su cuenta local.
2. En **Ajustes**, selecciona **Receptor: consulta al emisor**.
3. Escribe la IP Wi-Fi del primer teléfono.
4. Pega el código de enlace de 64 caracteres que mostró el emisor.
5. Guarda la dirección y vuelve a **Monitor**.
6. Pulsa **Iniciar monitoreo** y concede el permiso de red local si se solicita. El código se valida y se guarda protegido al iniciar.

Mantén abiertas las dos aplicaciones. Verás las lecturas del circuito en el receptor. La consulta se repite aproximadamente cada dos segundos más el tiempo que tarda la red. Si el emisor deja de responder, se retira la lectura vigente y se muestra el fallo de conexión.

Si cambia la IP del emisor, detén el monitoreo del receptor y actualiza esa dirección. Si generas otro código en el emisor, copia el nuevo al receptor antes de volver a iniciar.

## 7 Demostrar comunicación en ambos sentidos

Con ambos monitores en marcha:

1. En el receptor, abre **Teléfonos** y pulsa **Solicitar revisión al otro teléfono**.
2. En el emisor, abre **Teléfonos** y comprueba que aparece la solicitud.
3. Pulsa **Confirmar revisión**.
4. Comprueba que el receptor muestra la confirmación y el nombre del teléfono que respondió.
5. También puedes enviar una solicitud desde el emisor y confirmarla en el receptor.

La confirmación es un registro de revisión humana. No cambia las lecturas ni desactiva la alarma.

### Control del detector: prueba de alarma

En **Monitor → Control del detector** pulsa **Probar alarma (3 s)**:

- **En el emisor**, la app pide un desafío al ESP32 y envía `POST /prueba` firmado. El ESP32 reenvía `T` al Arduino, que enciende el LED rojo y el buzzer 3 s. La lectura pasa a **Prueba manual**.
- **En el receptor**, la orden viaja cifrada al emisor, que la ejecuta en su siguiente ciclo (unos 2 s) y comunica el resultado a ambos teléfonos.
- **Con ESP32 simulado o en la demostración**, el simulador pasa al estado de prueba durante 3 s.

Si hay una alerta real del sensor, esta tiene prioridad sobre la prueba. La prueba también puede enviarse con `T` desde el Monitor serie del Arduino, a 9600 baudios.

Las últimas 20 solicitudes se mantienen en memoria en el teléfono emisor. Se borran al cerrar sesión, cambiar de rol o terminar su proceso. No son un historial permanente.

## 8 Consultar el historial local

Cada teléfono guarda los datos que observa bajo su cuenta local. La sección **Historial** muestra los últimos 100 registros y la base SQLite conserva hasta 500 por cuenta. Se guarda aproximadamente cada cinco segundos o al cambiar de estado.

Los datos del receptor se fechan cuando los recibe. No se descarga el historial previo del emisor y no se sincronizan las cuentas. Si dejas de monitorear, no se guardan nuevas lecturas.

El botón de borrar historial solicita confirmación. Solo borra los registros de la cuenta actual en ese teléfono.

## 9 Comprobación sin Internet para presentar

1. Desactiva los datos móviles y desconecta la salida a Internet del router, manteniendo la red Wi-Fi local activa.
2. Inicia sesión local en ambos teléfonos.
3. Muestra la lectura del sensor en los dos Android.
4. Envía una solicitud de revisión desde un teléfono y confírmala en el otro.
5. Desde el receptor pulsa **Probar alarma (3 s)** y muestra el LED rojo y el buzzer (o el estado de prueba en el simulador).
6. Muestra los registros SQLite de cada teléfono.
7. Desconecta de forma segura el enlace UART y verifica que se indica ausencia de datos.
8. Detén el monitor del emisor y comprueba que el receptor muestra pérdida de conexión.
9. Explica la seguridad aplicada y la limitación del enlace HTTP con el ESP32. No realices pruebas con fugas de gas.

La red local y el prototipo funcionan sin servicios externos. Para preparar o recompilar el proyecto, Android Studio puede necesitar descargar herramientas y bibliotecas; eso no es una dependencia de la aplicación instalada.

## 10 Nube con Firebase (opcional)

Sin este paso, la app compila y funciona en local, y la sección **Nube** de Ajustes indica que Firebase no está configurado.

### Crear el proyecto (una vez, en el computador)

1. Entra en <https://console.firebase.google.com> con tu cuenta de Google y pulsa **Crear un proyecto** (por ejemplo `detector-de-gas`). Google Analytics no es necesario.
2. **Agregar app → Android**. Nombre del paquete: `com.example.detectordegas`. Registra la app.
3. Descarga **google-services.json** y cópialo en la carpeta `app/` del proyecto (junto a `app/build.gradle.kts`). Está excluido de Git, así que no se sube al repositorio público.
4. **Authentication → Comenzar → Correo electrónico/contraseña → Habilitar → Guardar**.
5. **Firestore Database → Crear base de datos**. Elige una ubicación cercana (por ejemplo `southamerica-east1`) y empieza en **modo de producción**.
6. En **Firestore → Reglas**, borra el contenido, pega el de `firebase/firestore.rules` y pulsa **Publicar**.
7. En Android Studio, pulsa **Sync Project with Gradle Files** y vuelve a ejecutar la app.

### Usarla en la demostración

1. En el **emisor**, como administrador: **Ajustes → Nube · Firebase**, escribe un correo y una contraseña de 8 caracteres o más, y pulsa **Crear cuenta**. Después usa **Entrar**.
2. Inicia el monitoreo. Cada lectura que se guarda en SQLite también se sube a Firestore (aproximadamente cada 5 s o al cambiar de estado).
3. En el **receptor**, como administrador, entra en la nube con **la misma cuenta**.
4. En cualquiera de los dos: **Historial → Nube · Firestore → Actualizar desde la nube**.
5. En Firebase Console → Firestore se ven los documentos en `users/{uid}/readings`. Muéstralo en la presentación.

Solo el emisor sube lecturas, para no duplicarlas. Sin Internet, Firestore las guarda en caché y las envía al volver la conexión. Las lecturas en la nube no se pueden borrar ni modificar desde la app: es un registro de solo anexado.

Con el plan gratuito (Spark) hay un límite diario de escrituras más que suficiente para la evaluación. Detén el monitoreo cuando no lo estés usando.

## 11 Seguridad implementada y límites

Las contraseñas se guardan como hashes PBKDF2 con una sal aleatoria por cuenta y 210000 iteraciones. Se utiliza HMAC-SHA256 en Android 8 o posterior y HMAC-SHA1 en Android 7. Tras cinco contraseñas incorrectas consecutivas de una cuenta, se bloquean los intentos durante 30 segundos. Las consultas SQL utilizan parámetros.

Entre teléfonos, los mensajes usan AES-256-GCM, una clave aleatoria compartida y un desafío nuevo por conexión. El receptor verifica la autenticidad de la respuesta y la solicitud a la que corresponde. El código de enlace se almacena cifrado con una clave de Android Keystore. Cualquier teléfono que posea ese código puede enlazarse: compártelo únicamente con el segundo dispositivo de la evaluación.

El enlace con el ESP32 usa HTTP con **autenticación HMAC-SHA256** mediante una clave de 32 bytes compartida entre la app y el firmware:

- Solo un cliente con la clave obtiene lecturas o envía órdenes; el resto recibe 401.
- Las respuestas están firmadas sobre el nonce del cliente, por lo que no se pueden falsificar ni reutilizar respuestas antiguas.
- La orden de control exige un desafío aleatorio de un solo uso y 10 s de vigencia, lo que impide repetirla capturando el tráfico.
- Las firmas se comparan en tiempo constante. En la app la clave se guarda cifrada con Android Keystore.
- La dirección se restringe a una IPv4 privada y no se siguen redirecciones.

Límite que debe explicarse: ese tramo garantiza **autenticidad e integridad, pero no confidencialidad**. Las lecturas viajan legibles dentro de la Wi-Fi local (protegida por WPA2). Se descartó HTTPS en el ESP32 porque exige gestionar certificados en el microcontrolador y en Android sin una autoridad de certificación local. No se acredita cumplimiento completo de ISO 27400.

El monitoreo y el servidor del emisor se pausan cuando la app deja de estar visible. No hay alertas Android en segundo plano. Las alertas físicas del Arduino continúan funcionando de forma independiente.
