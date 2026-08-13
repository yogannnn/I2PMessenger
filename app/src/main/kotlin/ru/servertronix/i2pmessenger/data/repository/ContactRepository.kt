package ru.servertronix.i2pmessenger.data.repository

import ru.servertronix.i2pmessenger.Contact
import ru.servertronix.i2pmessenger.data.local.ContactEntity
import ru.servertronix.i2pmessenger.data.local.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ContactRepository(private val db: AppDatabase) {

    fun getAllContacts(): Flow<List<Contact>> {
        return db.contactDao().getAllContacts().map { entities ->
            entities.map { it.toContact() }
        }
    }

    suspend fun addContact(name: String, address: String) {
        val entity = ContactEntity(
            name = name,
            address = address
        )
        db.contactDao().insertContact(entity)
    }

    suspend fun deleteContact(id: Int) {
        db.contactDao().deleteContact(id)
    }

    suspend fun updateContact(id: Int, name: String, address: String) {
        db.contactDao().updateContact(id, name, address)
    }

    suspend fun isContactExists(address: String): Boolean {
        return db.contactDao().getContactByAddress(address) != null
    }
}

// Функция преобразования Entity → Domain модель
fun ContactEntity.toContact(): Contact {
    return Contact(
        id = id,
        name = name,
        address = address,
        isOnline = isOnline,
        lastMessage = lastMessage,
        lastMessageTime = lastMessageTime
    )
}