package ru.servertronix.i2pmessenger.data.repository

import ru.servertronix.i2pmessenger.Message
import ru.servertronix.i2pmessenger.data.local.AppDatabase
import ru.servertronix.i2pmessenger.data.local.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MessageRepository(private val db: AppDatabase) {

    fun getMessagesForChat(chatId: String): Flow<List<Message>> = db.messageDao()
        .getMessagesForChat(chatId)
        .map { entities ->
            entities.map { it.toMessage() }
        }

    suspend fun saveMessage(message: Message, chatId: String, senderId: String) {
        val entity = MessageEntity(
            chatId = chatId,
            senderId = senderId,
            text = message.text,
            timestamp = message.timestamp,
            isMine = message.isMine,
            status = message.status
        )
        db.messageDao().insertMessage(entity)
    }

    suspend fun deleteMessagesForChat(chatId: String) {
        db.messageDao().deleteMessagesForChat(chatId)
    }

    suspend fun deleteMessage(id: Long) {
        db.messageDao().deleteMessage(id)
    }
}

fun MessageEntity.toMessage() = Message(
    id = id.toString(),
    text = text,
    timestamp = timestamp,
    isMine = isMine,
    status = status
)