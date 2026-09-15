package io.nekohasekai.sagernet.database

import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.toUri
import io.nekohasekai.sagernet.fmt.hysteria.*
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.naive.toUri
import io.nekohasekai.sagernet.fmt.shadowsocks.*
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.trojan_go.toUri
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.toUri
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.app
import moe.matsuri.nb4a.SingBoxOptions.MultiplexOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildMihomoConfig
import moe.matsuri.nb4a.proxy.anytls.toUri
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.*
import java.io.File

// Fields owned by the profile editor. Room's partial-entity update keeps
// runtime columns (order, traffic and test status) written while the editor is open.
data class ProxyEditableFields(
    val id: Long,
    val type: Int,
    val core: Int,
    val socksBean: SOCKSBean?,
    val httpBean: HttpBean?,
    val ssBean: ShadowsocksBean?,
    val vmessBean: VMessBean?,
    val trojanBean: TrojanBean?,
    val trojanGoBean: TrojanGoBean?,
    val mieruBean: MieruBean?,
    val naiveBean: NaiveBean?,
    val hysteriaBean: HysteriaBean?,
    val tuicBean: TuicBean?,
    val sshBean: SSHBean?,
    val wgBean: WireGuardBean?,
    val shadowTLSBean: ShadowTLSBean?,
    val anyTLSBean: AnyTLSBean?,
    val chainBean: ChainBean?,
    val nekoBean: NekoBean?,
    val configBean: ConfigBean?,
) {
    constructor(entity: ProxyEntity) : this(
        entity.id,
        entity.type,
        entity.core,
        entity.socksBean,
        entity.httpBean,
        entity.ssBean,
        entity.vmessBean,
        entity.trojanBean,
        entity.trojanGoBean,
        entity.mieruBean,
        entity.naiveBean,
        entity.hysteriaBean,
        entity.tuicBean,
        entity.sshBean,
        entity.wgBean,
        entity.shadowTLSBean,
        entity.anyTLSBean,
        entity.chainBean,
        entity.nekoBean,
        entity.configBean,
    )
}

@Entity(
    tableName = "proxy_entities", indices = [Index("groupId", name = "groupId")]
)
data class ProxyEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var groupId: Long = 0L,
    var type: Int = 0,
    var userOrder: Long = 0L,
    var tx: Long = 0L,
    var rx: Long = 0L,
    var status: Int = 0,
    var ping: Int = 0,
    var uuid: String = "",
    var error: String? = null,
    // 0 = auto (by protocol), 1 = sing-box, 2 = xray, 3 = mihomo
    @ColumnInfo(defaultValue = "0") var core: Int = 0,
    var socksBean: SOCKSBean? = null,
    var httpBean: HttpBean? = null,
    var ssBean: ShadowsocksBean? = null,
    var vmessBean: VMessBean? = null,
    var trojanBean: TrojanBean? = null,
    var trojanGoBean: TrojanGoBean? = null,
    var mieruBean: MieruBean? = null,
    var naiveBean: NaiveBean? = null,
    var hysteriaBean: HysteriaBean? = null,
    var tuicBean: TuicBean? = null,
    var sshBean: SSHBean? = null,
    var wgBean: WireGuardBean? = null,
    var shadowTLSBean: ShadowTLSBean? = null,
    var anyTLSBean: AnyTLSBean? = null,
    var chainBean: ChainBean? = null,
    var nekoBean: NekoBean? = null,
    var configBean: ConfigBean? = null,
) : Serializable() {

    companion object {
        const val TYPE_SOCKS = 0
        const val TYPE_HTTP = 1
        const val TYPE_SS = 2
        const val TYPE_VMESS = 4
        const val TYPE_TROJAN = 6

        const val TYPE_SSH = 17
        const val TYPE_WG = 18

        const val TYPE_TROJAN_GO = 7
        const val TYPE_NAIVE = 9
        const val TYPE_HYSTERIA = 15
        const val TYPE_SHADOWTLS = 19
        const val TYPE_TUIC = 20
        const val TYPE_MIERU = 21
        const val TYPE_ANYTLS = 22

        const val TYPE_CONFIG = 998
        const val TYPE_NEKO = 999

        const val TYPE_CHAIN = 8

        // core selection (ProxyEntity.core)
        const val CORE_AUTO = 0
        const val CORE_SING_BOX = 1
        const val CORE_XRAY = 2
        const val CORE_MIHOMO = 3

        val chainName by lazy { app.getString(R.string.proxy_chain) }

        @JvmField
        val CREATOR = object : CREATOR<ProxyEntity>() {

            override fun newInstance(): ProxyEntity {
                return ProxyEntity()
            }

            override fun newArray(size: Int): Array<ProxyEntity?> {
                return arrayOfNulls(size)
            }
        }
    }

    @Ignore
    @Transient
    var dirty: Boolean = false

    override fun initializeDefaultValues() {
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(1)

        output.writeLong(id)
        output.writeLong(groupId)
        output.writeInt(type)
        output.writeLong(userOrder)
        output.writeLong(tx)
        output.writeLong(rx)
        output.writeInt(status)
        output.writeInt(ping)
        output.writeString(uuid)
        output.writeString(error)

        val data = KryoConverters.serialize(requireBean())
        output.writeVarInt(data.size, true)
        output.writeBytes(data)

        output.writeBoolean(dirty)
        output.writeInt(core)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        val version = input.readInt()

        id = input.readLong()
        groupId = input.readLong()
        type = input.readInt()
        userOrder = input.readLong()
        tx = input.readLong()
        rx = input.readLong()
        status = input.readInt()
        ping = input.readInt()
        uuid = input.readString()
        error = input.readString()
        putByteArray(input.readBytes(input.readVarInt(true)))

        dirty = input.readBoolean()
        if (version >= 1) {
            core = input.readInt()
        }
    }


    // Share links and backup records: strict, so damaged bytes throw instead of
    // importing as a half-filled bean (the Room column converters stay lenient)
    fun putByteArray(byteArray: ByteArray) {
        putBeanBytes(byteArray)
    }

    fun displayType(): String = protocolDisplayType()

    fun displayName() = requireBean().displayName()
    fun displayAddress() = requireBean().displayAddress()

    fun requireBean(): AbstractBean {
        return beanForType() ?: error("Null ${displayType()} profile")
    }

    fun haveLink(): Boolean {
        return typeHasLink()
    }

    fun haveStandardLink(): Boolean {
        return when (requireBean()) {
            is SSHBean -> false
            is WireGuardBean -> false
            is ShadowTLSBean -> false
            is NekoBean -> false
            is ConfigBean -> false
            else -> true
        }
    }

    fun toStdLink(compact: Boolean = false): String = with(requireBean()) {
        when (this) {
            is SOCKSBean -> toUri()
            is HttpBean -> toUri()
            is ShadowsocksBean -> toUri()
            is VMessBean -> toUriVMessVLESSTrojan(false)
            is TrojanBean -> toUriVMessVLESSTrojan(true)
            is TrojanGoBean -> toUri()
            is NaiveBean -> toUri()
            is HysteriaBean -> toUri()
            is TuicBean -> toUri()
            is AnyTLSBean -> toUri()
            is NekoBean -> ""
            else -> toUniversalLink()
        }
    }

    fun exportConfig(): Pair<String, String> {
        var name = "${requireBean().displayName()}.json"

        return with(requireBean()) {
            StringBuilder().apply {
                val config = buildConfig(this@ProxyEntity, forExport = true)
                append(config.config)

                if (!config.externalIndex.all { it.chain.isEmpty() }) {
                    name = "profiles.txt"
                }

                for ((chain) in config.externalIndex) {
                    chain.entries.forEachIndexed { index, (port, profile) ->
                        when (val bean = profile.requireBean()) {
                            is TrojanGoBean -> {
                                append("\n\n")
                                append(bean.buildTrojanGoConfig(port))
                            }

                            is MieruBean -> {
                                append("\n\n")
                                append(bean.buildMieruConfig(port))
                            }

                            is NaiveBean -> {
                                append("\n\n")
                                append(bean.buildNaiveConfig(port))
                            }

                            is HysteriaBean -> {
                                append("\n\n")
                                var caFile: File? = null
                                append(bean.buildHysteria1Config(port) {
                                    File.createTempFile("hysteria_", ".ca", app.cacheDir)
                                        .also { caFile = it }
                                })
                                // the exported JSON keeps the path, but the temp
                                // file itself must not linger in cacheDir
                                caFile?.let { runCatching { it.delete() } }
                            }

                            is VMessBean -> {
                                append("\n\n")
                                append(buildXrayConfig(bean, port))
                            }

                            is AnyTLSBean -> {
                                append("\n\n")
                                append(buildMihomoConfig(bean, port))
                            }
                        }
                    }
                }
            }.toString()
        } to name
    }

    fun resolvedCore(): Int {
        if (core != CORE_AUTO) return core
        return coreForType()
    }

    fun needExternal(): Boolean {
        return needsExternalCore()
    }

    fun singMux(): MultiplexOptions? {
        return singMuxForType()
    }

    fun putBean(bean: AbstractBean): ProxyEntity {
        return assignBean(bean)
    }

    @androidx.room.Dao
    interface Dao {

        @Query("select * from proxy_entities")
        fun getAll(): List<ProxyEntity>

        @Query("SELECT * FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getByGroup(groupId: Long): List<ProxyEntity>

        @Query("SELECT * FROM proxy_entities WHERE id in (:proxyIds)")
        fun getEntities(proxyIds: List<Long>): List<ProxyEntity>

        @Query("SELECT COUNT(*) FROM proxy_entities WHERE groupId = :groupId")
        fun countByGroup(groupId: Long): Long

        @Query("SELECT  MAX(userOrder) + 1 FROM proxy_entities WHERE groupId = :groupId")
        fun nextOrder(groupId: Long): Long?

        @Query("SELECT * FROM proxy_entities WHERE id = :proxyId")
        fun getById(proxyId: Long): ProxyEntity?

        @Query("DELETE FROM proxy_entities WHERE id IN (:proxyId)")
        fun deleteById(proxyId: Long): Int

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)

        @Query("DELETE FROM proxy_entities WHERE groupId in (:groupId)")
        fun deleteByGroup(groupId: LongArray)

        @Delete
        fun deleteProxy(proxy: ProxyEntity): Int

        @Delete
        fun deleteProxy(proxies: List<ProxyEntity>): Int

        @Update
        fun updateProxy(proxy: ProxyEntity): Int

        @Update(entity = ProxyEntity::class)
        fun updateEditableFields(fields: ProxyEditableFields): Int

        // tx/rx only: callers hold a snapshot read at VPN/test start, and a
        // full-row @Update would roll back concurrent edits on the other columns
        @Query("UPDATE proxy_entities SET tx = :tx, rx = :rx WHERE id = :id")
        fun updateTraffic(id: Long, tx: Long, rx: Long)

        @Query("UPDATE proxy_entities SET status = :status, ping = :ping, error = :error WHERE id = :id")
        fun updateStatus(id: Long, status: Int, ping: Int, error: String?)

        // userOrder only: drag-sort holds a stale snapshot, and a full-row
        // @Update would roll back tx/rx persisted meanwhile by TrafficLooper
        @Query("UPDATE proxy_entities SET userOrder = :order WHERE id = :id")
        fun updateOrder(id: Long, order: Long)

        @Query("UPDATE proxy_entities SET groupId = :groupId WHERE id = :id")
        fun updateGroup(id: Long, groupId: Long): Int

        @Insert
        fun addProxy(proxy: ProxyEntity): Long

        @Insert
        fun insert(proxies: List<ProxyEntity>)

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteAll(groupId: Long): Int

        @Query("DELETE FROM proxy_entities")
        fun reset()

    }

    override fun describeContents(): Int {
        return 0
    }
}
