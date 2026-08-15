package ru.servertronix.i2pmessenger.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.servertronix.i2pmessenger.Contact
import ru.servertronix.i2pmessenger.data.local.AppDatabase
import ru.servertronix.i2pmessenger.data.local.ContactEntity
import ru.servertronix.i2pmessenger.i2p.SamConnection
import ru.servertronix.i2pmessenger.i2p.I2PManager

class ContactRepository(
    private val db: AppDatabase,
    private val samConnectionProvider: () -> SamConnection? = { I2PManager.getSamConnection() }
) {

    companion object {
        private const val TAG = "ContactRepository"
    }

    private val dao = db.contactDao()

    // =====================================================================
    // ADDRESS HELPERS
    // =====================================================================

    fun normalizeAddress(address: String): String {
        var normalized = address.trim().lowercase()
        normalized = normalized.removeSuffix(".b32.i2p")
        return normalized
    }

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

    suspend fun getAllContactsSync(): List<Contact> {
        Log.d(TAG, "🔍 [REPO] getAllContactsSync() called")
        val entities = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO] getAllContactsSync: entities.size=${entities.size}")
        entities.forEach { entity ->
            Log.d(TAG, "🔍 [REPO]   entity: id=${entity.id}, address=${entity.address}, hasKey=${entity.publicKeyBase64 != null}")
        }
        return entities.map { it.toContact() }
    }

    suspend fun getContactsWithDestinationSync(): List<Contact> {
        Log.d(TAG, "🔍 [REPO] getContactsWithDestinationSync() called")
        val all = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO]   всего контактов: ${all.size}")
        val filtered = all.filter { !it.publicKeyBase64.isNullOrBlank() }
        Log.d(TAG, "🔍 [REPO]   с ключами: ${filtered.size}")
        return filtered.map { it.toContact() }
    }

    suspend fun getDestinationBase64ListSync(): List<String> {
        Log.d(TAG, "🔍 [REPO] getDestinationBase64ListSync() START")
        val all = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO]   всего контактов: ${all.size}")
        all.forEach { entity ->
            Log.d(TAG, "🔍 [REPO]   контакт: address=${entity.address}, hasKey=${entity.publicKeyBase64 != null}")
        }
        val list = all.mapNotNull { it.publicKeyBase64 }.filter { it.isNotBlank() }
        Log.d(TAG, "🔍 [REPO]   контактов с ключами: ${list.size}")
        Log.d(TAG, "🔍 [REPO] getDestinationBase64ListSync() END")
        return list
    }

    // =====================================================================
    // ADD CONTACT
    // =====================================================================

    suspend fun addContact(name: String, address: String) {
        Log.d(TAG, "🔍 [REPO] addContact() START: name=$name, address=$address")
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO]   normalized=$normalized")

        if (normalized.isBlank()) {
            Log.e(TAG, "🔍 [REPO] ❌ адрес пустой!")
            throw IllegalArgumentException("I2P Base32 адрес пуст")
        }

        if (db.contactDao().getContactByAddress(normalized) != null) {
            Log.d(TAG, "🔍 [REPO]   контакт уже существует")
            return
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

    suspend fun addContactAndResolve(name: String, address: String): String? {
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
        return destination
    }

    // =====================================================================
    // DESTINATION LOOKUP
    // =====================================================================

    suspend fun resolveDestination(address: String): String? {
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO] resolveDestination() START: normalized=$normalized")

        if (normalized.isBlank()) {
            Log.w(TAG, "🔍 [REPO]   адрес пустой")
            return null
        }

        val base32 = toBase32Address(normalized)
        Log.d(TAG, "🔍 [REPO]   base32=$base32")

        val sam = samConnectionProvider()
        Log.d(TAG, "🔍 [REPO]   sam=${if (sam != null) "current I2PManager connection" else "unavailable"}")
        if (sam == null) {
            Log.w(TAG, "🔍 [REPO]   SAM недоступен, lookup будет повторён после восстановления")
            return null
        }

        return try {
            Log.d(TAG, "🔍 [REPO]   вызываем sam.lookupDestination($base32)")
            val destination = sam.lookupDestination(base32)
            Log.d(TAG, "🔍 [REPO]   sam.lookupDestination вернул ${if (destination != null) "ключ (${destination.take(32)}...)" else "null"}")
            destination
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [REPO] ❌ ошибка resolveDestination", e)
            null
        }
    }

    suspend fun resolveAndSaveDestination(address: String): String? {
        Log.d(TAG, "🔍 [REPO] resolveAndSaveDestination() START: address=$address")
        val normalized = normalizeAddress(address)
        val destination = resolveDestination(normalized)
        if (destination.isNullOrBlank()) {
            Log.w(TAG, "🔍 [REPO]   destination null или пустой")
            return null
        }
        Log.d(TAG, "🔍 [REPO]   сохраняем destination в БД")
        db.contactDao().updatePublicKey(normalized, destination)
        Log.d(TAG, "✅ [REPO] resolveAndSaveDestination() OK")
        return destination
    }

 suspend fun resolveMissingDestinations(): Int {
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
    return updated
}



    // =====================================================================
    // UPDATE
    // =====================================================================

    suspend fun updateContact(id: Int, name: String, address: String) {
        Log.d(TAG, "🔍 [REPO] updateContact() START: id=$id, name=$name, address=$address")
        val normalized = normalizeAddress(address)
        db.contactDao().updateContact(id, name.trim(), normalized)
        db.contactDao().updatePublicKey(normalized, "")
        Log.d(TAG, "🔍 [REPO] ✅ updateContact() завершён")
    }

    suspend fun updatePublicKey(address: String, publicKey: String) {
        Log.d(TAG, "🔍 [REPO] updatePublicKey() START: address=$address")
        val normalized = normalizeAddress(address)
        if (publicKey.isBlank()) {
            Log.w(TAG, "🔍 [REPO]   publicKey пустой!")
            return
        }
        val updated = db.contactDao().updatePublicKey(normalized, publicKey)
        Log.d(TAG, "🔍 [REPO]   updatePublicKey вернул $updated строк")
        Log.d(TAG, "✅ [REPO] updatePublicKey() OK: ${publicKey.take(32)}...")
    }

    // =====================================================================
    // ONLINE STATUS
    // =====================================================================

    suspend fun updateOnlineStatus(address: String, isOnline: Boolean, lastSeen: Long) {
        val normalized = normalizeAddress(address)
        val updated = db.contactDao().updateOnlineStatus(normalized, isOnline, lastSeen)
        Log.d(TAG, "🔍 [REPO] updateOnlineStatus: address=$normalized, online=$isOnline, rows=$updated")
    }

    suspend fun updateOnlineStatusByDestination(destinationBase64: String, isOnline: Boolean, lastSeen: Long): Boolean {
        Log.d(TAG, "🔍 [REPO] updateOnlineStatusByDestination() START: destination=${destinationBase64.take(32)}...")
        if (destinationBase64.isBlank()) {
            Log.w(TAG, "🔍 [REPO]   destination пустой")
            return false
        }

        val all = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO]   ищем контакт по destination...")
        val entity = all.firstOrNull { it.publicKeyBase64 == destinationBase64 }
        if (entity == null) {
            Log.w(TAG, "🔍 [REPO]   ❌ контакт не найден по destination")
            return false
        }

        Log.d(TAG, "🔍 [REPO]   найден контакт: address=${entity.address}")
        val updated = db.contactDao().updateOnlineStatus(entity.address, isOnline, lastSeen)
        Log.d(TAG, "🔍 [REPO]   updateOnlineStatus вернул $updated строк")
        return updated > 0
    }

    // =====================================================================
    // GET BY ADDRESS
    // =====================================================================

    suspend fun getContactByAddress(address: String): Contact? {
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO] getContactByAddress: normalized=$normalized")
        return db.contactDao().getContactByAddress(normalized)?.toContact()
    }

    fun getContactByAddressFlow(address: String): Flow<Contact?> {
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO] getContactByAddressFlow: normalized=$normalized")
        return db.contactDao().getContactByAddressFlow(normalized).map { entity ->
            entity?.toContact()
        }
    }

    suspend fun findContactByDestinationSync(destinationBase64: String): Contact? {
        Log.d(TAG, "🔍 [REPO] findContactByDestinationSync: destination=${destinationBase64.take(32)}...")
        if (destinationBase64.isBlank()) {
            Log.w(TAG, "🔍 [REPO]   destination пустой")
            return null
        }
        val all = db.contactDao().getAllContactsSync()
        Log.d(TAG, "🔍 [REPO]   ищем среди ${all.size} контактов")
        return all.firstOrNull { it.publicKeyBase64 == destinationBase64 }?.toContact()
    }

    // =====================================================================
    // DELETE
    // =====================================================================

    suspend fun deleteContact(id: Int) {
        Log.d(TAG, "🔍 [REPO] deleteContact: id=$id")
        db.contactDao().deleteContact(id)
        Log.d(TAG, "✅ [REPO] deleteContact завершён")
    }

    // =====================================================================
    // EXISTS
    // =====================================================================

    suspend fun isContactExists(address: String): Boolean {
        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [REPO] isContactExists: normalized=$normalized")
        return db.contactDao().getContactByAddress(normalized) != null
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