#include <HX711.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

const int HX711_DT = 19;
const int HX711_SCK = 18;
const float COUNTS_PER_GRAM = 397.2f;

const char* SERVICE_UUID =
  "b1e00001-8a20-4c8f-a45e-6f12e3d45c90";

const char* WEIGHT_UUID =
  "b1e00002-8a20-4c8f-a45e-6f12e3d45c90";

HX711 scale;

BLEServer* bleServer = nullptr;
BLECharacteristic* weightCharacteristic = nullptr;

void setup() {
  Serial.begin(115200);

  // Load cell
  scale.begin(HX711_DT, HX711_SCK);
  scale.set_scale(COUNTS_PER_GRAM);

  Serial.println("Keep scale empty. Taring...");
  delay(2000);

  while (!scale.wait_ready_timeout(1000)) {
    Serial.println("HX711 not ready");
  }

  scale.tare(20);

  // Bluetooth LE
  BLEDevice::init("BrewLab-Scale");

  bleServer = BLEDevice::createServer();
  bleServer->advertiseOnDisconnect(true);

  BLEService* service =
    bleServer->createService(SERVICE_UUID);

  weightCharacteristic = service->createCharacteristic(
    WEIGHT_UUID,
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_NOTIFY
  );

  weightCharacteristic->addDescriptor(new BLE2902());

  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);

  BLEDevice::startAdvertising();

  Serial.println("BLE ready: BrewLab-Scale");
}

void loop() {
  if (scale.wait_ready_timeout(1000)) {
    float grams = scale.get_units(5);
    String value = String(grams, 1);

    // Send the real measured weight as text, in grams.
    weightCharacteristic->setValue(value);

    bool connected = bleServer->getConnectedCount() > 0;

    if (connected) {
      weightCharacteristic->notify();
    }

    Serial.print("Weight: ");
    Serial.print(value);
    Serial.print(" g | BLE: ");
    Serial.println(connected ? "connected" : "waiting");
  } else {
    Serial.println("HX711 not ready");
  }

  delay(200);
}