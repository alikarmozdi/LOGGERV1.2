/*
  NavIMU_BLE  —  firmware for TTGO T-Display (ESP32) + ICM-45686 + QMC6309
  -----------------------------------------------------------------------
  - Polls the ICM-45686 (gyro + accel) at 500 Hz and averages every 5 polls
    -> 100 Hz output (simple anti-alias filter, lower noise).
  - Reads the QMC6309 magnetometer (sensor ODR 200 Hz) once per output sample.
  - Every sample gets an ESP32 timestamp in microseconds.
  - Sends samples over BLE notifications in batches (default 5 samples / packet
    = 20 packets per second, 120 bytes each).
  - Restarts advertising automatically after any disconnect.

  Arduino IDE: Board = "ESP32 Dev Module" (or "LilyGo T-Display").
  Works with the Espressif "esp32" board package 2.x and 3.x (Bluedroid BLE).
  No extra libraries are needed.

  BLE device name : NAV-IMU
  Service UUID    : a8b10001-6d3e-4c27-9f3a-1c2d3e4f5a60
  Data char (notify): a8b10002-6d3e-4c27-9f3a-1c2d3e4f5a60

  Packet format (little-endian):
    byte 0     : 0xA5 (magic)
    byte 1     : type  (0x01 = samples, 0x02 = status)
    bytes 2..3 : uint16 packet sequence number
    byte 4     : n = number of samples (type 0x01), 0 for status
    type 0x01, then n x 23 bytes:
      uint32 t_us            ESP32 time of the sample (micros, wraps after ~71 min)
      int16  gx, gy, gz      gyro,  LSB  (+-500 dps  -> 65.536 LSB per deg/s)
      int16  ax, ay, az      accel, LSB  (+-8 g      -> 4096 LSB per g)
      int16  mx, my, mz      mag,   LSB  (+-8 Gauss  -> 4000 LSB per Gauss)
      uint8  flags           bit0 = new mag sample, bit1 = mag overflow,
                             bit2 = mag valid
    type 0x02 (status, once per second), 13 bytes after the header:
      uint8  imuOk, uint8 magOk, uint16 i2cErrors, uint16 queueDrops,
      uint16 battery_mV, uint32 uptime_ms, uint8 fwVersion
*/

#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ======================= user settings =======================
#define I2C_SDA        21        // TTGO T-Display header pin 21
#define I2C_SCL        22        // TTGO T-Display header pin 22
#define I2C_HZ         400000
#define DEVICE_NAME    "NAV-IMU"
#define OUT_HZ         100       // output samples per second
#define OVERSAMPLE     5         // IMU polls averaged per output sample
#define MAX_PER_PACKET 5         // samples per BLE packet (if MTU allows)
#define BAT_ADC_PIN    34        // TTGO T-Display battery divider
#define BAT_EN_PIN     14        // TTGO T-Display ADC enable
// =============================================================

#define FW_VERSION 1
#define POLL_HZ (OUT_HZ * OVERSAMPLE)
static_assert(1000 % POLL_HZ == 0, "OUT_HZ*OVERSAMPLE must divide 1000 (e.g. 100*5, 100*10, 200*5)");
#define POLL_MS (1000 / POLL_HZ)

#define SERVICE_UUID "a8b10001-6d3e-4c27-9f3a-1c2d3e4f5a60"
#define DATA_UUID    "a8b10002-6d3e-4c27-9f3a-1c2d3e4f5a60"

// ---------------- ICM-45686 registers ----------------
#define ICM_REG_ACCEL_DATA_X 0x00   // 12 bytes: accel XYZ then gyro XYZ, big-endian
#define ICM_REG_PWR_MGMT0    0x10
#define ICM_REG_INT_STATUS   0x19
#define ICM_REG_ACCEL_CONFIG0 0x1B  // FS [6:4], ODR [3:0]
#define ICM_REG_GYRO_CONFIG0 0x1C   // FS [7:4], ODR [3:0]
#define ICM_REG_WHOAMI       0x72
#define ICM_REG_MISC2        0x7F   // bit1 = soft reset
#define ICM_ACCEL_FS_8G      2
#define ICM_GYRO_FS_500DPS   3
#define ICM_ODR_800HZ        6

// ---------------- QMC6309 registers ----------------
#define QMC_ADDR     0x7C
#define QMC_REG_ID   0x00   // = 0x90
#define QMC_REG_DATA 0x01   // X LSB, X MSB, Y LSB, Y MSB, Z LSB, Z MSB
#define QMC_REG_STAT 0x09   // bit0 DRDY, bit1 OVFL
#define QMC_REG_CTL1 0x0A
#define QMC_REG_CTL2 0x0B

struct __attribute__((packed)) Sample {
  uint32_t t_us;
  int16_t g[3];
  int16_t a[3];
  int16_t m[3];
  uint8_t flags;
};
static_assert(sizeof(Sample) == 23, "Sample must be 23 bytes");

static uint8_t icmAddr = 0x68;
static volatile bool imuOk = false;
static volatile bool magOk = false;
static volatile uint16_t i2cErrors = 0;
static volatile uint16_t queueDrops = 0;
static volatile uint32_t samplesMade = 0;

static QueueHandle_t sampleQueue;
static BLEServer *server = nullptr;
static BLECharacteristic *dataChar = nullptr;
static volatile bool connected = false;
static volatile bool restartAdvertising = false;
static uint32_t disconnectedAtMs = 0;
static uint16_t packetSeq = 0;

// ---------------- I2C helpers ----------------
static bool i2cWrite(uint8_t addr, uint8_t reg, uint8_t val) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(val);
  return Wire.endTransmission() == 0;
}

static bool i2cRead(uint8_t addr, uint8_t reg, uint8_t *buf, uint8_t len) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  int got = Wire.requestFrom((int)addr, (int)len);
  if (got != (int)len) {
    while (Wire.available()) Wire.read();
    return false;
  }
  for (uint8_t i = 0; i < len; i++) buf[i] = (uint8_t)Wire.read();
  return true;
}

// ---------------- sensors ----------------
static bool initIcm() {
  const uint8_t addrs[2] = {0x68, 0x69};
  for (int k = 0; k < 2; k++) {
    uint8_t id = 0;
    if (!i2cRead(addrs[k], ICM_REG_WHOAMI, &id, 1)) continue;
    if (id == 0x00 || id == 0xFF) continue;
    icmAddr = addrs[k];
    Serial.printf("ICM found at 0x%02X, WHO_AM_I=0x%02X %s\n", icmAddr, id,
                  id == 0xE9 ? "(ICM-45686)" : "(not 0xE9, trying anyway)");
    i2cWrite(icmAddr, ICM_REG_MISC2, 0x02);           // soft reset
    delay(5);
    uint8_t st = 0;
    i2cRead(icmAddr, ICM_REG_INT_STATUS, &st, 1);     // read clears reset-done flag
    bool ok = true;
    ok &= i2cWrite(icmAddr, ICM_REG_GYRO_CONFIG0, (ICM_GYRO_FS_500DPS << 4) | ICM_ODR_800HZ);
    ok &= i2cWrite(icmAddr, ICM_REG_ACCEL_CONFIG0, (ICM_ACCEL_FS_8G << 4) | ICM_ODR_800HZ);
    ok &= i2cWrite(icmAddr, ICM_REG_PWR_MGMT0, 0x0F); // gyro LN + accel LN
    delay(80);                                        // gyro start-up
    if (ok) return true;
  }
  return false;
}

static bool initMag() {
  uint8_t id = 0;
  if (!i2cRead(QMC_ADDR, QMC_REG_ID, &id, 1)) return false;
  Serial.printf("QMC6309 chip id=0x%02X %s\n", id, id == 0x90 ? "(ok)" : "(unexpected, trying anyway)");
  i2cWrite(QMC_ADDR, QMC_REG_CTL2, 0x80);   // soft reset
  delay(5);
  i2cWrite(QMC_ADDR, QMC_REG_CTL2, 0x00);
  delay(10);
  bool ok = true;
  ok &= i2cWrite(QMC_ADDR, QMC_REG_CTL1, 0x00);  // suspend before mode change
  ok &= i2cWrite(QMC_ADDR, QMC_REG_CTL2, 0x48);  // ODR 200 Hz, +-8 G, set/reset on
  ok &= i2cWrite(QMC_ADDR, QMC_REG_CTL1, 0x61);  // OSR2=8, OSR1=8, normal mode
  delay(10);
  return ok;
}

static bool readIcm(int16_t out[6]) {   // accel xyz, gyro xyz
  uint8_t b[12];
  if (!i2cRead(icmAddr, ICM_REG_ACCEL_DATA_X, b, 12)) return false;
  for (int i = 0; i < 6; i++) out[i] = (int16_t)((b[2 * i] << 8) | b[2 * i + 1]);
  return true;
}

static void sensorTask(void *) {
  int32_t sum[6] = {0, 0, 0, 0, 0, 0};
  int64_t tSum = 0;
  int nGood = 0, nPoll = 0, badInRow = 0;
  int16_t lastMag[3] = {0, 0, 0};
  uint32_t lastInitTry = 0;
  TickType_t last = xTaskGetTickCount();

  for (;;) {
    vTaskDelayUntil(&last, pdMS_TO_TICKS(POLL_MS));

    if (!imuOk) {
      uint32_t now = millis();
      if (now - lastInitTry > 1000) {
        lastInitTry = now;
        imuOk = initIcm();
        if (!magOk) magOk = initMag();
        last = xTaskGetTickCount();
      }
      continue;
    }

    int16_t r[6];
    if (readIcm(r)) {
      for (int i = 0; i < 6; i++) sum[i] += r[i];
      tSum += esp_timer_get_time();
      nGood++;
      badInRow = 0;
    } else {
      i2cErrors++;
      if (++badInRow > 50) { imuOk = false; badInRow = 0; Serial.println("IMU lost, re-init"); }
    }

    if (++nPoll < OVERSAMPLE) continue;
    nPoll = 0;
    if (nGood == 0) { tSum = 0; continue; }

    Sample s;
    s.t_us = (uint32_t)(tSum / nGood);
    for (int i = 0; i < 3; i++) {
      s.a[i] = (int16_t)lroundf((float)sum[i] / nGood);
      s.g[i] = (int16_t)lroundf((float)sum[3 + i] / nGood);
    }
    s.flags = 0;
    if (magOk) {
      uint8_t st = 0;
      if (i2cRead(QMC_ADDR, QMC_REG_STAT, &st, 1)) {
        if (st & 0x01) {
          uint8_t mb[6];
          if (i2cRead(QMC_ADDR, QMC_REG_DATA, mb, 6)) {
            for (int i = 0; i < 3; i++) lastMag[i] = (int16_t)(mb[2 * i] | (mb[2 * i + 1] << 8));
            s.flags |= 0x01;
          } else {
            i2cErrors++;
          }
        }
        if (st & 0x02) s.flags |= 0x02;
        s.flags |= 0x04;
      } else {
        i2cErrors++;
      }
    }
    for (int i = 0; i < 3; i++) s.m[i] = lastMag[i];

    if (xQueueSend(sampleQueue, &s, 0) != pdTRUE) queueDrops++;
    samplesMade++;
    for (int i = 0; i < 6; i++) sum[i] = 0;
    tSum = 0;
    nGood = 0;
  }
}

// ---------------- BLE ----------------
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    connected = true;
    Serial.println("BLE connected");
  }
  void onDisconnect(BLEServer *s) override {
    connected = false;
    restartAdvertising = true;
    disconnectedAtMs = millis();
    Serial.println("BLE disconnected");
  }
};

// Note: a full packet is 5 + 5*23 = 120 bytes, so the phone must negotiate an
// MTU >= 123. The NavLogger app requests MTU 247 before it subscribes to
// notifications, so packets are never sent with the default 23-byte MTU.

static void sendSamples(Sample *batch, int n) {
  uint8_t buf[5 + MAX_PER_PACKET * sizeof(Sample)];
  buf[0] = 0xA5;
  buf[1] = 0x01;
  buf[2] = packetSeq & 0xFF;
  buf[3] = packetSeq >> 8;
  buf[4] = (uint8_t)n;
  memcpy(buf + 5, batch, n * sizeof(Sample));
  dataChar->setValue(buf, 5 + n * sizeof(Sample));
  dataChar->notify();
  packetSeq++;
}

static void sendStatus() {
  uint8_t buf[18];
  uint16_t bat = 0;
  uint32_t up = millis();
  bat = (uint16_t)(analogReadMilliVolts(BAT_ADC_PIN) * 2);
  buf[0] = 0xA5;
  buf[1] = 0x02;
  buf[2] = packetSeq & 0xFF;
  buf[3] = packetSeq >> 8;
  buf[4] = 0;
  buf[5] = imuOk ? 1 : 0;
  buf[6] = magOk ? 1 : 0;
  uint16_t e = i2cErrors, d = queueDrops;
  memcpy(buf + 7, &e, 2);
  memcpy(buf + 9, &d, 2);
  memcpy(buf + 11, &bat, 2);
  memcpy(buf + 13, &up, 4);
  buf[17] = FW_VERSION;
  dataChar->setValue(buf, sizeof(buf));
  dataChar->notify();
  packetSeq++;
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\nNavIMU_BLE starting");

  pinMode(BAT_EN_PIN, OUTPUT);
  digitalWrite(BAT_EN_PIN, HIGH);

  Wire.begin(I2C_SDA, I2C_SCL, I2C_HZ);
  Wire.setTimeOut(20);
  imuOk = initIcm();
  magOk = initMag();
  Serial.printf("IMU %s, MAG %s\n", imuOk ? "OK" : "NOT FOUND", magOk ? "OK" : "NOT FOUND");

  sampleQueue = xQueueCreate(128, sizeof(Sample));

  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(247);
  BLEDevice::setPower(ESP_PWR_LVL_P9);
  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService *service = server->createService(SERVICE_UUID);
  dataChar = service->createCharacteristic(DATA_UUID, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
  dataChar->addDescriptor(new BLE2902());
  service->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("Advertising as " DEVICE_NAME);

  xTaskCreatePinnedToCore(sensorTask, "sensors", 4096, nullptr, 5, nullptr, 1);
}

void loop() {
  static Sample batch[MAX_PER_PACKET];
  static int nb = 0;
  static uint32_t lastStatus = 0, lastPrint = 0, lastCount = 0;

  Sample s;
  while (xQueueReceive(sampleQueue, &s, pdMS_TO_TICKS(5)) == pdTRUE) {
    if (!connected) { nb = 0; continue; }
    batch[nb++] = s;
    if (nb >= MAX_PER_PACKET) { sendSamples(batch, nb); nb = 0; }
  }

  uint32_t now = millis();
  if (restartAdvertising && now - disconnectedAtMs > 300) {
    restartAdvertising = false;
    BLEDevice::startAdvertising();
    Serial.println("Advertising restarted");
  }
  if (connected && now - lastStatus >= 1000) {
    lastStatus = now;
    sendStatus();
  }
  if (now - lastPrint >= 1000) {
    uint32_t made = samplesMade;
    Serial.printf("rate=%lu Hz  ble=%s  i2cErr=%u  drops=%u\n",
                  (unsigned long)(made - lastCount), connected ? "on" : "off",
                  (unsigned)i2cErrors, (unsigned)queueDrops);
    lastCount = made;
    lastPrint = now;
  }
}
