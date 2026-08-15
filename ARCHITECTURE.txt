I2P Messenger — архитектура и протокол

Версия документа: 1.0
Текущий транспорт: I2P SAM / STREAM
Платформа: Android
Язык: Kotlin
UI: XML + Material Design
База данных: Room / SQLite


---

1. Цель проекта

I2P Messenger — децентрализованный мессенджер для обмена сообщениями между пользователями через сеть I2P.

Основные свойства:

нет центрального сервера сообщений;

адреса пользователей представлены I2P Destination / Base32;

соединение устанавливается через локальный SAM API i2pd;

приложение работает поверх STREAM-транспорта;

прикладной протокол отделён от транспортного уровня;

состояние контактов и сообщений хранится локально;

UI не должен напрямую зависеть от SAM и сетевых сокетов.


В будущем архитектура должна позволять добавить:

файлы;

изображения;

голосовые сообщения;

звонки;

группы;

дополнительные типы уведомлений;

более компактный бинарный формат протокола.



---

2. Главный принцип архитектуры

Проект разделён на уровни.

┌──────────────────────────────────────────┐
│                    UI                    │
│ MainActivity / ChatActivity / Adapters  │
└─────────────────────┬────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────┐
│              Application logic            │
│ Chat / Presence / Contact managers        │
└─────────────────────┬────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────┐
│              Protocol layer               │
│ Packet / Encoder / Decoder / Message IDs │
└─────────────────────┬────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────┐
│              Transport layer              │
│ I2P / STREAM / SamConnection              │
└─────────────────────┬────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────┐
│                  i2pd                    │
│                SAM 3.0                   │
└──────────────────────────────────────────┘

Отдельно существует слой хранения:

Room
 │
 ├── Contacts
 ├── Messages
 ├── Presence
 └── Files metadata

Главное правило:

> UI не знает о SAM. SAM не знает о сообщениях чата.




---

3. Транспортный уровень

3.1. SamConnection

SamConnection — низкоуровневый клиент SAM.

Он отвечает только за взаимодействие с i2pd.

Он должен уметь:

connect()
disconnect()

HELLO
NAMING LOOKUP

SESSION CREATE
SESSION STATUS
SESSION REMOVE

STREAM CONNECT
STREAM ACCEPT
STREAM CLOSE

Но SamConnection не должен знать, что такое:

CHAT_MESSAGE
PRESENCE
MESSAGE_READ
FILE

Это задача протокола.


---

4. SAM control connection

Необходимо разделять два понятия:

Контрольное соединение

TCP:

Android
   ↓
127.0.0.1:7656
   ↓
i2pd SAM

Через него идут команды:

HELLO
SESSION CREATE
SESSION STATUS
NAMING LOOKUP
SESSION REMOVE
STREAM CONNECT

STREAM-соединения

Отдельные соединения, используемые непосредственно для обмена данными между I2P Destination.


---

5. Правило контрольного сокета

Контрольный SAM-сокет не должен использоваться одновременно несколькими потоками без синхронизации.

Плохой вариант:

Thread A → NAMING LOOKUP
Thread B → SESSION CREATE
Thread C → reconnect

Все три работают с одним BufferedReader.

Это может привести к:

Broken pipe
SAM <- null
Bad file descriptor

Поэтому команды контрольного соединения должны выполняться последовательно.

Концептуально:

SamConnection
                   │
              command queue
                   │
        ┌──────────┼──────────┐
        ▼          ▼          ▼
     LOOKUP      CREATE      STATUS

Одновременно выполняется только одна команда, требующая чтения ответа от SAM.


---

6. Жизненный цикл SAM-сессии

Нормальный сценарий:

connect()
   ↓
HELLO
   ↓
SESSION CREATE
   ↓
SESSION STATUS
   ↓
READY

После этого существующая SAM-сессия должна переиспользоваться.

Не нужно делать:

message
 ↓
SESSION CREATE
 ↓
send
 ↓
SESSION REMOVE

message
 ↓
SESSION CREATE
 ↓
send

SAM-сессия — долгоживущий ресурс.


---

7. Автоматическое восстановление

При повреждении контрольного соединения:

ERROR
 ↓
закрыть старый socket
 ↓
создать новый SamConnection
 ↓
HELLO
 ↓
SESSION CREATE
 ↓
SESSION STATUS
 ↓
READY

Важно:

> Не пытаться использовать старые закрытые Socket, Reader или Writer.



Старое соединение полностью уничтожается.


---

8. I2PManager

I2PManager — более высокий уровень над SamConnection.

Он отвечает за:

состояние подключения;

наличие SAM-сессии;

отправку сообщений;

получение сообщений;

регистрацию обработчиков;

управление жизненным циклом сетевого слоя.


Примерно:

I2PManager
    │
    └── SamConnection
          │
          └── SAM

I2PManager не должен разбирать содержимое пользовательского сообщения.


---

9. Приём входящих данных

Входящие данные проходят через единый конвейер.

I2P STREAM
     ↓
SamConnection
     ↓
I2PManager
     ↓
ProtocolDecoder
     ↓
ProtocolPacket
     ↓
Application layer

Например:

CHAT_MESSAGE
     ↓
ChatManager
     ↓
Room
     ↓
Flow
     ↓
ChatActivity

А:

PRESENCE
     ↓
PresenceManager
     ↓
ContactRepository
     ↓
Room
     ↓
Flow
     ↓
UI


---

10. Приоритет обработки системных сообщений

Очень важно:

PRESENCE не должен попадать в чат как обычный текст.

Поэтому прикладной протокол должен сначала определить тип пакета.

Например:

incoming packet
       ↓
ProtocolDecoder
       ↓
packet.type
       │
       ├── CHAT_MESSAGE → ChatManager
       ├── PRESENCE → PresenceManager
       ├── MESSAGE_READ → ReceiptManager
       └── PING → NetworkManager

Таким образом больше не нужен подход:

if (message.startsWith("PRESENCE|"))

который был временным решением.


---

11. Прикладной протокол

Планируется собственный протокол версии 1.

Основная идея:

┌─────────┬──────┬──────────────┬──────────────┐
│ version │ type │ identifier   │ payload      │
└─────────┴──────┴──────────────┴──────────────┘

Точный бинарный формат может быть реализован позже.

На первом этапе допускается сериализация через JSON внутри пакета, если это ускоряет разработку.

Архитектура при этом должна быть рассчитана на последующую замену JSON на бинарный формат.


---

12. Версия протокола

Каждый пакет должен иметь:

protocolVersion

Например:

1

Это позволит в будущем сделать:

version 2
version 3

и поддерживать совместимость.


---

13. Типы пакетов

CHAT

Диапазон:

0x01 – 0x0F

0x01 CHAT_MESSAGE

Обычное сообщение.

Поля:

messageId
timestamp
text

Пример:

{
    "version": 1,
    "type": "CHAT_MESSAGE",
    "messageId": "...",
    "timestamp": 1786681115655,
    "text": "Привет"
}


---

0x02 MESSAGE_DELIVERED

Подтверждение получения сообщения устройством.

messageId
timestamp

Логика:

SENT
 ↓
DELIVERED


---

0x03 MESSAGE_READ

Подтверждение прочтения.

messageId
timestamp

Логика:

DELIVERED
 ↓
READ


---

14. Presence

Диапазон:

0x10 – 0x1F

0x10 PRESENCE

Используется для heartbeat.

Поля:

status
timestamp

Например:

ONLINE
1786681115655

OFFLINE не обязательно передавать.

Клиент может вычислять:

currentTime - lastSeen > timeout

→ OFFLINE.


---

15. PING / PONG

0x11 PING

Проверка доступности.

requestId
timestamp

0x12 PONG

Ответ:

requestId
timestamp

PING/PONG не является пользовательским сообщением.


---

16. Служебные сообщения

Диапазон:

0x20 – 0x2F

0x20 HELLO

Начальная информация о клиенте/протоколе.

Например:

protocolVersion
supportedFeatures

В будущем:

files
calls
groups

могут передаваться как возможности.


---

0x21 ERROR

Ошибка протокола.

Поля:

code
message
requestId


---

17. Зарезервированные диапазоны

0x01 – 0x0F   CHAT
0x10 – 0x1F   PRESENCE
0x20 – 0x2F   SERVICE
0x30 – 0x3F   FILES
0x40 – 0x4F   CALLS

Это не означает, что все типы внутри диапазонов нужно реализовывать сейчас.

Диапазоны просто резервируются заранее.


---

18. Message ID

Каждое сообщение должно иметь уникальный messageId.

Например:

550e8400-e29b-41d4-a716-446655440000

Идентификатор используется для:

дедупликации;

подтверждения доставки;

подтверждения прочтения;

поиска сообщения;

повторной отправки;

синхронизации состояния.


Очень важно:

> Повторное получение одного messageId не должно создавать второе сообщение в Room.



Обработка должна быть идемпотентной.


---

19. Состояние сообщения

Минимальная модель:

SENDING
   ↓
SENT
   ↓
DELIVERED
   ↓
READ

Например:

SENDING

Сообщение создано локально.

SENT

Передано транспортному уровню.

DELIVERED

Удалённый клиент подтвердил получение.

READ

Удалённый пользователь подтвердил прочтение.


---

20. Room

Room отвечает за локальное состояние.

Основные сущности:

ContactEntity
MessageEntity
PresenceEntity

Возможно, presence можно хранить непосредственно в ContactEntity, если отдельная таблица не нужна.


---

21. MessageEntity

Примерная структура:

id
conversationId
senderAddress
receiverAddress
text
timestamp
status
messageType

В будущем:

attachmentId
replyToMessageId
edited
deleted


---

22. ContactEntity

Пример:

id
addressBase32
publicKeyBase64
displayName
lastSeen
presenceStatus

Base32 используется как удобный пользовательский идентификатор.

Base64 Destination/key используется внутри сетевого слоя там, где он необходим.


---

23. Поток обновления UI

UI не должен самостоятельно спрашивать базу после каждого события.

Правильная схема:

PresenceManager
      ↓
ContactRepository
      ↓
Room
      ↓
Flow<List<Contact>>
      ↓
MainActivity
      ↓
ContactAdapter

Для чата:

Room
 ↓
Flow<List<Message>>
 ↓
ChatActivity
 ↓
Adapter

Room является источником истины для UI.


---

24. PresenceManager

PresenceManager отвечает за:

heartbeat;

обработку PRESENCE;

обновление lastSeen;

определение online/offline;

связь с ContactRepository.


Он не должен напрямую менять UI.

PresenceManager
      ↓
Repository
      ↓
Room
      ↓
Flow
      ↓
UI


---

25. Жизненный цикл приложения

I2PManager и PresenceManager должны жить на уровне Application, а не Activity.

Application
 ├── I2PManager
 ├── PresenceManager
 └── repositories

Activity может пересоздаваться:

rotate
background
return
navigation

но сетевой слой не должен из-за этого пересоздаваться.


---

26. Потоки

UI:

Main thread

Room:

IO

Сеть:

IO

Долгие операции никогда не выполняются на главном потоке.

Не использовать бесконтрольный:

GlobalScope.launch

Предпочтительно иметь управляемый:

CoroutineScope(
    SupervisorJob() + Dispatchers.IO
)

на уровне соответствующего компонента.


---

27. Дедупликация

Любой входящий CHAT_MESSAGE сначала проверяется:

messageId уже есть в Room?

Если:

да → не создавать дубликат
нет → сохранить

После успешного сохранения отправить:

MESSAGE_DELIVERED


---

28. Ошибки сети

Транспортная ошибка:

Broken pipe
Connection reset
Timeout
SAM closed connection

не должна попадать в UI как обычное сообщение.

Она должна превращаться в состояние транспорта:

DISCONNECTED
CONNECTING
CONNECTED
ERROR


---

29. Файлы — будущее расширение

Диапазон:

0x30 – 0x3F

Планируемые типы:

FILE_OFFER
FILE_ACCEPT
FILE_REJECT
FILE_CHUNK
FILE_COMPLETE

Файл не должен помещаться целиком в CHAT_MESSAGE.

Сообщение содержит метаданные:

fileId
name
size
mimeType
hash

А данные передаются отдельными блоками.


---

30. Звонки — будущее расширение

Диапазон:

0x40 – 0x4F

Возможные пакеты:

CALL_OFFER
CALL_ANSWER
CALL_REJECT
CALL_END
CALL_CANDIDATE

Это будет отдельный этап разработки.


---

31. Что нельзя делать

Не смешивать:

UI
SAM
Room
Protocol

Например, плохой вариант:

ChatActivity
    ↓
SamConnection
    ↓
"CHAT_MESSAGE|..."

Правильнее:

ChatActivity
    ↓
ChatManager
    ↓
ProtocolEncoder
    ↓
I2PManager
    ↓
SamConnection


---

32. Предлагаемая структура проекта

Примерно:

com.example.i2pmessenger

├── app
│
├── data
│   ├── database
│   │   ├── AppDatabase
│   │   ├── ContactDao
│   │   └── MessageDao
│   │
│   ├── entity
│   │   ├── ContactEntity
│   │   └── MessageEntity
│   │
│   └── repository
│       ├── ContactRepository
│       └── MessageRepository
│
├── network
│   ├── i2p
│   │   ├── I2PManager
│   │   ├── SamConnection
│   │   └── ...
│   │
│   └── protocol
│       ├── ProtocolPacket
│       ├── PacketType
│       ├── ProtocolEncoder
│       └── ProtocolDecoder
│
├── presence
│   ├── PresenceManager
│   └── ...
│
├── chat
│   ├── ChatManager
│   └── ...
│
└── ui
    ├── main
    ├── chat
    ├── contacts
    └── adapters

Это целевая структура, а не требование прямо сейчас перенести всё в эти папки.

Не будем делать рефакторинг только ради красивых папок.


---

33. Главный принцип развития

Разработка идёт снизу вверх.

Этап 1 — транспорт

SAM
 ↓
SESSION
 ↓
STREAM
 ↓
CONNECT
 ↓
SEND
 ↓
RECEIVE

Этап 2 — протокол

Packet
 ↓
Encoder
 ↓
Decoder

Этап 3 — сообщения

CHAT_MESSAGE
DELIVERED
READ

Этап 4 — presence

PRESENCE
PING
PONG

Этап 5 — UI

Room
 ↓
Flow
 ↓
UI

Этап 6 — дополнительные возможности

FILES
CALLS
GROUPS


---

34. Наш рабочий процесс

И вот это предлагаю сделать нашим правилом.

Я не буду периодически вываливать на тебя:

> «Теперь перепиши 17 классов».



Вместо этого будем работать маленькими задачами.

Например:

Задача 1

Стабилизировать SAM control connection.

Подзадачи:

1. Проверить жизненный цикл socket.
2. Найти конкурентные sendCommand().
3. Сериализовать команды.
4. Проверить SESSION CREATE.
5. Проверить reconnect.
6. Проверить STREAM CONNECT.

После каждого этапа:

код
 ↓
сборка
 ↓
логи
 ↓
проверка

Только когда этап действительно работает — переходим дальше.


---

35. Правило «не чинить то, что ещё не сломано»

Если сейчас:

STREAM

работает, мы не будем одновременно переписывать:

Room
Presence
UI
Protocol

ради будущей архитектуры.

Сначала:

SAM стабилен

Потом:

Protocol

Потом:

Receipts

и так далее.


---

36. Текущий приоритет проекта

На данный момент:

🔴 1. Стабилизировать SAM / STREAM
🟠 2. Ввести Protocol layer
🟠 3. CHAT_MESSAGE
🟡 4. MESSAGE_DELIVERED
🟡 5. MESSAGE_READ
🟡 6. PRESENCE
🟢 7. PING/PONG
⚪ 8. FILES
⚪ 9. CALLS

Причём PRESENCE уже частично реализован, поэтому мы его не выбрасываем — просто позже перенесём на нормальный протокол.


---

37. Ключевая архитектурная идея проекта

В конечном итоге хочется получить:

┌──────────────┐
                    │     UI       │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │ Application  │
                    │   Logic      │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │   Protocol   │
                    │              │
                    │ CHAT         │
                    │ RECEIPTS     │
                    │ PRESENCE     │
                    │ FILES        │
                    │ CALLS        │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │  Transport   │
                    │              │
                    │ I2P STREAM    │
                    │ SAM           │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │    i2pd      │
                    └──────────────┘

А Room находится рядом с логикой приложения как локальное хранилище состояния, а не как часть сетевого слоя.


---

И самое главное

Я бы не начинал сейчас писать весь этот протокол.

А текущая задача остаётся предельно скучной:

> Починить и стабилизировать SAM/STREAM.



После этого я буду выдавать тебе задачи небольшими порциями: что сделать → какие файлы изменить → как проверить → какие логи ожидаем увидеть. Ты кидаешь результат, и мы вместе проверяем, прежде чем двигаться дальше.

Так мы не утонем в собственном мессенджере. А это, учитывая нынешние приключения с Broken pipe, уже немаленькая победа. 😄
