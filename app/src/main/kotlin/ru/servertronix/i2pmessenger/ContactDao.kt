package ru.servertronix.i2pmessenger.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteContact(id: Int)

    @Query("UPDATE contacts SET name = :name, address = :address WHERE id = :id")
    suspend fun updateContact(id: Int, name: String, address: String)
    
    @Query("SELECT * FROM contacts WHERE address = :address LIMIT 1")
    suspend fun getContactByAddress(address: String): ContactEntity?
}