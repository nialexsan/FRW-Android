package com.flowfoundation.wallet.manager.coin

import com.google.gson.annotations.SerializedName
import com.flowfoundation.wallet.cache.tokenStateCache
import com.flowfoundation.wallet.manager.app.chainNetWorkString
import com.flowfoundation.wallet.manager.evm.EVMWalletManager
import com.flowfoundation.wallet.manager.flowjvm.cadenceCheckLinkedAccountTokenListEnabled
import com.flowfoundation.wallet.manager.flowjvm.cadenceCheckTokenEnabled
import com.flowfoundation.wallet.manager.flowjvm.cadenceCheckTokenListEnabled
import com.flowfoundation.wallet.manager.wallet.WalletManager
import com.flowfoundation.wallet.network.ApiService
import com.flowfoundation.wallet.network.flowscan.contractId
import com.flowfoundation.wallet.network.retrofitApi
import com.flowfoundation.wallet.utils.ioScope
import com.flowfoundation.wallet.utils.logd
import com.flowfoundation.wallet.utils.logw
import com.flowfoundation.wallet.utils.uiScope
import org.web3j.utils.Convert
import org.web3j.utils.Numeric
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

object TokenStateManager {
    private val TAG = TokenStateManager::class.java.simpleName

    private val tokenStateList = CopyOnWriteArrayList<TokenState>()
    private val listeners = CopyOnWriteArrayList<WeakReference<TokenStateChangeListener>>()

    fun reload() {
        ioScope {
            tokenStateList.clear()
            tokenStateList.addAll(tokenStateCache().read()?.stateList ?: emptyList())
        }
    }

    fun fetchState() {
        ioScope {
            if (WalletManager.isEVMAccountSelected()) {
                fetchEVMTokenStateSync()
                FlowCoinListManager.getCoin(FlowCoin.SYMBOL_FLOW)?.let { token ->
                    val oldState = tokenStateList.firstOrNull { it.symbol == token.symbol }
                    tokenStateList.remove(oldState)
                    tokenStateList.add(TokenState(token.symbol, token.address, true))
                    dispatchListeners()
                }
            } else if (WalletManager.isChildAccountSelected()) {
                fetchLinkedAccountStateSync()
            } else {
                fetchStateSync()
            }
        }
    }

    private suspend fun fetchEVMTokenStateSync() {
        val address = EVMWalletManager.getEVMAddress() ?: return
        val apiService = retrofitApi().create(ApiService::class.java)
        val balanceResponse = apiService.getEVMTokenBalance(address, chainNetWorkString())
        balanceResponse.data?.forEach { token ->
            if (token.balance.isBlank()) {
                return@forEach
            }
            val amountValue = token.balance.toBigDecimal()
            val value = amountValue.movePointLeft(token.decimal).toFloat()
            val isEnable = value > 0
            val oldState = tokenStateList.firstOrNull { it.symbol == token.symbol }
            tokenStateList.remove(oldState)
            tokenStateList.add(TokenState(token.symbol, token.address, isEnable))
        }
        dispatchListeners()
        tokenStateCache().cache(TokenStateCache(tokenStateList.toList()))
    }

    private fun fetchLinkedAccountStateSync() {
        val coinList = FlowCoinListManager.coinList()
        val enabledToken = cadenceCheckLinkedAccountTokenListEnabled()
        if (enabledToken == null) {
            logw(TAG, "fetch error")
            return
        }
        coinList.forEach { coin ->
            val isEnable = enabledToken[coin.contractId()] ?: false
            val oldState = tokenStateList.firstOrNull {
                it.symbol == coin.symbol
            }
            tokenStateList.remove(oldState)
            tokenStateList.add(TokenState(coin.symbol, coin.address, isEnable))
        }
        dispatchListeners()
        tokenStateCache().cache(TokenStateCache(tokenStateList.toList()))
    }

    private fun fetchStateSync() {
        val coinList = FlowCoinListManager.coinList()
        val enabledToken = cadenceCheckTokenListEnabled()
        if (enabledToken == null) {
            logw(TAG, "fetch error")
            return
        }
        coinList.forEach { coin ->
            val isEnable = enabledToken[coin.contractId()] ?: false
            val oldState = tokenStateList.firstOrNull {
                it.symbol == coin.symbol
            }
            tokenStateList.remove(oldState)
            tokenStateList.add(TokenState(coin.symbol, coin.address, isEnable))
        }
        logd("WalletFragmentViewModel", "tokenStateList::${tokenStateList.size}")
        dispatchListeners()
        tokenStateCache().cache(TokenStateCache(tokenStateList.toList()))
    }

    fun fetchStateSingle(coin: FlowCoin, cache: Boolean = false) {
        val isEnable = cadenceCheckTokenEnabled(coin)
        if (isEnable != null) {
            val oldState = tokenStateList.firstOrNull { it.symbol == coin.symbol }
            tokenStateList.remove(oldState)
            tokenStateList.add(TokenState(coin.symbol, coin.address, isEnable))
            if (oldState?.isAdded != isEnable) {
                dispatchListeners()
            }
        }
        if (cache) {
            tokenStateCache().cache(TokenStateCache(tokenStateList.toList()))
        }
    }

    fun isTokenAdded(tokenAddress: String) = tokenStateList.firstOrNull { it.address.lowercase() == tokenAddress.lowercase() }?.isAdded ?: false

    fun addListener(callback: TokenStateChangeListener) {
        uiScope { this.listeners.add(WeakReference(callback)) }
    }

    private fun dispatchListeners() {
        uiScope {
            listeners.removeAll { it.get() == null }
            listeners.toList().forEach { it.get()?.onTokenStateChange() }
        }
    }

    fun clear() {
        tokenStateList.clear()
        tokenStateCache().clear()
    }
}

interface TokenStateChangeListener {
    fun onTokenStateChange()
}

class TokenStateCache(
    @SerializedName("stateList")
    val stateList: List<TokenState> = emptyList(),
)

class TokenState(
    @SerializedName("symbol")
    val symbol: String,
    @SerializedName("address")
    val address: String,
    @SerializedName("isAdded")
    val isAdded: Boolean,
)