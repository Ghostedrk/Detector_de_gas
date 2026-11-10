/*
  Detector de gas educativo — Arduino Uno

  CONEXIONES:
  Sensor AO  -> A0
  Sensor VCC -> 5V
  Sensor GND -> GND
  LED verde  -> D4, con resistencia de 220 ohmios
  LED rojo   -> D5, con resistencia de 220 ohmios
  Buzzer     -> control desde D8

  D1/TX      -> divisor resistivo -> GPIO16/RX2 del ESP32 (lecturas)
  D0/RX      <- GPIO17/TX2 del ESP32 (orden de prueba "T" desde la app)
  Desconectar el cable de D0 mientras se carga este programa.

  Monitor serie: 9600 baudios.
  Enviar T o t (desde el Monitor serie o desde la app) para probar las salidas.

  Los valores son lecturas ADC, no temperatura ni ppm.
*/

const byte PIN_SENSOR = A0;
const byte LED_VERDE = 4;
const byte LED_ROJO = 5;
const byte PIN_BUZZER = 8;

// false: buzzer pasivo, como en el codigo anterior.
// true: buzzer activo con controlador activado por HIGH.
const bool BUZZER_ACTIVO = false;

// Umbrales provisionales, no calibrados.
const int UMBRAL_ALERTA = 500;
const int UMBRAL_RETORNO = 450;

const unsigned long ESPERA_INICIAL_MS = 5000UL;
const unsigned long INTERVALO_LECTURA_MS = 250UL;
const unsigned long DURACION_PRUEBA_MS = 3000UL;
const unsigned long DURACION_PITIDO_MS = 150UL;

bool alerta = false;
bool prueba = false;
bool pitidoEncendido = false;

unsigned long inicio = 0;
unsigned long inicioPrueba = 0;
unsigned long inicioPitido = 0;
unsigned long ultimaLectura = 0;

// Iniciar un pitido breve sin detener el programa.
void iniciarPitido() {
  inicioPitido = millis();
  pitidoEncendido = true;

  if (BUZZER_ACTIVO) {
    digitalWrite(PIN_BUZZER, HIGH);
  } else {
    tone(PIN_BUZZER, 2000, DURACION_PITIDO_MS);
  }
}

void apagarBuzzer() {
  noTone(PIN_BUZZER);
  digitalWrite(PIN_BUZZER, LOW);
  pitidoEncendido = false;
}

void setup() {
  pinMode(LED_VERDE, OUTPUT);
  pinMode(LED_ROJO, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);

  digitalWrite(LED_VERDE, LOW);
  digitalWrite(LED_ROJO, LOW);
  digitalWrite(PIN_BUZZER, LOW);

  Serial.begin(9600);
  inicio = millis();
}

void loop() {
  unsigned long ahora = millis();

  // Apagar el pitido cuando se cumplan los 150 ms.
  // Se revisa en cada vuelta, sin esperar otra lectura.
  if (pitidoEncendido &&
      ahora - inicioPitido >= DURACION_PITIDO_MS) {
    apagarBuzzer();
  }

  // Prueba manual desde el Monitor serie del Arduino.
  while (Serial.available() > 0) {
    char comando = Serial.read();

    if (comando == 't' || comando == 'T') {
      prueba = true;
      inicioPrueba = millis();

      // Un pitido por comando.
      // Si hay alerta del sensor, su sonido tiene prioridad.
      if (!alerta) {
        iniciarPitido();
      }
    }
  }

  ahora = millis();

  // El estado de prueba dura tres segundos.
  if (prueba &&
      ahora - inicioPrueba >= DURACION_PRUEBA_MS) {
    prueba = false;
  }

  bool calentando = ahora - inicio < ESPERA_INICIAL_MS;

  // Leer y transmitir cada 250 ms.
  if (ahora - ultimaLectura >= INTERVALO_LECTURA_MS) {
    ultimaLectura = ahora;

    int lectura = analogRead(PIN_SENSOR);

    // La prueba manual no modifica esta lectura.
    if (calentando) {
      alerta = false;
    } else if (lectura >= UMBRAL_ALERTA) {
      alerta = true;
    } else if (lectura <= UMBRAL_RETORNO) {
      alerta = false;
    }

    // Repetir pitidos mientras haya una alerta del sensor.
    if (alerta) {
      iniciarPitido();
    }

    // Mantener el formato que recibe el ESP32.
    const char* estado;

    if (alerta) {
      estado = "alerta";
    } else if (prueba) {
      estado = "prueba";
    } else if (calentando) {
      estado = "calentando";
    } else {
      estado = "sin_alerta";
    }

    Serial.print(lectura);
    Serial.print(',');
    Serial.println(estado);
  }

  // Actualizar los LED.
  bool activarAlarma = alerta || prueba;

  digitalWrite(LED_ROJO, activarAlarma ? HIGH : LOW);

  // Verde parpadeando durante la espera inicial.
  // Verde fijo cuando no existe alerta ni prueba.
  bool verdeEncendido =
    !activarAlarma &&
    (!calentando || ((ahora / 500UL) % 2 == 0));

  digitalWrite(LED_VERDE, verdeEncendido ? HIGH : LOW);
}