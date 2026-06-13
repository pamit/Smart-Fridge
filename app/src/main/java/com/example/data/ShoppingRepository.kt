package com.example.data

import kotlinx.coroutines.flow.Flow

class ShoppingRepository(private val dao: ShoppingItemDao) {
    val allItems: Flow<List<ShoppingItem>> = dao.getAllItems()

    suspend fun insert(item: ShoppingItem) {
        dao.insertItem(item)
    }

    suspend fun update(item: ShoppingItem) {
        dao.updateItem(item)
    }

    suspend fun deleteById(id: Int) {
        dao.deleteItemById(id)
    }

    suspend fun clear() {
        dao.clearAll()
    }
}
