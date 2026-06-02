package com.example.deathstrandingvelo

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.osmdroid.util.GeoPoint

// ВНИМАНИЕ: Версия БД изменена на 2!
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "OdradekStorage.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE storage_cells (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, max_weight REAL)")

        db.execSQL("""
            CREATE TABLE cargo_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                description TEXT,
                weight REAL,
                is_fragile INTEGER,
                xp_reward INTEGER,
                money_reward INTEGER, -- НОВАЯ КОЛОНКА ДЛЯ ДЕНЕГ
                status TEXT,
                lat REAL,
                lon REAL
            )
        """.trimIndent())

        db.execSQL("INSERT INTO storage_cells (name, max_weight) VALUES ('Главный стеллаж', 50.0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cargo_items")
        db.execSQL("DROP TABLE IF EXISTS storage_cells")
        onCreate(db)
    }

    fun addCargoToWarehouse(item: CargoItem) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", item.name)
            put("description", item.description)
            put("weight", item.weightKg)
            put("is_fragile", if (item.isFragile) 1 else 0)
            put("xp_reward", item.xpReward)
            put("money_reward", item.moneyReward) // Сохраняем деньги
            put("status", "IN_WAREHOUSE")
            put("lat", item.location.latitude)
            put("lon", item.location.longitude)
        }
        db.insert("cargo_items", null, values)
        db.close()
    }

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
                val money = cursor.getInt(cursor.getColumnIndex("money_reward")) // Читаем деньги
                val lat = cursor.getDouble(cursor.getColumnIndex("lat"))
                val lon = cursor.getDouble(cursor.getColumnIndex("lon"))

                val cargoStatus = when(status) {
                    "ON_BIKE", "IN_WAREHOUSE", "OFFERED", "ACCEPTED" -> CargoStatus.PENDING
                    "DELIVERED" -> CargoStatus.COLLECTED
                    "LOST" -> CargoStatus.CANCELED
                    else -> CargoStatus.PENDING
                }

                list.add(CargoItem(id, name, desc, weight, isFragile, GeoPoint(lat, lon), xp, money, cargoStatus))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return list
    }

    fun updateCargoStatus(cargoId: Int, newStatus: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply { put("status", newStatus) }
        db.update("cargo_items", values, "id = ?", arrayOf(cargoId.toString()))
        db.close()
    }

    fun clearDelivered() {
        val db = this.writableDatabase
        db.delete("cargo_items", "status = ?", arrayOf("DELIVERED"))
        db.close()
    }

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

    fun clearAllPendingCargo() {
        val db = this.writableDatabase
        db.delete("cargo_items", "status IN ('IN_WAREHOUSE', 'ON_BIKE', 'OFFERED', 'ACCEPTED')", null)
        db.close()
    }

    fun addCargoToBike(item: CargoItem) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", item.name)
            put("description", item.description)
            put("weight", item.weightKg)
            put("is_fragile", if (item.isFragile) 1 else 0)
            put("xp_reward", item.xpReward)
            put("money_reward", item.moneyReward) // Сохраняем деньги
            put("status", "ON_BIKE")
            put("lat", item.location.latitude)
            put("lon", item.location.longitude)
        }
        db.insert("cargo_items", null, values)
        db.close()
    }

    fun unloadAllFromBike() {
        val db = this.writableDatabase
        val values = ContentValues().apply { put("status", "ACCEPTED") }
        db.update("cargo_items", values, "status = ?", arrayOf("ON_BIKE"))
        db.close()
    }

    fun addCargoWithStatus(item: CargoItem, customStatus: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", item.name)
            put("description", item.description)
            put("weight", item.weightKg)
            put("is_fragile", if (item.isFragile) 1 else 0)
            put("xp_reward", item.xpReward)
            put("money_reward", item.moneyReward) // Сохраняем деньги
            put("status", customStatus)
            put("lat", item.location.latitude)
            put("lon", item.location.longitude)
        }
        db.insert("cargo_items", null, values)
        db.close()
    }

    fun clearOfferedContracts() {
        val db = this.writableDatabase
        db.delete("cargo_items", "status = ?", arrayOf("OFFERED"))
        db.close()
    }
}