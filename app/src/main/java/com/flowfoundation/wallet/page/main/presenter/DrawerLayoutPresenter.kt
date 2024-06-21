package com.flowfoundation.wallet.page.main.presenter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.core.graphics.ColorUtils
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.flowfoundation.wallet.R
import com.flowfoundation.wallet.base.presenter.BasePresenter
import com.flowfoundation.wallet.databinding.LayoutMainDrawerLayoutBinding
import com.flowfoundation.wallet.manager.account.AccountManager
import com.flowfoundation.wallet.manager.account.OnWalletDataUpdate
import com.flowfoundation.wallet.manager.account.WalletFetcher
import com.flowfoundation.wallet.manager.app.chainNetWorkString
import com.flowfoundation.wallet.manager.app.isDeveloperMode
import com.flowfoundation.wallet.manager.app.isPreviewnet
import com.flowfoundation.wallet.manager.app.isTestnet
import com.flowfoundation.wallet.manager.childaccount.ChildAccount
import com.flowfoundation.wallet.manager.childaccount.ChildAccountList
import com.flowfoundation.wallet.manager.childaccount.ChildAccountUpdateListenerCallback
import com.flowfoundation.wallet.manager.emoji.AccountEmojiManager
import com.flowfoundation.wallet.manager.emoji.OnEmojiUpdate
import com.flowfoundation.wallet.manager.evm.EVMWalletManager
import com.flowfoundation.wallet.manager.wallet.WalletManager
import com.flowfoundation.wallet.network.model.WalletListData
import com.flowfoundation.wallet.page.dialog.accounts.AccountSwitchDialog
import com.flowfoundation.wallet.page.evm.EnableEVMActivity
import com.flowfoundation.wallet.page.main.MainActivityViewModel
import com.flowfoundation.wallet.page.main.model.MainDrawerLayoutModel
import com.flowfoundation.wallet.page.main.refreshWalletList
import com.flowfoundation.wallet.page.main.widget.NetworkPopupMenu
import com.flowfoundation.wallet.page.restore.WalletRestoreActivity
import com.flowfoundation.wallet.utils.ScreenUtils
import com.flowfoundation.wallet.utils.extensions.capitalizeV2
import com.flowfoundation.wallet.utils.extensions.dp2px
import com.flowfoundation.wallet.utils.extensions.gone
import com.flowfoundation.wallet.utils.extensions.res2String
import com.flowfoundation.wallet.utils.extensions.res2color
import com.flowfoundation.wallet.utils.extensions.setVisible
import com.flowfoundation.wallet.utils.extensions.visible
import com.flowfoundation.wallet.utils.findActivity
import com.flowfoundation.wallet.utils.ioScope
import com.flowfoundation.wallet.utils.loadAvatar
import com.flowfoundation.wallet.utils.parseAvatarUrl
import com.flowfoundation.wallet.utils.svgToPng
import com.flowfoundation.wallet.utils.uiScope
import com.flowfoundation.wallet.widgets.DialogType
import com.flowfoundation.wallet.widgets.ProgressDialog
import com.flowfoundation.wallet.widgets.SwitchNetworkDialog

class DrawerLayoutPresenter(
    private val drawer: DrawerLayout,
    private val binding: LayoutMainDrawerLayoutBinding,
) : BasePresenter<MainDrawerLayoutModel>, ChildAccountUpdateListenerCallback, OnWalletDataUpdate,
    OnEmojiUpdate {


    private val activity by lazy { findActivity(drawer) as FragmentActivity }
    private val progressDialog by lazy { ProgressDialog(activity) }

    init {
        drawer.addDrawerListener(DrawerListener())

        with(binding.root.layoutParams) {
            width = (ScreenUtils.getScreenWidth() * 0.8f).toInt()
            binding.root.layoutParams = this
        }

        with(binding) {
            accountSwitchButton.setOnClickListener { AccountSwitchDialog.show(activity.supportFragmentManager) }
            clEvmLayout.setOnClickListener {
                if (EVMWalletManager.haveEVMAddress()) {
                    drawer.close()
                } else {
                    EnableEVMActivity.launch(activity)
                }
            }
            flNetworkLayout.setVisible(isDeveloperMode())
            flNetworkLayout.setOnClickListener {
                NetworkPopupMenu(tvNetwork).show()
            }
            tvImportAccount.setOnClickListener {
                WalletRestoreActivity.launch(activity)
            }
        }

        bindData()
        binding.refreshWalletList()

        AccountEmojiManager.addListener(this)
        ChildAccountList.addAccountUpdateListener(this)
        WalletFetcher.addListener(this)
    }

    private fun initEVMLayoutTitle() {
        val text = R.string.enable_evm_title.res2String()
        val evmText = R.string.evm_on_flow.res2String()
        val index = text.indexOf(evmText)
        if (index < 0 || index + evmText.length > text.length) {
            binding.tvEvmTitle.text = text
        } else {
            val start = binding.tvEvmTitle.paint.measureText(text.substring(0, index))
            binding.tvEvmTitle.text = SpannableStringBuilder(text).apply {
                val startColor = R.color.evm_on_flow_start_color.res2color()
                val endColor = R.color.evm_on_flow_end_color.res2color()
                val gradientTextWidth = binding.tvEvmTitle.paint.measureText(text)
                val shader = LinearGradient(
                    start, 0f, gradientTextWidth, 0f,
                    intArrayOf(startColor, endColor), null,
                    Shader.TileMode.CLAMP,
                )
                setSpan(
                    ShaderSpan(shader),
                    index,
                    index + evmText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun bindAccountData() {
        binding.llAccountLayout.removeAllViews()
        val list = AccountManager.list().filter { it.isActive.not() }
        list.take(2).forEach { account ->
            binding.llAccountLayout.addView(ImageFilterView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    44.dp2px().toInt(),
                    28.dp2px().toInt()
                )
                setPadding(8.dp2px().toInt(), 0, 8.dp2px().toInt(), 0)
                setOnClickListener {
                    if (isTestnet() || isPreviewnet()) {
                        SwitchNetworkDialog(activity, DialogType.SWITCH).show()
                    } else {
                        progressDialog.show()
                        AccountManager.switch(account) {
                            uiScope {
                                progressDialog.dismiss()
                            }
                        }
                    }
                }
                loadAvatar(account.userInfo.avatar)
            })
        }
    }

    override fun bind(model: MainDrawerLayoutModel) {
        model.refreshData?.let { bindData() }
        model.openDrawer?.let { drawer.open() }
    }

    private fun bindData() {
        uiScope {
            with(binding.tvNetwork) {
                val network = chainNetWorkString()
                text = network.capitalizeV2()
                val color = when (network) {
                    "mainnet" -> R.color.mainnet
                    "testnet" -> R.color.testnet
                    "previewnet" -> R.color.previewnet
                    else -> R.color.text
                }
                setTextColor(color.res2color())
                backgroundTintList = ColorStateList.valueOf(color.res2color()).withAlpha(16)
            }
        }
        ioScope {
            val address = WalletManager.selectedWalletAddress()
            drawer.setDrawerLockMode(if (address.isBlank()) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED)

            val userInfo = AccountManager.userInfo() ?: return@ioScope
            uiScope {
                with(binding) {
//                    avatarView.loadAvatar(userInfo.avatar)
                    nickNameView.text = userInfo.nickname

                    val avatarUrl = userInfo.avatar.parseAvatarUrl()
                    val avatar = if (avatarUrl.contains("boringavatars.com") || avatarUrl.contains("flovatar.com")) {
                        avatarUrl.svgToPng()
                    } else {
                        avatarUrl
                    }
                    Glide.with(avatarView)
                        .asBitmap()
                        .load(avatar)
                        .placeholder(R.drawable.ic_placeholder)
                        .into(object : SimpleTarget<Bitmap>() {
                            override fun onResourceReady(
                                resource: Bitmap,
                                transition: Transition<in Bitmap>?
                            ) {
                                avatarView.setImageBitmap(resource)
                                val color = Palette.from(resource).generate().getDominantColor(R.color.text_sub.res2color())
                                val startColor = R.color.white_60.res2color()
                                val endColor = ColorUtils.setAlphaComponent(color, 153)
                                val gradientDrawable = GradientDrawable(
                                    GradientDrawable.Orientation.TOP_BOTTOM,
                                    intArrayOf(
                                        startColor,
                                        endColor
                                    )
                                )
                                gradientDrawable.cornerRadius = 12f
                                headerBg.background = gradientDrawable
                            }
                        })
                }
            }
        }
        /**
         * Temp Remove
         * The account 'quick switch' feature must be removed until we enable user-defined profile pictures or otherwise make it more obvious which profile icon corresponds to which account.
         * Until that time, we must remove the quick switch feature.
         */
//        bindAccountData()
    }

    private fun bindEVMInfo() {
        if (EVMWalletManager.showEVMEnablePage()) {
            initEVMLayoutTitle()
            binding.clEvmLayout.visible()
        } else {
            binding.clEvmLayout.gone()
        }
    }

    private fun launchClick(unit: () -> Unit) {
        unit.invoke()
        drawer.close()
    }

    private inner class DrawerListener : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerOpened(drawerView: View) {
            super.onDrawerOpened(drawerView)
            bindData()
            bindEVMInfo()
        }
    }

    override fun onChildAccountUpdate(parentAddress: String, accounts: List<ChildAccount>) {
        binding.refreshWalletList()
    }

    override fun onWalletDataUpdate(wallet: WalletListData) {
        binding.refreshWalletList()
    }

    override fun onEmojiUpdate(userName: String, address: String, emojiId: Int, emojiName: String) {
        binding.refreshWalletList()
    }

    private inner class ShaderSpan(private val shader: Shader) : ForegroundColorSpan(0) {
        override fun updateDrawState(tp: TextPaint) {
            tp.shader = shader
        }
    }
}

fun openDrawerLayout(context: Context) {
    val activity = context as? FragmentActivity ?: return
    val viewModel = ViewModelProvider(activity)[MainActivityViewModel::class.java]
    viewModel.openDrawerLayout()
}
