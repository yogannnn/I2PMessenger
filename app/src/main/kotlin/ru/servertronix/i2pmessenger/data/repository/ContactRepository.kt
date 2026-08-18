package ru.servertronix.i2pmessenger.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ru.servertronix.i2pmessenger.Contact
import ru.servertronix.i2pmessenger.data.local.AppDatabase
import ru.servertronix.i2pmessenger.data.local.ContactEntity
import ru.servertronix.i2pmessenger.i2p.I2PManager
import ru.servertronix.i2pmessenger.i2p.SamConnection

class ContactRepository(
    private val db: AppDatabase,
    private val samConnectionProvider: () -> SamConnection? = { I2PManager.getSamConnection() }
) {

    companion object {
        private const val TAG = "ContactRepository"
        private const val DESTINATION_CACHE_TTL_MS = 5 * 60 * 1000L // 5 минут
    }

    private val dao = db.contactDao()

    /**
     * In-memory кеш для разрешения base32 адресов в base64 destination.
     * Ключ: normalized base32 адрес (khdy7cxq...)
     * Значение: destination (VEgoBVRVYw...) + время истечения
     *
     * Зачем нужен кеш:
     * - PresenceManager отправляет PRESENCE каждые 15 сек на каждый контакт
     * - Без кеша это 10+ NAMING LOOKUP запросов к SAM в минуту
     * - Кеш с TTL 5 минут резко снижает нагрузку на SAM-мост
     */
    private val destinationCache = mutableMapOf<String, CachedDestination>()
    private data class CachedDestination(val destination: String, val expiresAt: Long)

    // =====================================================================
    // ADDRESS HELPERS — НОРМАЛИЗАЦИЯ АДРЕСОВ
    // =====================================================================

    /**
     * Нормализует I2P адрес:
     * - убирает пробелы
     * - приводит к нижнему регистру
     * - убирает суффикс .b32.i2p
     * 
     * На выходе: чистый base32 адрес (khdy7cxqh2tonqcsyhmcybklvz262ajmbqnbxkcw5c6zcgwhv3eq)
     */
    fun normalizeAddress(address: String): String {
        var normalized = address.trim().lowercase()
        normalized = normalized.removeSuffix(".b32.i2p")
        return normalized
    }

    /**
     * Добавляет суффикс .b32.i2p к нормализованному адресу.
     * Это формат, который ожидает SAM для NAMING LOOKUP.
     */
    fun toBase32Address(address: String): String {
        val normalized = normalizeAddress(address)
        return "$normalized.b32.i2p"
    }

    // =====================================================================
    // GET ALL
    // =====================================================================

    fun getAllContacts(): Flow<List<Contact>> {
        Log.d(TAG, "🔍 [REPO] getAllContacts() called")
        return db.contactDao().getAllContacts().map { entities ->
            Log.d(TAG, "🔍 [REPO] getAllContacts: entities.size=${entities.size}")
            entities.map { it.toContact() }
        }
    }

    suspend fun getAllContactsSync(): List<Contact> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] getAllContactsSync() called")
        val entities = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO] getAllContactsSync: entities.size=${entities.size}")
        entities.forEach { entity ->
            Log.d(TAG, "🔍 [REPO]   entity: id=${entity.id}, address=${entity.address}, hasKey=${entity.publicKeyBase64 != null}")
        }
        entities.map { it.toContact() }
    }

    suspend fun getContactsWithDestinationSync(): List<Contact> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] getContactsWithDestinationSync() called")
        val all = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO]   всего контактов: ${all.size}")
        val filtered = all.filter { !it.publicKeyBase64.isNullOrBlank() }
        Log.d(TAG, "🔍 [REPO]   с ключами: ${filtered.size}")
        filtered.map { it.toContact() }
    }

    suspend fun getDestinationBase64ListSync(): List<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] getDestinationBase64ListSync() START")
        val all = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO]   всего контактов: ${all.size}")
        all.forEach { entity ->
            Log.d(TAG, "🔍 [REPO]   контакт: address=${entity.address}, hasKey=${entity.publicKeyBase64 != null}")
        }
        val list = all.mapNotNull { it.publicKeyBase64 }.filter { it.isNotBlank() }
        Log.d(TAG, "🔍 [REPO]   контактов с ключами: ${list.size}")
        Log.d(TAG, "🔍 [REPO] getDestinationBase64ListSync() END")
        list
    }

    // =====================================================================
    // ADD CONTACT
    // =====================================================================

    suspend fun addContact(name: String, address: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] addContact() START: name=$name, address=$address")
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO]   normalized=$normalized")

        if (normalized.isBlank()) {
            Log.e(TAG, "🔍 [REPO] ❌ адрес пустой!")
            throw IllegalArgumentException("I2P Base32 адрес пуст")
        }

        if (db.contactDao().getContactByAddress(normalized) != null) {
            Log.d(TAG, "🔍 [REPO]   контакт уже существует")
            return@withContext
        }

        val entity = ContactEntity(
            name = name.trim(),
            address = normalized,
            publicKeyBase64 = null
        )
        Log.d(TAG, "🔍 [REPO]   вставляем entity: name=${entity.name}, address=${entity.address}")
        db.contactDao().insertContact(entity)
        Log.d(TAG, "🔍 [REPO] ✅ addContact() завершён")
    }

    suspend fun addContactAndResolve(name: String, address: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] addContactAndResolve() START: name=$name, address=$address")
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO]   normalized=$normalized")

        if (normalized.isBlank()) {
            Log.e(TAG, "🔍 [REPO] ❌ адрес пустой!")
            throw IllegalArgumentException("I2P Base32 адрес пуст")
        }

        val existing = db.contactDao().getContactByAddress(normalized)
        if (existing == null) {
            Log.d(TAG, "🔍 [REPO]   контакт не найден, создаём новый")
            db.contactDao().insertContact(
                ContactEntity(
                    name = name.trim(),
                    address = normalized,
                    publicKeyBase64 = null
                )
            )
            Log.d(TAG, "🔍 [REPO]   контакт создан")
        } else {
            Log.d(TAG, "🔍 [REPO]   контакт уже существует: id=${existing.id}")
        }

        Log.d(TAG, "🔍 [REPO]   вызываем resolveDestination для $normalized")
        val destination = resolveDestination(normalized)
        Log.d(TAG, "🔍 [REPO]   resolveDestination вернул ${if (destination != null) "ключ (${destination.take(32)}...)" else "null"}")

        if (destination != null) {
            Log.d(TAG, "🔍 [REPO]   сохраняем ключ в БД")
            val updated = db.contactDao().updatePublicKey(normalized, destination)
            Log.d(TAG, "🔍 [REPO]   updatePublicKey вернул $updated строк")
            Log.d(TAG, "✅ [REPO]   ключ сохранён")
        } else {
            Log.w(TAG, "🔍 [REPO]   ⚠️ ключ НЕ сохранён (destination=null)")
        }

        Log.d(TAG, "🔍 [REPO] addContactAndResolve() END")
        destination
    }

    // =====================================================================
    // DESTINATION LOOKUP (with in-memory cache)
    // =====================================================================

    /**
     * Разрешает base32 адрес в base64 destination через SAM NAMING LOOKUP.
     *
     * SAM протокол:
     * Client → HELLO → STREAM CONNECT → STREAM ACCEPT
     * Client → NAMING LOOKUP NAME=<base32> → VALUE=<base64>
     *
     * приоритет: кеш → SAM-REQUEST → обновление кеша
     *
     * @return base64 destination (SAM format) или null, если не удалось разрешить
     */
    suspend fun resolveDestination(address: String): String? = withContext(Dispatchers.IO) {
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO] resolveDestination() START: normalized=$normalized")

        if (normalized.isBlank()) {
            Log.w(TAG, "🔍 [REPO]   адрес пустой")
            return@withContext null
        }

        // Проверяем кеш сначала — это экономит SAM-запросы
        val cached = destinationCache[normalized]
        val now = System.currentTimeMillis()
        if (cached != null && cached.expiresAt > now) {
            Log.d(TAG, "🔍 [REPO]   cache HIT for $normalized (valid until ${cached.expiresAt - now}ms)")
            return@withContext cached.destination
        }

        // Если в кеше null (попадание в первый раз или после очистки) — пропускаем кэш
        val base32 = toBase32Address(normalized)
        Log.d(TAG, "🔍 [REPO]   base32=$base32")

        val sam = samConnectionProvider()
        Log.d(TAG, "🔍 [REPO]   sam=${if (sam != null) "current I2PManager connection" else "unavailable"}")
        if (sam == null) {
            Log.w(TAG, "🔍 [REPO]   SAM недоступен, lookup будет повторён после восстановления")
            return@withContext null
        }

        try {
            Log.d(TAG, "🔍 [REPO]   вызываем sam.lookupDestination($base32)")
            val destination = sam.lookupDestination(base32)
            Log.d(TAG, "🔍 [REPO]   sam.lookupDestination вернул ${if (destination != null) "ключ (${destination.take(32)}...)" else "null"}")
            if (destination != null) {
                // Сохраняем в кеш на 5 минут — BASE32-адрес обычно не меняется часто
                destinationCache[normalized] = CachedDestination(destination, now + DESTINATION_CACHE_TTL_MS)
                Log.d(TAG, "🔍 [REPO]   cached for ${DESTINATION_CACHE_TTL_MS / 1000} sec")
            }
            destination
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [REPO] ❌ ошибка resolveDestination", e)
            null
        }
    }

    /** Инвалидирует кеш для конкретного адреса (вызывается при обновлении ключа) */
    internal fun invalidateDestinationCache(address: String) {
        val normalized = normalizeAddress(address)
        destinationCache.remove(normalized)
        Log.d(TAG, "🔍 [REPO] cache invalidated for $normalized")
    }

    /** Полная очистка кеша (вызывается при смене SAM-хоста/порта) */
    internal fun clearDestinationCache() {
        destinationCache.clear()
        Log.d(TAG, "🔍 [REPO] destination cache cleared")
    }

    suspend fun resolveAndSaveDestination(address: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] resolveAndSaveDestination() START: address=$address")
        val normalized = normalizeAddress(address)
        val destination = resolveDestination(normalized)
        if (destination.isNullOrBlank()) {
            Log.w(TAG, "🔍 [REPO]   destination null или пустой")
            return@withContext null
        }
        Log.d(TAG, "🔍 [REPO]   сохраняем destination в БД")
        db.contactDao().updatePublicKey(normalized, destination)
        Log.d(TAG, "✅ [REPO] resolveAndSaveDestination() OK")
        destination
    }

    suspend fun resolveMissingDestinations(): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] resolveMissingDestinations() START")
        val contacts = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO]   всего контактов: ${contacts.size}")
        var updated = 0
        for (contact in contacts) {
            if (!contact.publicKeyBase64.isNullOrBlank()) {
                Log.d(TAG, "🔍 [REPO]   контакт ${contact.address} уже имеет ключ, пропускаем")
                continue
            }
            Log.d(TAG, "🔍 [REPO]   пытаемся разрешить контакт ${contact.address}")
            try {
                val destination = resolveAndSaveDestination(contact.address)
                if (destination != null) {
                    updated++
                    Log.d(TAG, "🔍 [REPO]   ✅ ключ сохранён для ${contact.address}")
                } else {
                    Log.w(TAG, "🔍 [REPO]   ⚠️ не удалось разрешить ${contact.address}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🔍 [REPO] ❌ ошибка resolve для ${contact.address}", e)
            }
        }
        Log.d(TAG, "🔍 [REPO] resolveMissingDestinations() END, обновлено $updated контактов")
        updated
    }

    // =====================================================================
    // UPDATE
    // =====================================================================

    /**
     * Обновляет контакт (имя и адрес).
     * Вызывается из editContactDialog при сохранении.
     * 1. Обновляем данные в БД
     * 2. Инвалидируем кеш oldAddress (запись может получить новый ключ)
     */
    suspend fun updateContact(id: Int, name: String, address: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] updateContact() START: id=$id, name=$name, address=$address")
        val normalized = normalizeAddress(address)
        db.contactDao().updateContact(id, name.trim(), normalized)
        db.contactDao().updatePublicKey(normalized, "")
        // Инвалидируем кеш — адрес мог измениться, новый ключ будет запрошен при необходимости
        destinationCache.remove(normalized)
        Log.d(TAG, "🔍 [REPO] ✅ updateContact() завершён")
    }

    /**
     * Сохраняет base64 destination для контакта в БД.
     * Вызывается из addContactAndResolve и resolveAndSaveDestination.
     * Инвалидирует кеш, так как ключ обновился.
     */
    suspend fun updatePublicKey(address: String, publicKey: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] updatePublicKey() START: address=$address")
        val normalized = normalizeAddress(address)
        if (publicKey.isBlank()) {
            Log.w(TAG, "🔍 [REPO]   publicKey пустой!")
            return@withContext
        }
        val updated = db.contactDao().updatePublicKey(normalized, publicKey)
        Log.d(TAG, "🔍 [REPO]   updatePublicKey вернул $updated строк")
        Log.d(TAG, "✅ [REPO] updatePublicKey() OK: ${publicKey.take(32)}...")
        // Инвалидируем кеш, так как ключ обновился
        destinationCache.remove(normalized)
    }

    // =====================================================================
    // ONLINE STATUS — ОТСЛЕЖИВАНИЕ НАЛИЧИЯ КОНТАКТОВ
    // =====================================================================

    /** Обновляет статус онлайна для контакта */
    suspend fun updateOnlineStatus(address: String, isOnline: Boolean, lastSeen: Long) = withContext(Dispatchers.IO) {
        val normalized = normalizeAddress(address)
        val updated = db.contactDao().updateOnlineStatus(normalized, isOnline, lastSeen)
        Log.d(TAG, "🔍 [REPO] updateOnlineStatus: address=$normalized, online=$isOnline, rows=$updated")
    }

    /**
     * Находит контакт по его base64 destination и обновляет статус.
     * Используется PresenceManager при получении входящего PRESENCE.
     * Находит по полному совпадению publicKeyBase64 в БД.
     */
    suspend fun updateOnlineStatusByDestination(destinationBase64: String, isOnline: Boolean, lastSeen: Long): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] updateOnlineStatusByDestination() START: destination=${destinationBase64.take(32)}...")
        if (destinationBase64.isBlank()) {
            Log.w(TAG, "🔍 [REPO]   destination пустой")
            return@withContext false
        }

        val all = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO]   ищем контакт по destination...")
        val entity = all.firstOrNull { it.publicKeyBase64 == destinationBase64 }
        if (entity == null) {
            Log.w(TAG, "🔍 [REPO]   ❌ контакт не найден по destination")
            return@withContext false
        }

        Log.d(TAG, "🔍 [REPO]   найден контакт: address=${entity.address}")
        val updated = db.contactDao().updateOnlineStatus(entity.address, isOnline, lastSeen)
        Log.d(TAG, "🔍 [REPO]   updateOnlineStatus вернул $updated строк")
        return@withContext updated > 0
    }

    // =====================================================================
    // GET BY ADDRESS
    // =====================================================================

    suspend fun getContactByAddress(address: String): Contact? = withContext(Dispatchers.IO) {
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO] getContactByAddress: normalized=$normalized")
        db.contactDao().getContactByAddress(normalized)?.toContact()
    }

    fun getContactByAddressFlow(address: String): Flow<Contact?> {
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO] getContactByAddressFlow: normalized=$normalized")
        return db.contactDao().getContactByAddressFlow(normalized).map { entity ->
            entity?.toContact()
        }
    }

    suspend fun findContactByDestinationSync(destinationBase64: String): Contact? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] findContactByDestinationSync: destination=${destinationBase64.take(32)}...")
        if (destinationBase64.isBlank()) {
            Log.w(TAG, "🔍 [REPO]   destination пустой")
            return@withContext null
        }
        val all = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO]   ищем среди ${all.size} контактов")
        all.firstOrNull { it.publicKeyBase64 == destinationBase64 }?.toContact()
    }

    // =====================================================================
    // DELETE
    // =====================================================================

    suspend fun deleteContact(id: Int) = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 [REPO] deleteContact: id=$id")
        db.contactDao().deleteContact(id)
        Log.d(TAG, "✅ [REPO] deleteContact завершён")
    }

    // =====================================================================
    // EXISTS
    // =====================================================================

    /** Проверяет, существует ли контакт с таким адресом */
    suspend fun isContactExists(address: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO] isContactExists: normalized=$normalized")
        db.contactDao().getContactByAddress(normalized) != null
    }
}

fun ContactEntity.toContact() = Contact(
    id = id,
    name = name,
    address = address,
    publicKeyBase64 = publicKeyBase64,
    isOnline = isOnline,
    lastSeen = lastSeen,
    lastMessage = lastMessage,
    lastMessageTime = lastMessageTime
)