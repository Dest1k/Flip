# Flipper Control — Android App

Управление Flipper Zero с телефона через BLE. Красивый тёмный интерфейс, полный доступ к железу.

---

## Архитектура

```
Android App
    │
    ├── FlipperService (Foreground Service)   ← держит соединение в фоне
    │       │
    │       ├── FlipperBleManager             ← BLE GATT: scan / connect / RX/TX
    │       └── FlipperRpcSession             ← Flipper RPC (protobuf framing)
    │
    └── UI (Jetpack Compose)
            ├── DashboardScreen               ← главный экран, плитки фич
            ├── SubGhzScreen                  ← RF scan/capture/replay
            ├── NfcScreen                     ← NFC dump/emulate
            ├── RfidScreen                    ← 125kHz RFID
            ├── IrScreen                      ← универсальный пульт
            ├── BleScreen                     ← BLE spam + scanner
            ├── BadUsbScreen                  ← HID payload editor
            ├── GpioScreen                    ← GPIO/PWM управление
            └── FilesScreen                   ← браузер SD карты
```

## Протокол

Flipper Zero имеет встроенный **RPC сервер** доступный по BLE:

- **Service UUID**: `8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000`
- **TX Char** (phone→flipper): `...fe0000`
- **RX Char** (flipper→phone): `...fe0001`
- **Формат**: `varint(length)` + `PB_Main` (protobuf)

Официальные .proto файлы: https://github.com/flipperdevices/flipperzero-protobuf

---

## Структура проекта

```
app/src/main/
├── java/com/flippercontrol/
│   ├── MainActivity.kt              ← точка входа, NavHost, service binding
│   ├── core/
│   │   ├── FlipperBleManager.kt     ← BLE слой
│   │   ├── FlipperRpcSession.kt     ← RPC + protobuf кодек
│   │   └── FlipperService.kt        ← Foreground Service
│   └── ui/
│       ├── DashboardScreen.kt
│       ├── SubGhzScreen.kt
│       ├── NfcScreen.kt
│       ├── RfidScreen.kt
│       ├── IrScreen.kt
│       ├── BleScreen.kt
│       ├── BadUsbScreen.kt
│       ├── GpioScreen.kt
│       ├── FilesScreen.kt
│       └── Components.kt            ← общие компоненты (TopBar, EmptyState...)
├── AndroidManifest.xml
└── build.gradle.kts
```

---

## Сборка

### Требования
- Android Studio Ladybug (2024.2) или новее
- JDK 17
- Android SDK 35
- Flipper Zero с прошивкой **RogueMaster** (или Unleashed)

### Шаги
```bash
# 1. Создай новый Android проект в Android Studio
#    (Empty Activity, Kotlin, минимальный SDK 26)

# 2. Скопируй файлы в нужные папки согласно структуре выше

# 3. Замени build.gradle.kts своим из архива

# 4. (Опционально) для полного protobuf:
git clone https://github.com/flipperdevices/flipperzero-protobuf
# Сгенерируй Kotlin классы через protoc с kotlin_out
# и замени ProtoWriter/ProtoReader на сгенерированные классы

# 5. Собери и установи
./gradlew installDebug
```

---

## Что работает из коробки

| Фича       | Статус | Примечание |
|------------|--------|------------|
| BLE соединение | ✅ | GATT + notifications |
| Device Info | ✅ | firmware, hw revision, uid |
| Файловый браузер | ✅ | listStorage RPC |
| IR передача | ✅ | irTransmit RPC |
| GPIO HIGH/LOW | ✅ | gpioSetPin RPC |
| Sub-GHz RX | ✅ | subGhzStartReceive |
| NFC read | 🔧 | нужен парсинг NFC payload |
| RFID read | 🔧 | нужен парсинг RFID payload |
| BLE spam | 🔧 | интегрируй с FlipperBleManager |
| Bad USB | 🔧 | storageWrite + appStart RPC |

🔧 = логика готова, нужно доработать парсинг конкретного protobuf payload.
Полные поля: https://github.com/flipperdevices/flipperzero-protobuf

---

## Советы

- **Не убивай сервис**: он держит BLE соединение. Swipe из recents его не убивает (foreground).
- **Логи**: `adb logcat -s FlipperBleManager FlipperRpcSession`
- **Тест без Flipper**: в каждом экране есть mock данные для разработки без железа.
- **Protobuf**: для продакшна обязательно заменить ручной кодек на сгенерированный.
