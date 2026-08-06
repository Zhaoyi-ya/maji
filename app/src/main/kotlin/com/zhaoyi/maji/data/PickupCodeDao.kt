package com.zhaoyi.maji.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PickupCodeDao {
    @Insert
    suspend fun insert(c: PickupCode)

    @Update
    suspend fun update(c: PickupCode)

    @Delete
    suspend fun delete(c: PickupCode)

    /** 未完成的排在前面，同组内按创建时间倒序 */
    @Query("SELECT * FROM pickup_codes ORDER BY doneAt IS NOT NULL, createdAt DESC")
    fun getAll(): Flow<List<PickupCode>>

    @Query("SELECT * FROM pickup_codes WHERE id = :id")
    suspend fun getById(id: String): PickupCode?

    @Query("SELECT * FROM pickup_codes WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PickupCode>

    @Query("UPDATE pickup_codes SET onIsland = 0")
    suspend fun clearAllIslandFlags()

    /**
     * 定向更新完成状态，绝不能用 @Update 传空壳对象——
     * Room 的 @Update 是按主键整行覆盖，会把 code/merchant/item 等字段一起清空。
     */
    @Query("UPDATE pickup_codes SET doneAt = :doneAt, onIsland = :onIsland WHERE id = :id")
    suspend fun setDoneState(id: String, doneAt: Long?, onIsland: Boolean)

    /** 定向更新上岛标记，同样避免整行覆盖 */
    @Query("UPDATE pickup_codes SET onIsland = :onIsland WHERE id = :id")
    suspend fun setOnIsland(id: String, onIsland: Boolean)

    @Query("DELETE FROM pickup_codes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** 清掉已完成超过 7 天的记录 */
    @Query("DELETE FROM pickup_codes WHERE doneAt IS NOT NULL AND doneAt < :before")
    suspend fun purgeDoneBefore(before: Long)
}
