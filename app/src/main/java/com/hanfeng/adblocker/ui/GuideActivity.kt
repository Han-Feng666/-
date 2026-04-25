package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.HanFeng.databinding.ActivityGuideBinding

class GuideActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val initialTopPadding = binding.guideRoot.paddingTop
        val initialBottomPadding = binding.guideRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.guideRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, systemBars.top + initialTopPadding, view.paddingRight, systemBars.bottom + initialBottomPadding)
            insets
        }
        binding.titleText.text = intent.getStringExtra(EXTRA_TITLE) ?: "使用说明"
        binding.contentText.text = intent.getStringExtra(EXTRA_CONTENT) ?: DEFAULT_GUIDE_CONTENT
        binding.btnBack.setOnClickListener { finish() }
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_CONTENT = "extra_content"
        private const val DEFAULT_GUIDE_CONTENT = "1. 点击“开启拦截”后，应用会建立本地 VPN，首次使用需授权 VPN 权限。\n2. 应用只拦截命中广告规则的 DNS 域名请求，对未命中的请求会正常转发，尽量保证不影响手机上网。\n3. 对于 TCP、非 DNS 的 UDP 以及其他正常网络流量，应用默认不修改内容，确保网络连接保持通畅。\n4. 左侧界面可导入、添加、分类查看和批量删除规则；右侧界面可查看拦截统计和排行榜。\n5. 导入规则文件后，应用会自动在后台分析可导入规则、例外规则、重复项和被跳过的高级修饰符。\n6. 规则导入兼容常见 Hosts 与 AdGuard 域名型规则；对依赖请求上下文的高级修饰符会自动跳过，优先保证网络正常。\n7. 黑白名单：默认对受管应用生效，可将指定应用加入白名单后完全放行，不参与拦截。\n8. 防绕过机制：内置常见加密 DNS 阻止策略，减少广告通过加密 DNS 绕过拦截的情况。\n9. 低功耗设计：仅在您主动开启时运行，关闭后立即停止拦截，不常驻后台。\n10. 如果某些应用仍显示旧广告，通常是本地缓存导致，建议清理对应应用缓存后再测试。\n11. 内置保守广告规则，App 首次启动会自动导入一批保守广告规则，主要覆盖常见广告 SDK / 广告投放域名，优先拦截小说类常见广告而不碰正常主站域名。\n12. 厂商识别与应用识别，内置较多国内外广告平台厂商识别。\n13. 应用如有BUG或有更好的建议请进群反馈。"

        fun createIntent(context: Context, title: String, content: String): Intent {
            return Intent(context, GuideActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CONTENT, content)
        }
    }
}
