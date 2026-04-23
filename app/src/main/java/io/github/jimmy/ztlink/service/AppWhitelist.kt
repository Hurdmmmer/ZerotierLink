package io.github.jimmy.ztlink.service

import android.content.pm.PackageManager
import android.net.VpnService
import android.util.Log

/**
 * 应用白名单配置。
 *
 * 说明：
 * 1. 白名单中的应用会通过 `VpnService.Builder.addDisallowedApplication` 被排除；
 * 2. 被排除应用会自动跳过该 App 的内核转发；
 * 3. 其流量会回到系统默认网络路径。
 *
 * @property userWhitelistPackages 用户配置的白名单包名列表。
 * @property includeBuiltInWhitelistPackages 是否附加内置白名单包名。
 */
data class AppWhitelistConfig(
    val userWhitelistPackages: List<String> = emptyList(),
    val includeBuiltInWhitelistPackages: Boolean = true,
)

/**
 * 应用白名单配置应用结果。
 *
 * @property requestedPackages 请求应用的包名列表（规范化后）。
 * @property appliedPackages 成功应用的包名列表。
 * @property ignoredPackages 被忽略的非法包名。
 * @property failedPackages 调用 Builder 失败的包名。
 */
data class AppWhitelistApplyResult(
    val requestedPackages: List<String>,
    val appliedPackages: List<String>,
    val ignoredPackages: List<String>,
    val failedPackages: List<String>,
) {
    /**
     * 是否全部成功。
     */
    val isSuccess: Boolean
        get() = ignoredPackages.isEmpty() && failedPackages.isEmpty()
}

/**
 * 内置白名单包名集合。
 *
 * 按当前产品策略默认仅绕过 Android Auto。
 */
object BuiltInWhitelistApps {
    val packages: List<String> = listOf(
        // Keep only Android Auto bypass by default.
        "com.google.android.projection.gearhead",
    )
}

/**
 * 把应用白名单应用到 VPN Builder。
 */
class AppWhitelistApplier(
    private val logTag: String = "AppWhitelistApplier",
) {
    /**
     * 应用白名单配置。
     *
     * @param builder VPN 构建器。
     * @param policy 应用白名单配置。
     * @return 应用结果。
     */
    fun apply(
        builder: VpnService.Builder,
        policy: AppWhitelistConfig,
    ): AppWhitelistApplyResult {
        val ignoredPackages = mutableListOf<String>()
        val requestedPackages = mergePackages(policy, ignoredPackages)
        val appliedPackages = mutableListOf<String>()
        val failedPackages = mutableListOf<String>()

        requestedPackages.forEach { packageName ->
            try {
                // 关键逻辑：addDisallowedApplication 表示该应用“绕过 VPN”。
                builder.addDisallowedApplication(packageName)
                appliedPackages += packageName
            } catch (error: PackageManager.NameNotFoundException) {
                failedPackages += packageName
                Log.w(logTag, "Whitelist package not found: $packageName", error)
            } catch (error: Throwable) {
                failedPackages += packageName
                Log.w(logTag, "Failed to apply whitelist package: $packageName", error)
            }
        }

        return AppWhitelistApplyResult(
            requestedPackages = requestedPackages,
            appliedPackages = appliedPackages,
            ignoredPackages = ignoredPackages,
            failedPackages = failedPackages,
        )
    }

    /**
     * 合并内置白名单包名与用户包名，并进行规范化。
     *
     * @param policy 输入策略。
     * @param ignoredPackages 输出被忽略包名列表。
     * @return 可用于 Builder 的包名列表。
     */
    private fun mergePackages(
        policy: AppWhitelistConfig,
        ignoredPackages: MutableList<String>,
    ): List<String> {
        val source = buildList {
            if (policy.includeBuiltInWhitelistPackages) {
                addAll(BuiltInWhitelistApps.packages)
            }
            addAll(policy.userWhitelistPackages)
        }
        return source.asSequence()
            .map { it.trim() }
            .onEach { candidate ->
                if (candidate.isNotEmpty() && !PACKAGE_NAME_REGEX.matches(candidate)) {
                    ignoredPackages += candidate
                }
            }
            .filter { it.isNotEmpty() }
            .filter { PACKAGE_NAME_REGEX.matches(it) }
            .distinct()
            .toList()
    }

    companion object {
        /**
         * Android 包名校验规则（用于基本过滤）。
         */
        private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
