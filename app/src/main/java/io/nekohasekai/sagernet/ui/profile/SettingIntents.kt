package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.nekohasekai.sagernet.database.ProxyEntity
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity

// 节点类型对应的编辑器，编辑已有节点和「新建节点」菜单共用
fun settingActivityOf(type: Int): Class<out Activity> = when (type) {
    ProxyEntity.TYPE_SOCKS -> SocksSettingsActivity::class.java
    ProxyEntity.TYPE_HTTP -> HttpSettingsActivity::class.java
    ProxyEntity.TYPE_SS -> ShadowsocksSettingsActivity::class.java
    ProxyEntity.TYPE_VMESS -> VMessSettingsActivity::class.java
    ProxyEntity.TYPE_TROJAN -> TrojanSettingsActivity::class.java
    ProxyEntity.TYPE_TROJAN_GO -> TrojanGoSettingsActivity::class.java
    ProxyEntity.TYPE_MIERU -> MieruSettingsActivity::class.java
    ProxyEntity.TYPE_NAIVE -> NaiveSettingsActivity::class.java
    ProxyEntity.TYPE_HYSTERIA -> HysteriaSettingsActivity::class.java
    ProxyEntity.TYPE_SSH -> SSHSettingsActivity::class.java
    ProxyEntity.TYPE_WG -> WireGuardSettingsActivity::class.java
    ProxyEntity.TYPE_TUIC -> TuicSettingsActivity::class.java
    ProxyEntity.TYPE_SHADOWTLS -> ShadowTLSSettingsActivity::class.java
    ProxyEntity.TYPE_ANYTLS -> AnyTLSSettingsActivity::class.java
    ProxyEntity.TYPE_CHAIN -> ChainSettingsActivity::class.java
    ProxyEntity.TYPE_CONFIG -> ConfigSettingActivity::class.java
    else -> throw IllegalArgumentException()
}

// Moved from ProxyEntity so the database package no longer depends on ui.
fun ProxyEntity.settingIntent(ctx: Context): Intent =
    Intent(ctx, settingActivityOf(type)).apply {
        putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, id)
    }
