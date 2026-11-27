#include <HX711.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_gatts_api.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>

const int HX711_DT = 19;
const int HX711_SCK = 18;
const float COUNTS_PER_GRAM = 405.7f;

const char* SERVICE_UUID = "b1e00001-8a20-4c8f-a45e-6f12e3d45c90";
const char* WEIGHT_UUID = "b1e00002-8a20-4c8f-a45e-6f12e3d45c90";
const char* COMMAND_UUID = "b1e00003-8a20-4c8f-a45e-6f12e3d45c90";

const uint8_t TARE_SAMPLE_COUNT = 20;
const uint8_t WEIGHT_SAMPLE_COUNT = 5;
const uint32_t TARE_TIMEOUT_MS = 4000;
const uint32_t WEIGHT_PAUSE_MS = 200;

HX711 scale;
BLEServer* bleServer = nullptr;
BLECharacteristic* weightCharacteristic = nullptr;
BLECharacteristic* commandCharacteristic = nullptr;
BLE2902* commandCccd = nullptr;

struct TareRequest {
  uint16_t id;
  uint16_t connId;
  uint32_t generation;
  uint32_t queuedAtMs;
  bool busy;
};

QueueHandle_t tareQueue = nullptr;
SemaphoreHandle_t connectionLock = nullptr;
esp_gatt_if_t serverGattIf = ESP_GATT_IF_NONE;
bool connected = false;
uint16_t connectionId = 0;
uint32_t connectionGeneration = 0;
bool commandNotificationsEnabled = false;
bool tareReserved = false;

// Only loop() accesses the ADC and these sample accumulators after setup().
TareRequest activeTare{};
int64_t tareSum = 0;
uint8_t tareSamples = 0;
int64_t weightSum = 0;
uint8_t weightSamples = 0;
uint32_t lastWeightSentMs = 0;
uint32_t lastNotReadyLogMs = 0;

void onCommandWrite(const uint8_t* value, uint16_t length, uint16_t connId) {
  if (length < 6 || length > 10 || memcmp(value, "TARE:", 5) != 0) return;
  uint32_t id = 0;
  for (uint16_t i = 5; i < length; ++i) {
    if (value[i] < '0' || value[i] > '9') return;
    id = id * 10 + (value[i] - '0');
  }
  if (id == 0 || id > UINT16_MAX) return;

  xSemaphoreTake(connectionLock, portMAX_DELAY);
  if (connected && connectionId == connId && commandNotificationsEnabled) {
    TareRequest request{static_cast<uint16_t>(id), connId, connectionGeneration, millis(), tareReserved};
    if (xQueueSend(tareQueue, &request, 0) == pdPASS && !request.busy) {
      tareReserved = true;
    }
  }
  xSemaphoreGive(connectionLock);
}

// Capture connection identity and command CCCD writes for connection-specific replies.
void handleGattEvent(esp_gatts_cb_event_t event, esp_gatt_if_t gattsIf, esp_ble_gatts_cb_param_t* param) {
  if (event == ESP_GATTS_WRITE_EVT && commandCharacteristic != nullptr && !param->write.is_prep &&
      param->write.handle == commandCharacteristic->getHandle()) {
    onCommandWrite(param->write.value, param->write.len, param->write.conn_id);
    return;
  }

  xSemaphoreTake(connectionLock, portMAX_DELAY);
  if (event == ESP_GATTS_REG_EVT && param->reg.status == ESP_GATT_OK) {
    serverGattIf = gattsIf;
  } else if (event == ESP_GATTS_CONNECT_EVT) {
    connected = true;
    connectionId = param->connect.conn_id;
    ++connectionGeneration;
    commandNotificationsEnabled = false;
    tareReserved = false;
    xQueueReset(tareQueue);
  } else if (event == ESP_GATTS_DISCONNECT_EVT && connectionId == param->disconnect.conn_id) {
    connected = false;
    ++connectionGeneration;
    commandNotificationsEnabled = false;
    tareReserved = false;
    xQueueReset(tareQueue);
  } else if (event == ESP_GATTS_WRITE_EVT && connected && commandCccd != nullptr &&
             param->write.conn_id == connectionId && param->write.handle == commandCccd->getHandle() &&
             !param->write.is_prep && param->write.len == 2) {
    commandNotificationsEnabled = (param->write.value[0] & 0x01) != 0;
  }
  xSemaphoreGive(connectionLock);
}

// Callers hold connectionLock while checking the connection and enqueueing a reply.
bool isCurrentConnection(const TareRequest& request) {
  return connected && request.connId == connectionId && request.generation == connectionGeneration;
}

void notifyTareResult(const TareRequest& request, const char* error) {
  if (!commandNotificationsEnabled || serverGattIf == ESP_GATT_IF_NONE) return;
  char response[24];
  const unsigned int id = request.id;
  const int length = error == nullptr
    ? snprintf(response, sizeof(response), "OK:%u", id)
    : snprintf(response, sizeof(response), "ERR:%u:%s", id, error);
  esp_err_t status = esp_ble_gatts_send_indicate(
    serverGattIf, request.connId, commandCharacteristic->getHandle(), length,
    reinterpret_cast<uint8_t*>(response), false
  );
  if (status != ESP_OK) Serial.printf("Tare reply failed (%d)\n", status);
}

bool finishTare(const char* error) {
  bool updated = false;
  xSemaphoreTake(connectionLock, portMAX_DELAY);
  if (isCurrentConnection(activeTare)) {
    if (error == nullptr) {
      scale.set_offset(static_cast<long>(tareSum / TARE_SAMPLE_COUNT));
      updated = true;
    }
    tareReserved = false;
    notifyTareResult(activeTare, error);
  }
  xSemaphoreGive(connectionLock);
  activeTare = {};
  return updated;
}

void processTareRequests() {
  if (commandCharacteristic == nullptr) return;
  xSemaphoreTake(connectionLock, portMAX_DELAY);
  if (activeTare.id != 0 && !isCurrentConnection(activeTare)) activeTare = {};
  TareRequest request{};
  while (xQueueReceive(tareQueue, &request, 0) == pdPASS) {
    if (!isCurrentConnection(request)) continue;
    if (request.busy) {
      notifyTareResult(request, "BUSY");
    } else {
      activeTare = request;
      tareSum = 0;
      tareSamples = 0;
    }
  }
  xSemaphoreGive(connectionLock);
  if (activeTare.id != 0 && millis() - activeTare.queuedAtMs >= TARE_TIMEOUT_MS) {
    finishTare("TIMEOUT");
  }
}

void setup() {
  Serial.begin(115200);

  scale.begin(HX711_DT, HX711_SCK);
  scale.set_scale(COUNTS_PER_GRAM);

  Serial.println("Keep scale empty. Taring...");
  delay(2000);
  while (!scale.wait_ready_timeout(1000)) {
    Serial.println("HX711 not ready");
  }
  scale.tare(TARE_SAMPLE_COUNT);

  BLEDevice::init("BrewLab-Scale");

  tareQueue = xQueueCreate(8, sizeof(TareRequest));
  connectionLock = xSemaphoreCreateMutex();
  if (tareQueue != nullptr && connectionLock != nullptr) {
    BLEDevice::setCustomGattsHandler(handleGattEvent);
  } else {
    Serial.println("Remote tare queue unavailable");
  }

  bleServer = BLEDevice::createServer();
  bleServer->advertiseOnDisconnect(true);
  BLEService* service = bleServer->createService(SERVICE_UUID);
  weightCharacteristic = service->createCharacteristic(
    WEIGHT_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  weightCharacteristic->addDescriptor(new BLE2902());

  if (tareQueue != nullptr && connectionLock != nullptr) {
    commandCharacteristic = service->createCharacteristic(
      COMMAND_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY
    );
    commandCccd = new BLE2902();
    commandCharacteristic->addDescriptor(commandCccd);
  }

  service->start();
  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("BLE ready: BrewLab-Scale");
}

void publishWeight() {
  const long average = static_cast<long>(weightSum / WEIGHT_SAMPLE_COUNT);
  const float grams = static_cast<float>(average - scale.get_offset()) / COUNTS_PER_GRAM;
  String value = String(grams, 1);
  weightCharacteristic->setValue(value);
  const bool hasClient = bleServer->getConnectedCount() > 0;
  if (hasClient) weightCharacteristic->notify();
  Serial.print("Weight: ");
  Serial.print(value);
  Serial.print(" g | BLE: ");
  Serial.println(hasClient ? "connected" : "waiting");
  lastWeightSentMs = millis();
  weightSum = 0;
  weightSamples = 0;
}

void loop() {
  processTareRequests();
  const uint32_t now = millis();
  const bool sampleWeight = now - lastWeightSentMs >= WEIGHT_PAUSE_MS;
  if (activeTare.id != 0 || sampleWeight) {
    if (scale.is_ready()) {
      const long raw = scale.read();
      bool offsetUpdated = false;
      if (activeTare.id != 0) {
        tareSum += raw;
        if (++tareSamples == TARE_SAMPLE_COUNT) {
          const bool expired = millis() - activeTare.queuedAtMs >= TARE_TIMEOUT_MS;
          offsetUpdated = finishTare(expired ? "TIMEOUT" : nullptr);
        }
      }
      if (offsetUpdated) {
        // Publish the next average using only readings obtained after the offset changed.
        weightSum = 0;
        weightSamples = 0;
      } else if (sampleWeight) {
        weightSum += raw;
        if (++weightSamples == WEIGHT_SAMPLE_COUNT) publishWeight();
      }
    } else if (now - lastWeightSentMs >= 1000 && now - lastNotReadyLogMs >= 1000) {
      lastNotReadyLogMs = now;
      Serial.println("HX711 not ready");
    }
  }
  delay(2);
}
