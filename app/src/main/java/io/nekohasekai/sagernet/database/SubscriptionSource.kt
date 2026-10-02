/******************************************************************************
 *                                                                            *
 * Exclave Next addition: multi-source groups.                                *
 *                                                                            *
 * A SubscriptionSource is one subscription URL that belongs to a group.      *
 * A group may own several of them, side by side with manually added          *
 * profiles (ProxyEntity.sourceId == 0). The subscription metadata (link,     *
 * type, filters, UA, headers, traffic counters, lastUpdated, autoUpdate …)   *
 * is reused verbatim from the existing SubscriptionBean, stored as a Kryo    *
 * BLOB column exactly like ProxyGroup.subscription.                          *
 ******************************************************************************/

package io.nekohasekai.sagernet.database

import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.ktx.applyDefaultValues

@Entity(
    tableName = "subscription_sources",
    indices = [Index("groupId", name = "ss_groupId")]
)
data class SubscriptionSource(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var groupId: Long = 0L,
    var userOrder: Long = 0L,
    var name: String? = null,
    var subscription: SubscriptionBean? = null,
) : Serializable() {

    override fun initializeDefaultValues() {
        subscription?.applyDefaultValues()
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(0)
        output.writeLong(id)
        output.writeLong(groupId)
        output.writeLong(userOrder)
        output.writeString(name)
        subscription!!.serializeToBuffer(output)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        input.readInt() // version
        id = input.readLong()
        groupId = input.readLong()
        userOrder = input.readLong()
        name = input.readString()
        val sub = SubscriptionBean()
        this.subscription = sub
        sub.deserializeFromBuffer(input)
    }

    fun displayName(): String {
        return name.takeIf { !it.isNullOrEmpty() }
            ?: subscription?.link?.takeIf { it.isNotEmpty() }
            ?: id.toString()
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM subscription_sources ORDER BY userOrder")
        fun all(): List<SubscriptionSource>

        @Query("SELECT * FROM subscription_sources WHERE groupId = :groupId ORDER BY userOrder")
        fun byGroup(groupId: Long): List<SubscriptionSource>

        @Query("SELECT * FROM subscription_sources WHERE id = :sourceId")
        fun getById(sourceId: Long): SubscriptionSource?

        @Query("SELECT COUNT(*) FROM subscription_sources WHERE groupId = :groupId")
        fun countByGroup(groupId: Long): Long

        @Query("SELECT MAX(userOrder) + 1 FROM subscription_sources WHERE groupId = :groupId")
        fun nextOrder(groupId: Long): Long?

        @Insert
        fun create(source: SubscriptionSource): Long

        @Update
        fun update(source: SubscriptionSource)

        @Query("DELETE FROM subscription_sources WHERE id = :sourceId")
        fun deleteById(sourceId: Long)

        @Query("DELETE FROM subscription_sources WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)

        @Query("DELETE FROM subscription_sources WHERE groupId IN (:groupId)")
        fun deleteByGroup(groupId: LongArray)
    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<SubscriptionSource>() {
            override fun newInstance(): SubscriptionSource {
                return SubscriptionSource()
            }

            override fun newArray(size: Int): Array<SubscriptionSource?> {
                return arrayOfNulls(size)
            }
        }
    }
}
