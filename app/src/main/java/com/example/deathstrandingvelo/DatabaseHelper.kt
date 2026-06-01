package com.example.deathstrandingvelo

import android.annotation.SuppressLint // Добавили импорт!
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.osmdroid.util.GeoPoint

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "OdradekStorage.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // ИСПРАВИЛ ОПЕЧАТКУ: execSQL вместо execPath
        db.execSQL("CREATE TABLE storage_cells (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, max_weight REAL)")

        // Таблица Грузов
        db.execSQL("""
            CREATE TABLE cargo_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                description TEXT,
                weight REAL,
                is_fragile INTEGER,
                xp_reward INTEGER,
                status TEXT, -- 'IN_WAREHOUSE', 'ON_BIKE', 'DELIVERED', 'LOST'
                lat REAL,
                lon REAL
            )
        """.trimIndent())

        // Создаем базовый стеллаж при первом запуске
        db.execSQL("INSERT INTO storage_cells (name, max_weight) VALUES ('Главный стеллаж', 50.0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cargo_items")
        db.execSQL("DROP TABLE IF EXISTS storage_cells")
        onCreate(db)
    }

    // --- ФУНКЦИИ ДЛЯ РАБОТЫ СО СКЛАДОМ ---

    // Добавить новый груз на склад
    fun addCargoToWarehouse(item: CargoItem) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", item.name)
            put("description", item.description)
            put("weight", item.weightKg)
            put("is_fragile", if (item.isFragile) 1 else 0)
            put("xp_reward", item.xpReward)
            put("status", "IN_WAREHOUSE")
            put("lat", item.location.latitude)
            put("lon", item.location.longitude)
        }
        db.insert("cargo_items", null, values)
        db.close()
    }

    // Получить все грузы со статусом (например, "IN_WAREHOUSE" или "ON_BIKE")
    @SuppressLint("Range")
    fun getCargoByStatus(status: String): List<CargoItem> {
        val list = mutableListOf<CargoItem>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM cargo_items WHERE status = ?", arrayOf(status))

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(cursor.getColumnIndex("id"))
                val name = cursor.getString(cursor.getColumnIndex("name"))
                val desc = cursor.getString(cursor.getColumnIndex("description"))
                val weight = cursor.getDouble(cursor.getColumnIndex("weight"))
                val isFragile = cursor.getInt(cursor.getColumnIndex("is_fragile")) == 1
                val xp = cursor.getInt(cursor.getColumnIndex("xp_reward"))
                val lat = cursor.getDouble(cursor.getColumnIndex("lat"))
                val lon = cursor.getDouble(cursor.getColumnIndex("lon"))

                // Преобразуем статус БД в наш Enum
                val cargoStatus = when(status) {
                    "ON_BIKE" -> CargoStatus.PENDING
                    "DELIVERED" -> CargoStatus.COLLECTED
                    "LOST" -> CargoStatus.CANCELED
                    else -> CargoStatus.PENDING
                }

                list.add(CargoItem(id, name, desc, weight, isFragile, GeoPoint(lat, lon), xp, cargoStatus))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return list
    }

    // Переместить груз (со склада на велик или наоборот)
    fun updateCargoStatus(cargoId: Int, newStatus: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply { put("status", newStatus) }
        db.update("cargo_items", values, "id = ?", arrayOf(cargoId.toString()))
        db.close()
    }

    // Очистить доставленные грузы (чтобы не засорять базу)
    fun clearDelivered() {
        val db = this.writableDatabase
        db.delete("cargo_items", "status = ?", arrayOf("DELIVERED"))
        db.close()
    }

    // Узнать текущий вес на велосипеде
    @SuppressLint("Range")
    fun getBikeWeight(): Double {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT SUM(weight) FROM cargo_items WHERE status = 'ON_BIKE'", null)
        var weight = 0.0
        if (cursor.moveToFirst()) weight = cursor.getDouble(0)
        cursor.close()
        db.close()
        return weight
    }

    // АВТО-РАСПРЕДЕЛЕНИЕ: Забиваем велик под завязку!
    fun autoLoadBike(maxWeight: Double) {
        val currentWeight = getBikeWeight()
        var available = maxWeight - currentWeight
        val warehouseItems = getCargoByStatus("IN_WAREHOUSE")
        for (item in warehouseItems) {
            if (item.weightKg <= available) {
                updateCargoStatus(item.id, "ON_BIKE")
                available -= item.weightKg
            }
        }
    }

    // Добавить груз СРАЗУ НА ВЕЛИК (Для обратных грузов на маршруте)
    fun addCargoToBike(item: CargoItem) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", item.name)
            put("description", item.description)
            put("weight", item.weightKg)
            put("is_fragile", if (item.isFragile) 1 else 0)
            put("xp_reward", item.xpReward)
            put("status", "ON_BIKE") // Сразу на багажник!
            put("lat", item.location.latitude)
            put("lon", item.location.longitude)
        }
        db.insert("cargo_items", null, values)
        db.close()
    }
    // Снять все грузы с велосипеда обратно на склад
    fun unloadAllFromBike() {
        val db = this.writableDatabase
        val values = ContentValues().apply { put("status", "IN_WAREHOUSE") }
        db.update("cargo_items", values, "status = ?", arrayOf("ON_BIKE"))
        db.close()
    }
}