package ru.servertronix.i2pmessenger.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    // ---------------------------------------------------------------------
    // INSERT
    // ---------------------------------------------------------------------

    @Insert(
        onConflict = OnConflictStrategy.IGNORE
    )
    suspend fun insertContact(
        contact: ContactEntity
    ): Long

    // ---------------------------------------------------------------------
    // READ
    // ---------------------------------------------------------------------

    @Query("""
        SELECT *
        FROM contacts
        ORDER BY name COLLATE NOCASE ASC
    """)
    fun getAllContacts():
            Flow<List<ContactEntity>>

    /**
     * Синхронный вариант нужен репозиторию для операций,
     * которые выполняются уже на Dispatchers.IO.
     */
    @Query("""
        SELECT *
        FROM contacts
        ORDER BY name COLLATE NOCASE ASC
    """)
    suspend fun getAllContactsSync():
            List<ContactEntity>

    @Query("""
        SELECT *
        FROM contacts
        WHERE address = :address
        LIMIT 1
    """)
    suspend fun getContactByAddress(
        address: String
    ): ContactEntity?

    @Query("""
        SELECT *
        FROM contacts
        WHERE address = :address
        LIMIT 1
    """)
    fun getContactByAddressFlow(
        address: String
    ): Flow<ContactEntity?>

    @Query("""
        SELECT *
        FROM contacts
        WHERE publicKeyBase64 = :destinationBase64
        LIMIT 1
    """)
    suspend fun getContactByDestination(
        destinationBase64: String
    ): ContactEntity?

    // ---------------------------------------------------------------------
    // DESTINATION / KEY
    // ---------------------------------------------------------------------

    /**
     * Сохраняет полный I2P Destination Base64,
     * полученный через NAMING LOOKUP или первый контакт.
     */
    @Query("""
        UPDATE contacts
        SET publicKeyBase64 = :publicKeyBase64
        WHERE address = :address
    """)
    suspend fun updatePublicKey(
        address: String,
        publicKeyBase64: String?
    ): Int

    /**
     * Сбрасывает Destination.
     *
     * Используется, например, если адрес контакта изменился.
     */
    @Query("""
        UPDATE contacts
        SET publicKeyBase64 = NULL
        WHERE address = :address
    """)
    suspend fun clearPublicKey(
        address: String
    ): Int

    // ---------------------------------------------------------------------
    // UPDATE CONTACT
    // ---------------------------------------------------------------------

    /**
     * ВАЖНО:
     *
     * При изменении Base32 адреса старый Destination Base64
     * больше нельзя использовать.
     *
     * Поэтому publicKeyBase64 сбрасывается прямо здесь.
     */
    @Query("""
        UPDATE contacts
        SET
            name = :name,
            address = :address,
            publicKeyBase64 = NULL
        WHERE id = :id
    """)
    suspend fun updateContact(
        id: Int,
        name: String,
        address: String
    ): Int

    // ---------------------------------------------------------------------
    // ONLINE STATUS
    // ---------------------------------------------------------------------

    @Query("""
        UPDATE contacts
        SET
            isOnline = :isOnline,
            lastSeen = :lastSeen
        WHERE address = :address
    """)
    suspend fun updateOnlineStatus(
        address: String,
        isOnline: Boolean,
        lastSeen: Long
    ): Int

    /**
     * Вариант обновления непосредственно по Destination Base64.
     *
     * Это удобно для входящих presence:
     *
     * STREAM sender
     *      ↓
     * destinationBase64
     *      ↓
     * этот query
     */
    @Query("""
        UPDATE contacts
        SET
            isOnline = :isOnline,
            lastSeen = :lastSeen
        WHERE publicKeyBase64 = :destinationBase64
    """)
    suspend fun updateOnlineStatusByDestination(
        destinationBase64: String,
        isOnline: Boolean,
        lastSeen: Long
    ): Int

    // ---------------------------------------------------------------------
    // LAST MESSAGE
    // ---------------------------------------------------------------------

    @Query("""
        UPDATE contacts
        SET
            lastMessage = :lastMessage,
            lastMessageTime = :lastMessageTime
        WHERE address = :address
    """)
    suspend fun updateLastMessage(
        address: String,
        lastMessage: String,
        lastMessageTime: Long
    ): Int

    // ---------------------------------------------------------------------
    // DELETE
    // ---------------------------------------------------------------------

    @Delete
    suspend fun deleteContact(
        contact: ContactEntity
    )

    @Query("""
        DELETE FROM contacts
        WHERE id = :id
    """)
    suspend fun deleteContact(
        id: Int
    ): Int

    // ---------------------------------------------------------------------
    // UTILITY
    // ---------------------------------------------------------------------

    @Query("""
        SELECT COUNT(*)
        FROM contacts
    """)
    suspend fun getContactCount(): Int
}