/*
  DETECTOR DE GAS EDUCATIVO
  PLACA: ESP32 / DOIT ESP32 DEVKIT V1

  - Recibe datos del Arduino Uno por UART.
  - Se conecta a una red Wi-Fi de 2,4 GHz.
  - Publica JSON firmado en /estado (HMAC-SHA256).
  - Recibe la orden de prueba en /prueba y la reenvia al Arduino ("T").
  - Indica cuando dejan de llegar datos del Arduino.

  SEGURIDAD:
  Toda consulta debe traer X-Nonce y X-Firma calculadas con la clave
  compartida CLAVE_DISPOSITIVO. Las respuestas tambien se firman.
  La orden /prueba exige un desafio de un solo uso (GET /desafio).

  CONEXIONES:
  GPIO16 / RX2 <- divisor resistivo <- D1/TX del Uno
  GPIO17 / TX2 -> D0/RX del Uno (desconectar al cargar el programa del Uno)
  GND del ESP32 unido al GND del Uno

  Monitor serie USB: 115200 baudios.
  Enlace con el Arduino Uno: 9600 baudios.
*/

#include <WiFi.h>
#include <WebServer.h>
#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include "mbedtls/md.h"

// CAMBIAR por los datos exactos de su red.
const char* NOMBRE_RED = "TU_RED_WIFI";
const char* CLAVE_RED = "TU_CLAVE_WIFI";

// CAMBIAR por la clave de 64 caracteres generada en Ajustes de la app.
const char* CLAVE_DISPOSITIVO = "PEGA_AQUI_LA_CLAVE_DE_64_CARACTERES";

// Pines del enlace serial.
const int PIN_RX_UNO = 16;
const int PIN_TX_UNO = 17;

HardwareSerial enlaceUno(2);
WebServer servidor(80);

// Ultima lectura valida recibida.
int lectura = 0;
char estado[24] = "sin_datos";

bool hayDatos = false;
unsigned long ultimaRecepcion = 0;

// Tras tres segundos sin mensajes, indicar falta de datos.
const unsigned long LIMITE_DATOS_MS = 3000UL;

// Almacenamiento de una linea recibida.
char linea[64];
size_t longitud = 0;
bool descartarLinea = false;

// Control del Wi-Fi y de los mensajes por consola.
bool servidorActivo = false;
unsigned long ultimoIntentoWiFi = 0;
unsigned long ultimoInforme = 0;

const unsigned long REINTENTO_WIFI_MS = 30000UL;

// Seguridad del enlace con la app.
uint8_t claveBytes[32];
bool claveValida = false;
char desafio[33] = "";
unsigned long desafioCreado = 0;
const unsigned long VIGENCIA_DESAFIO_MS = 10000UL;

int valorHex(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  c = tolower(c);
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  return -1;
}

// Convertir la clave de 64 caracteres hexadecimales en 32 bytes.
bool cargarClave() {
  if (strlen(CLAVE_DISPOSITIVO) != 64) return false;
  for (int i = 0; i < 32; i++) {
    int alto = valorHex(CLAVE_DISPOSITIVO[2 * i]);
    int bajo = valorHex(CLAVE_DISPOSITIVO[2 * i + 1]);
    if (alto < 0 || bajo < 0) return false;
    claveBytes[i] = (uint8_t)(alto * 16 + bajo);
  }
  return true;
}

// HMAC-SHA256 en hexadecimal minuscula (64 caracteres + fin).
void calcularHmac(const String& mensaje, char salida[65]) {
  uint8_t resultado[32];
  mbedtls_md_hmac(
    mbedtls_md_info_from_type(MBEDTLS_MD_SHA256),
    claveBytes, sizeof(claveBytes),
    (const unsigned char*)mensaje.c_str(), mensaje.length(),
    resultado
  );
  for (int i = 0; i < 32; i++) {
    sprintf(salida + 2 * i, "%02x", resultado[i]);
  }
  salida[64] = '\0';
}

// Comparacion en tiempo constante para no revelar la firma.
bool firmasIguales(const String& recibida, const char* esperada) {
  if (recibida.length() != 64) return false;
  uint8_t diferencia = 0;
  for (int i = 0; i < 64; i++) {
    diferencia |= (uint8_t)recibida[i] ^ (uint8_t)esperada[i];
  }
  return diferencia == 0;
}

bool nonceValido(const String& nonce) {
  if (nonce.length() != 32) return false;
  for (int i = 0; i < 32; i++) {
    char c = nonce[i];
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  }
  return true;
}

void responderError(int codigo, const char* mensaje) {
  String cuerpo = "{\"error\":\"";
  cuerpo += mensaje;
  cuerpo += "\"}";
  servidor.sendHeader("Cache-Control", "no-store");
  servidor.send(codigo, "application/json", cuerpo);
}

// Verificar X-Nonce y X-Firma = HMAC(clave, "METODO RUTA\n" + nonce).
bool autorizar(const char* metodo, const char* ruta, String& nonce) {
  if (!claveValida) {
    responderError(503, "clave_no_configurada");
    return false;
  }
  nonce = servidor.header("X-Nonce");
  String firma = servidor.header("X-Firma");
  if (!nonceValido(nonce)) {
    responderError(401, "no_autorizado");
    return false;
  }
  char esperada[65];
  calcularHmac(String(metodo) + " " + ruta + "\n" + nonce, esperada);
  if (!firmasIguales(firma, esperada)) {
    Serial.println("[SEGURIDAD] Solicitud rechazada: firma invalida.");
    responderError(401, "no_autorizado");
    return false;
  }
  return true;
}

// Respuesta firmada: X-Firma = HMAC(clave, "RESPUESTA\n" + nonce + "\n" + cuerpo).
void enviarFirmado(const String& nonce, const String& cuerpo) {
  char firma[65];
  calcularHmac(String("RESPUESTA\n") + nonce + "\n" + cuerpo, firma);
  servidor.sendHeader("X-Firma", firma);
  servidor.sendHeader("Cache-Control", "no-store");
  servidor.send(200, "application/json", cuerpo);
}

// Comprobar que el Arduino sigue enviando informacion.
bool datosVigentes() {
  return hayDatos &&
         millis() - ultimaRecepcion <= LIMITE_DATOS_MS;
}

// Validar los nombres de estado permitidos.
bool estadoValido(const char* valor) {
  return strcmp(valor, "calentando") == 0 ||
         strcmp(valor, "sin_alerta") == 0 ||
         strcmp(valor, "alerta") == 0 ||
         strcmp(valor, "prueba") == 0;
}

// Revisar una linea completa antes de aceptarla.
void procesarLinea() {
  int nuevaLectura;
  char nuevoEstado[24];
  char sobrante;

  int campos = sscanf(
    linea,
    "%4d,%23[a-z_]%c",
    &nuevaLectura,
    nuevoEstado,
    &sobrante
  );

  // Rechazar mensajes invalidos o con caracteres adicionales.
  if (campos != 2 ||
      nuevaLectura < 0 ||
      nuevaLectura > 1023 ||
      !estadoValido(nuevoEstado)) {
    return;
  }

  lectura = nuevaLectura;
  strcpy(estado, nuevoEstado);

  ultimaRecepcion = millis();
  hayDatos = true;
}

// Recibir caracteres sin esperar una linea de forma bloqueante.
void recibirArduino() {
  while (enlaceUno.available() > 0) {
    char caracter = enlaceUno.read();

    if (caracter == '\r') {
      continue;
    }

    if (caracter == '\n') {
      if (!descartarLinea && longitud > 0) {
        linea[longitud] = '\0';
        procesarLinea();
      }

      longitud = 0;
      descartarLinea = false;
    } else if (!descartarLinea) {
      if (longitud < sizeof(linea) - 1) {
        linea[longitud++] = caracter;
      } else {
        // Descartar mensajes que superan el espacio disponible.
        descartarLinea = true;
      }
    }
  }
}

// Pagina principal: sin lecturas, porque los datos solo se entregan firmados.
void mostrarPagina() {
  servidor.sendHeader("Cache-Control", "no-store");
  servidor.send(
    200,
    "text/html; charset=utf-8",
    "<!doctype html><html lang='es'><head><meta charset='utf-8'>"
    "<meta name='viewport' content='width=device-width,initial-scale=1'>"
    "<title>Detector de gas</title></head><body>"
    "<h1>Detector de gas</h1>"
    "<p>Las lecturas solo se entregan a la aplicacion Android autorizada.</p>"
    "<p>Prototipo educativo. No reemplaza un detector certificado.</p>"
    "</body></html>"
  );
}

// Lectura actual, solo para solicitudes firmadas.
void mostrarJSON() {
  String nonce;
  if (!autorizar("GET", "/estado", nonce)) return;

  bool vigente = datosVigentes();

  String respuesta = "{\"lectura\":";

  if (vigente) {
    respuesta += String(lectura);
  } else {
    respuesta += "null";
  }

  respuesta += ",\"estado\":\"";
  respuesta += vigente ? estado : "sin_datos";
  respuesta += "\",\"conexion_arduino\":";
  respuesta += vigente ? "true" : "false";
  respuesta += ",\"unidad\":\"ADC\"}";

  enviarFirmado(nonce, respuesta);
}

// Desafio aleatorio de un solo uso para las ordenes de control.
void entregarDesafio() {
  for (int i = 0; i < 16; i++) {
    sprintf(desafio + 2 * i, "%02x", (unsigned int)(esp_random() & 0xFF));
  }
  desafio[32] = '\0';
  desafioCreado = millis();

  String cuerpo = "{\"desafio\":\"";
  cuerpo += desafio;
  cuerpo += "\"}";
  servidor.sendHeader("Cache-Control", "no-store");
  servidor.send(200, "application/json", cuerpo);
}

// Orden de control: el Arduino activa LED rojo y buzzer durante 3 s.
void ejecutarPrueba() {
  String nonce;
  if (!autorizar("POST", "/prueba", nonce)) return;

  bool vigente =
    desafio[0] != '\0' &&
    millis() - desafioCreado <= VIGENCIA_DESAFIO_MS &&
    nonce.equals(desafio);

  // El desafio se invalida tras cualquier intento firmado.
  desafio[0] = '\0';

  if (!vigente) {
    responderError(401, "desafio_vencido");
    return;
  }

  enlaceUno.write('T');
  Serial.println("[CONTROL] Prueba de alarma enviada al Arduino.");
  enviarFirmado(nonce, "{\"resultado\":\"prueba_enviada\"}");
}

// Gestionar conexion y servidor.
void gestionarWiFi() {
  unsigned long ahora = millis();

  if (WiFi.status() == WL_CONNECTED) {
    if (!servidorActivo) {
      servidor.begin();
      servidorActivo = true;

      Serial.println("[WIFI] Conectado.");
      Serial.print("[WEB] Abrir: http://");
      Serial.println(WiFi.localIP());
    }

    servidor.handleClient();
  } else {
    if (servidorActivo) {
      servidor.stop();
      servidorActivo = false;

      // Dar tiempo antes del siguiente intento.
      ultimoIntentoWiFi = ahora;
      Serial.println("[WIFI] Se perdio la conexion.");
    }

    if (ahora - ultimoIntentoWiFi >= REINTENTO_WIFI_MS) {
      Serial.println("[WIFI] Reintentando conexion...");

      // Cancelar el intento anterior antes de comenzar otro.
      // Los reintentos automaticos estan desactivados.
      WiFi.disconnect();
      delay(100);

      WiFi.begin(NOMBRE_RED, CLAVE_RED);
      ultimoIntentoWiFi = millis();
    }
  }
}

void setup() {
  // Consola USB del ESP32.
  Serial.begin(115200);

  // Puerto independiente para recibir los datos del Uno.
  enlaceUno.begin(
    9600,
    SERIAL_8N1,
    PIN_RX_UNO,
    PIN_TX_UNO
  );

  // Rutas disponibles en el navegador.
  servidor.on("/", HTTP_GET, mostrarPagina);
  servidor.on("/estado", HTTP_GET, mostrarJSON);
  servidor.on("/desafio", HTTP_GET, entregarDesafio);
  servidor.on("/prueba", HTTP_POST, ejecutarPrueba);

  // Cabeceras necesarias para verificar las firmas.
  const char* cabeceras[] = {"X-Nonce", "X-Firma"};
  servidor.collectHeaders(cabeceras, 2);

  claveValida = cargarClave();
  if (!claveValida) {
    Serial.println("[SEGURIDAD] Configura CLAVE_DISPOSITIVO con 64 caracteres hexadecimales.");
  }

  // Conectar a una red existente.
  WiFi.mode(WIFI_STA);

  // Este programa gestiona sus propios reintentos.
  WiFi.setAutoReconnect(false);

  WiFi.begin(NOMBRE_RED, CLAVE_RED);
  ultimoIntentoWiFi = millis();

  Serial.println("[WIFI] Conectando a la red...");
}

void loop() {
  recibirArduino();
  gestionarWiFi();

  unsigned long ahora = millis();

  // Mostrar informacion cada dos segundos.
  if (ahora - ultimoInforme >= 2000UL) {
    ultimoInforme = ahora;

    if (WiFi.status() == WL_CONNECTED) {
      Serial.print("[WIFI] Conectado | IP: ");
      Serial.println(WiFi.localIP());
    } else {
      Serial.println("[WIFI] Sin conexion a la red.");
    }

    if (datosVigentes()) {
      Serial.print("[UNO] Lectura: ");
      Serial.print(lectura);
      Serial.print(" | Estado: ");
      Serial.println(estado);
    } else {
      Serial.println("[UNO] Sin datos recientes.");
    }
  }
}
