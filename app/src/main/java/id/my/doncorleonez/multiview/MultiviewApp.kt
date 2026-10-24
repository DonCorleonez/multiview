package id.my.doncorleonez.multiview

import android.app.Application
import android.content.Context
import id.my.doncorleonez.multiview.core.AccountStore
import id.my.doncorleonez.multiview.core.Log
import id.my.doncorleonez.multiview.core.ViewState
import id.my.doncorleonez.multiview.core.XtreamClient
import id.my.doncorleonez.multiview.platform.AndroidLogger
import id.my.doncorleonez.multiview.platform.ChannelRepo
import id.my.doncorleonez.multiview.platform.KeystoreBox
import id.my.doncorleonez.multiview.platform.PrefsKV
import java.io.File

/** Process-wide singletons. No services, nothing runs in the background on its own. */
class MultiviewApp : Application() {
    lateinit var accounts: AccountStore; private set
    lateinit var state: ViewState; private set
    lateinit var channels: ChannelRepo; private set
    lateinit var xtream: XtreamClient; private set

    override fun onCreate() {
        super.onCreate()
        Log.sink = AndroidLogger()
        xtream = XtreamClient(userAgent = "Multiview/${BuildConfig.VERSION_NAME}")
        accounts = AccountStore(PrefsKV(getSharedPreferences("accounts", Context.MODE_PRIVATE)), KeystoreBox())
        state = ViewState(PrefsKV(getSharedPreferences("state", Context.MODE_PRIVATE)))
        channels = ChannelRepo(File(filesDir, "cache"), xtream, accounts)
    }

    companion object {
        fun of(c: Context) = c.applicationContext as MultiviewApp
    }
}
