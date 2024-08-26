package com.flowfoundation.wallet.page.restore.keystore.viewmodel

import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.flowfoundation.wallet.R
import com.flowfoundation.wallet.base.activity.BaseActivity
import com.flowfoundation.wallet.firebase.auth.getFirebaseJwt
import com.flowfoundation.wallet.manager.account.Account
import com.flowfoundation.wallet.manager.account.AccountManager
import com.flowfoundation.wallet.manager.account.DeviceInfoManager
import com.flowfoundation.wallet.manager.account.username
import com.flowfoundation.wallet.manager.wallet.WalletManager
import com.flowfoundation.wallet.network.ApiService
import com.flowfoundation.wallet.network.OtherHostService
import com.flowfoundation.wallet.network.clearUserCache
import com.flowfoundation.wallet.network.model.AccountKey
import com.flowfoundation.wallet.network.model.ImportRequest
import com.flowfoundation.wallet.network.model.LoginRequest
import com.flowfoundation.wallet.network.retrofit
import com.flowfoundation.wallet.network.retrofitWithHost
import com.flowfoundation.wallet.page.main.MainActivity
import com.flowfoundation.wallet.page.restore.keystore.PrivateKeyStoreCryptoProvider
import com.flowfoundation.wallet.page.restore.keystore.model.KeyStoreOption
import com.flowfoundation.wallet.page.restore.keystore.model.KeystoreAddress
import com.flowfoundation.wallet.page.walletrestore.firebaseLogin
import com.flowfoundation.wallet.page.walletrestore.getFirebaseUid
import com.flowfoundation.wallet.utils.ioScope
import com.flowfoundation.wallet.utils.loge
import com.flowfoundation.wallet.utils.setMultiBackupCreated
import com.flowfoundation.wallet.utils.setRegistered
import com.flowfoundation.wallet.utils.toast
import com.flowfoundation.wallet.utils.uiScope
import com.google.gson.Gson
import com.nftco.flow.sdk.HashAlgorithm
import com.nftco.flow.sdk.SignatureAlgorithm
import com.nftco.flow.sdk.bytesToHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import wallet.core.jni.PrivateKey
import wallet.core.jni.StoredKey


class KeyStoreRestoreViewModel : ViewModel() {

    private val queryService by lazy {
        retrofitWithHost("https://production.key-indexer.flow.com").create(OtherHostService::class.java)
    }

    private val apiService by lazy {
        retrofit().create(ApiService::class.java)
    }

    private val addressList = mutableListOf<KeystoreAddress>()
    private var currentKeyStoreAddress: KeystoreAddress? = null

    val addressListLiveData = MutableLiveData<List<KeystoreAddress>>()
    val optionChangeLiveData = MutableLiveData<KeyStoreOption>()
    val loadingLiveData = MutableLiveData<Boolean>()

    fun changeOption(option: KeyStoreOption) {
        optionChangeLiveData.postValue(option)
    }

    fun getAddressList(): List<KeystoreAddress> {
        return addressList
    }

    fun importKeyStore(json: String, password: String, address: String?) {
        loadingLiveData.postValue(true)
        ioScope {
            val keyStore = StoredKey.importJSON(json.toByteArray())
            val privateKey = PrivateKey(keyStore.decryptPrivateKey(password.toByteArray()))

            val p1PublicKey =
                privateKey.publicKeyNist256p1.uncompressed().data().bytesToHex().removePrefix("04")

            val k1PublicKey =
                privateKey.getPublicKeySecp256k1(false).data().bytesToHex().removePrefix("04")

            queryAddressWithPublicKey(privateKey.data().bytesToHex(), k1PublicKey, p1PublicKey)
        }

    }

    private suspend fun queryAddressWithPublicKey(
        privateKey: String, k1PublicKey: String,
        p1PublicKey: String
    ) {
        addressList.clear()
        val k1Response = queryService.queryAddress(k1PublicKey)
        if (k1Response.publicKey == k1PublicKey && k1Response.accounts.isNotEmpty()) {
            addressList.addAll(k1Response.accounts.map {
                KeystoreAddress(
                    address = it.address,
                    publicKey = k1PublicKey,
                    privateKey = privateKey,
                    keyId = it.keyId,
                    weight = it.weight,
                    hashAlgo = HashAlgorithm.SHA2_256.index,
                    signAlgo = SignatureAlgorithm.ECDSA_SECP256k1.index
                )
            }.toList())
        }
        val p1Response = queryService.queryAddress(p1PublicKey)
        if (p1Response.publicKey == p1PublicKey && p1Response.accounts.isNotEmpty()) {
            addressList.addAll(p1Response.accounts.map {
                KeystoreAddress(
                    address = it.address,
                    publicKey = p1PublicKey,
                    privateKey = privateKey,
                    keyId = it.keyId,
                    weight = it.weight,
                    hashAlgo = HashAlgorithm.SHA2_256.index,
                    signAlgo = SignatureAlgorithm.ECDSA_P256.index
                )
            }.toList())
        }
        loadingLiveData.postValue(false)
        addressListLiveData.postValue(addressList)
    }

    fun importKeyStoreAddress(keystoreAddress: KeystoreAddress) {
        currentKeyStoreAddress = keystoreAddress
        loadingLiveData.postValue(true)
        ioScope {
            try {
                val response = apiService.checkKeystorePublicKeyImport(keystoreAddress.publicKey)
                if (response.status == 200) {
                    loadingLiveData.postValue(false)
                    changeOption(KeyStoreOption.CREATE_USERNAME)
                }
            } catch (e: Exception) {
                (e as? HttpException)?.let {
                    if (it.code() == 409) {
                        loginWithKeyStoreAddress(keystoreAddress)
                    }
                } ?: run {
                    loadingLiveData.postValue(false)
                }
            }
        }
    }

    fun importWithUsername(username: String) {
        if (currentKeyStoreAddress == null || username.isEmpty()) {
            loadingLiveData.postValue(false)
            toast(msgRes = R.string.login_failure)
            return
        }
        if (WalletManager.wallet()?.walletAddress() == currentKeyStoreAddress?.address) {
            toast(msgRes = R.string.wallet_already_logged_in, duration = Toast.LENGTH_LONG)
            val activity = BaseActivity.getCurrentActivity() ?: return
            activity.finish()
            return
        }
        val account = AccountManager.list().firstOrNull { it.wallet?.walletAddress() == currentKeyStoreAddress?.address }
        if (account != null) {
            AccountManager.switch(account){}
            return
        }
        ioScope {
            val cryptoProvider = PrivateKeyStoreCryptoProvider(Gson().toJson(currentKeyStoreAddress))
            val activity = BaseActivity.getCurrentActivity() ?: return@ioScope
            import(cryptoProvider, username) { isSuccess ->
                uiScope {
                    loadingLiveData.postValue(false)
                    if (isSuccess) {
                        delay(200)
                        MainActivity.relaunch(activity, clearTop = true)
                    } else {
                        toast(msgRes = R.string.login_failure)
                        activity.finish()
                    }
                }
            }
        }
    }

    private fun import(cryptoProvider: PrivateKeyStoreCryptoProvider, username: String, callback:
        (isSuccess: Boolean) -> Unit) {

        ioScope {
            getFirebaseUid { uid ->
                if (uid.isNullOrBlank()) {
                    callback.invoke(false)
                    return@getFirebaseUid
                }
                runBlocking {
                    val catching = runCatching {
                        val deviceInfoRequest = DeviceInfoManager.getDeviceInfoRequest()
                        val service = retrofit().create(ApiService::class.java)
                        val resp = service.import(
                            ImportRequest(
                                address = cryptoProvider.getAddress(),
                                username = username,
                                accountKey = AccountKey(
                                    publicKey = cryptoProvider.getPublicKey(),
                                    hashAlgo = cryptoProvider.getHashAlgorithm().index,
                                    signAlgo = cryptoProvider.getSignatureAlgorithm().index
                                ),
                                deviceInfo = deviceInfoRequest
                            )
                        )
                        if (resp.data?.customToken.isNullOrBlank()) {
                            callback.invoke(false)
                        } else {
                            firebaseLogin(resp.data?.customToken!!) { isSuccess ->
                                if (isSuccess) {
                                    setRegistered()
                                    setMultiBackupCreated()
                                    ioScope {
                                        AccountManager.add(
                                            Account(
                                                userInfo = service.userInfo().data,
                                                keyStoreInfo = cryptoProvider.getKeyStoreInfo()
                                            )
                                        )
                                        clearUserCache()
                                        callback.invoke(true)
                                    }
                                } else {
                                    callback.invoke(false)
                                }
                            }
                        }
                    }

                    if (catching.isFailure) {
                        loge(catching.exceptionOrNull())
                        callback.invoke(false)
                    }
                }
            }
        }
    }

    private fun loginWithKeyStoreAddress(keystoreAddress: KeystoreAddress) {
        if (WalletManager.wallet()?.walletAddress() == keystoreAddress.address) {
            toast(msgRes = R.string.wallet_already_logged_in, duration = Toast.LENGTH_LONG)
            val activity = BaseActivity.getCurrentActivity() ?: return
            activity.finish()
            return
        }
        val account = AccountManager.list().firstOrNull { it.wallet?.walletAddress() == keystoreAddress.address }
        if (account != null) {
            AccountManager.switch(account){}
            return
        }
        ioScope {
            val cryptoProvider = PrivateKeyStoreCryptoProvider(Gson().toJson(keystoreAddress))
            val activity = BaseActivity.getCurrentActivity() ?: return@ioScope
            login(cryptoProvider) { isSuccess ->
                uiScope {
                    loadingLiveData.postValue(false)
                    if (isSuccess) {
                        delay(200)
                        MainActivity.relaunch(activity, clearTop = true)
                    } else {
                        toast(msgRes = R.string.login_failure)
                        activity.finish()
                    }
                }
            }
        }
    }

    private fun login(cryptoProvider: PrivateKeyStoreCryptoProvider, callback: (isSuccess: Boolean) -> Unit) {
        ioScope {
            getFirebaseUid { uid ->
                if (uid.isNullOrBlank()) {
                    callback.invoke(false)
                    return@getFirebaseUid
                }
                runBlocking {
                    val catching = runCatching {
                        val deviceInfoRequest = DeviceInfoManager.getDeviceInfoRequest()
                        val service = retrofit().create(ApiService::class.java)
                        val resp = service.login(
                            LoginRequest(
                                signature = cryptoProvider.getUserSignature(
                                    getFirebaseJwt()
                                ),
                                accountKey = AccountKey(
                                    publicKey = cryptoProvider.getPublicKey(),
                                    hashAlgo = cryptoProvider.getHashAlgorithm().index,
                                    signAlgo = cryptoProvider.getSignatureAlgorithm().index
                                ),
                                deviceInfo = deviceInfoRequest
                            )
                        )
                        if (resp.data?.customToken.isNullOrBlank()) {
                            callback.invoke(false)
                        } else {
                            firebaseLogin(resp.data?.customToken!!) { isSuccess ->
                                if (isSuccess) {
                                    setRegistered()
                                    setMultiBackupCreated()
                                    ioScope {
                                        AccountManager.add(
                                            Account(
                                                userInfo = service.userInfo().data,
                                                keyStoreInfo = cryptoProvider.getKeyStoreInfo()
                                            )
                                        )
                                        clearUserCache()
                                        callback.invoke(true)
                                    }
                                } else {
                                    callback.invoke(false)
                                }
                            }
                        }
                    }

                    if (catching.isFailure) {
                        loge(catching.exceptionOrNull())
                        callback.invoke(false)
                    }
                }
            }
        }
    }
}