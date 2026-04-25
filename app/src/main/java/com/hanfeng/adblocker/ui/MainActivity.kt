package com.HanFeng.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.R
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.WhitelistRepository
import com.HanFeng.databinding.ActivityMainBinding
import com.HanFeng.service.AdBlockVpnService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSION_PREFS = "permission_flow"
        private const val KEY_FIRST_LAUNCH_CHECK_DONE = "first_launch_check_done"
    }

    private lateinit var binding: ActivityMainBinding
    private var pendingVpnStartAfterPermission = false
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            pendingVpnStartAfterPermission = false
            Toast.makeText(this, "未授予 VPN 权限，无法开启拦截", Toast.LENGTH_SHORT).show()
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            showNotificationPermissionDeniedDialog()
        }
        if (pendingVpnStartAfterPermission) {
            pendingVpnStartAfterPermission = false
            continueVpnStartFlow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.pager.adapter = MainPagerAdapter(this)
        binding.pager.offscreenPageLimit = 1
        binding.pager.currentItem = 1
        requestRequiredPermissionsOnFirstLaunch()
        preloadBundledRules()
    }

    fun requestToggleVpn() {
        if (AdBlockVpnService.isRunning) {
            stopVpnAndExit()
            return
        }
        if (needsNotificationPermission()) {
            pendingVpnStartAfterPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueVpnStartFlow()
    }

    private fun requestRequiredPermissionsOnFirstLaunch() {
        val prefs = getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FIRST_LAUNCH_CHECK_DONE, false)) return
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_CHECK_DONE, true).apply()

        lifecycleScope.launch {
            val hasAppListAccess = withContext(Dispatchers.Default) {
                WhitelistRepository.hasAppListAccess(applicationContext)
            }
            if (!hasAppListAccess) {
                showAppListPermissionDialog()
            }
            if (needsNotificationPermission()) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun preloadBundledRules() {
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.Default) {
                RuleRepository.ensureBundledReferenceRules(applicationContext)
            }
            if (imported > 0) {
                LogRepository.append(this@MainActivity, "Preloaded $imported bundled safe rules")
            }
        }
    }

    private fun continueVpnStartFlow() {
        runCatching {
            VpnService.prepare(this)
        }.onSuccess { prepareIntent ->
            if (prepareIntent != null) {
                vpnLauncher.launch(prepareIntent)
            } else {
                startVpnService()
            }
        }.onFailure {
            LogRepository.append(this, "VPN prepare failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(this, "无法申请 VPN 权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    private fun startVpnService() {
        val serviceIntent = Intent(this, AdBlockVpnService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }.onSuccess {
            Toast.makeText(this, "正在开启拦截", Toast.LENGTH_SHORT).show()
        }.onFailure {
            AdBlockVpnService.isRunning = false
            LogRepository.append(this, "Start VPN service failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(this, "开启拦截失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVpnAndExit() {
        AdBlockVpnService.isRunning = false
        startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP))
        Toast.makeText(this, "停止后将退出应用", Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({
            finishAffinity()
        }, 350)
    }

    fun showGuideDialog() {
        runCatching {
            startActivity(
                GuideActivity.createIntent(
                    this,
                    "使用说明",
                    "1. 首次开启拦截时，需要授权系统 VPN 权限；Android 13 及以上机型需允许通知权限，手机设置内手动允许获取应用列表权限，用于稳定显示前台服务通知。\n" +
                        "2. 本应用采用本地 VPN + DNS/IP 级拦截方案，不做 HTTPS 解密，只拦截命中广告规则的域名，其余正常流量默认透传，不影响用户网络体验。\n" +
                        "3. 对于 TCP、非 DNS 的 UDP 以及其他正常网络流量，应用默认不修改内容，尽量保证手机联网稳定、不断网。\n" +
                        "4. 内置保守广告规则，首次启动会自动导入一批常见广告 SDK、广告投放、追踪与小说类广告域名规则，优先拦截广告而不碰正常主站域名。\n" +
                        "5. 规则页支持手动添加、导入规则文件、导入并分析、筛选非广告规则，以及分类查看和批量删除规则。\n" +
                        "6. 规则导入兼容常见 Hosts、AdGuard 域名型规则及部分结构化域名规则；对 regex、keyword、路径规则和复杂修饰符会自动跳过，优先保证网络正常。\n" +
                        "7. 可疑域名页会记录最近出现的可疑域名、出现次数、最近命中的应用、最近厂商分类和小说专项命中次数，方便继续补规则。\n" +
                        "8. 黑白名单功能：默认对受管应用生效，可将指定应用加入白名单后完全放行，不参与拦截；如果某个应用联网异常，建议先加入白名单测试。\n" +
                        "9. 防绕过机制：内置常见加密 DNS、DoH、DoT、HTTPDNS 相关阻止策略，减少广告通过加密 DNS 绕过拦截的情况。\n" +
                        "10. 低功耗设计：仅在您主动开启时运行，关闭后立即停止拦截，不常驻后台；可疑样本采样也做了节流，尽量减少对手机流畅度的影响。\n" +
                        "11. 如果某些应用仍显示旧广告，通常是本地缓存或旧 DNS 缓存导致，建议清理对应应用缓存、重开应用后再测试。\n" +
                        "12. 应用如有 BUG或你有更好的建议，请进群反馈。"
                )
            )
        }.onFailure {
            LogRepository.append(this, "Guide dialog failed: ${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(this, "打开使用说明失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun openWhitelist() {
        startActivity(Intent(this, WhitelistActivity::class.java))
    }

    fun openTrafficCardPage() {
        openExternal("https://h5.lot-ml.com/ProductEn/Index/120d6424545c4be5")
    }

    fun joinQqGroup() {
        openExternal("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=573309536&card_type=group&source=qrcode")
    }

    fun shareLogs() {
        val uri = LogRepository.exportZip(this)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出日志"))
    }

    fun openRuleDownloadPage() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("提取码", "aehi"))
        Toast.makeText(this, "提取码已复制", Toast.LENGTH_SHORT).show()
        openExternal("https://hanfengnb.lanzoul.com/b0j1elsrg")
    }

    private fun openExternal(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "未找到可用应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppDetailsSettings() {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }.onFailure {
            Toast.makeText(this, "无法打开应用设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNotificationPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
            .setTitle("通知权限未开启")
            .setMessage("前台服务通知需要通知权限才能更稳定显示。若已拒绝，请到系统设置中手动允许该权限。")
            .setPositiveButton("去设置") { _: DialogInterface, _: Int -> openAppDetailsSettings() }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun showAppListPermissionDialog() {
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HanFeng_Dialog)
            .setTitle("需要应用列表权限")
            .setMessage("当前手机可能限制了应用列表读取，黑白名单与应用识别可能不完整。请到系统设置中手动允许“获取应用列表”或类似权限。")
            .setPositiveButton("去设置") { _: DialogInterface, _: Int -> openAppDetailsSettings() }
            .setNegativeButton("稍后再说", null)
            .show()
    }

}
