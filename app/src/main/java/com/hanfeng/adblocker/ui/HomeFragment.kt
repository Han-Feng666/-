package com.HanFeng.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.HanFeng.R
import com.HanFeng.service.AdBlockVpnService

class HomeFragment : Fragment(R.layout.fragment_home) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity = requireActivity() as MainActivity
        view.findViewById<ImageView>(R.id.homeBackground).applyCustomAssetBackground("custom/home_background")
        val homeContent = view.findViewById<View>(R.id.homeContent)
        val toggle = view.findViewById<Button>(R.id.btnToggle)
        val initialTopPadding = homeContent.paddingTop
        val initialBottomPadding = homeContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(homeContent) { content, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(
                content.paddingLeft,
                initialTopPadding + systemBars.top,
                content.paddingRight,
                initialBottomPadding + systemBars.bottom
            )
            insets
        }
        toggle.setOnClickListener {
            activity.requestToggleVpn()
            updateToggleText(toggle)
            toggle.postDelayed({ updateToggleText(toggle) }, 300)
            toggle.postDelayed({ updateToggleText(toggle) }, 1000)
        }
        view.findViewById<Button>(R.id.btnGuide).setOnClickListener { activity.showGuideDialog() }
        view.findViewById<Button>(R.id.btnWhitelist).setOnClickListener { activity.openWhitelist() }
        updateToggleText(toggle)
        view.findViewById<View>(R.id.homeButtons).apply {
            post {
                val params = layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params.verticalBias = 0.53f
                layoutParams = params
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<Button>(R.id.btnToggle)?.let(::updateToggleText)
    }

    private fun updateToggleText(toggle: Button) {
        toggle.text = if (AdBlockVpnService.isRunning) "停止拦截" else "开启拦截"
    }
}
