/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.mieru

import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.dialAddress
import io.nekohasekai.sagernet.fmt.dialPort
import moe.matsuri.nb4a.utils.JavaUtil.gson

// port 是本机 socks 入站的端口，target 是跳实例的拨号目标（经映射时拨本机的映射入站，否则拨服务器本身）
fun MieruBean.buildMieruConfig(port: Int, target: ExternalDialTarget, settings: ExternalCoreSettings): String {
    // 值为 null 的键由 Gson 省略，与 org.json 的 put(键, null) 删除键一致
    val serverInfo = arrayListOf(
        linkedMapOf<String, Any?>(
            "ipAddress" to target.dialAddress(this),
            "portBindings" to arrayListOf(
                linkedMapOf<String, Any?>(
                    "port" to target.dialPort(this),
                    "protocol" to protocol,
                )
            ),
        )
    )
    return gson.toJson(
        linkedMapOf<String, Any?>(
            "activeProfile" to "default",
            "socks5Port" to port,
            // 档位与 ConfigBuilder 的 sing-box 映射一致；mieru 的写法是大写
            "loggingLevel" to when (settings.logLevel) {
                0 -> "FATAL"
                1 -> "WARN"
                3 -> "DEBUG"
                4 -> "TRACE"
                else -> "INFO"
            },
            "profiles" to arrayListOf(
                linkedMapOf<String, Any?>(
                    "profileName" to "default",
                    "user" to linkedMapOf<String, Any?>(
                        "name" to username,
                        "password" to password,
                    ),
                    "servers" to serverInfo,
                    "mtu" to mtu,
                )
            ),
        )
    )
}
