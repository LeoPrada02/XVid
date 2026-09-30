package app.xvid

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast

/**
 * The X login page: X's own login form in a WebView. Once X has set its session
 * cookie, the cookies are kept as the phone's X login (see [app.xvid.core.XLogin])
 * and the downloads that needed a login are retried.
 *
 * It always starts logged out, so an expired session left in the WebView is never
 * taken for a new login.
 *
 * Username and password only: Google doesn't allow its sign-in inside apps' WebViews.
 */
class XLoginActivity : Activity() {
    private val mainThread = Handler(Looper.getMainLooper())
    private lateinit var web: WebView

    /** Whether the old cookies are gone, so the ones X sets now are a new login. */
    private var ready = false
    private var done = false

    // X's login is a single-page app that doesn't always load a new page when it's done,
    // so the cookies are checked every second as well as after each page.
    private val poll = object : Runnable {
        override fun run() {
            if (!captureLogin()) mainThread.postDelayed(this, POLL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    captureLogin()
                }
            }
        }
        setContentView(
            column {
                setBackgroundColor(color(R.color.bg))
                addView(text(TextStyle.MUTED, R.string.x_login_help).apply {
                    setPadding(dp(16), dp(24), dp(16), dp(12))
                })
                addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        CookieManager.getInstance().removeAllCookies {
            if (isDestroyed) return@removeAllCookies
            ready = true
            web.loadUrl(LOGIN_URL)
        }
    }

    override fun onResume() {
        super.onResume()
        mainThread.post(poll)
    }

    override fun onPause() {
        mainThread.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Keeps the login once X's cookies are one, and closes. Returns whether it did. */
    private fun captureLogin(): Boolean {
        if (done) return true
        if (!ready) return false
        val cookies = CookieManager.getInstance().getCookie(X_URL) ?: return false
        val app = application as XVidApp
        if (!app.xLogin.logIn(cookies)) return false
        done = true
        CookieManager.getInstance().flush()
        mainThread.removeCallbacks(poll)
        Toast.makeText(applicationContext, R.string.x_login_done, Toast.LENGTH_SHORT).show()
        DownloadService.retryAfterLogin(this)
        finish()
        return true
    }

    companion object {
        private const val X_URL = "https://x.com"
        private const val LOGIN_URL = "https://x.com/i/flow/login"
        private const val POLL_MS = 1000L

        fun intent(context: Context): Intent = Intent(context, XLoginActivity::class.java)

        /** Logs the phone out of X: the kept login and the login page's own cookies. */
        fun logOut(context: Context) {
            (context.applicationContext as XVidApp).xLogin.logOut()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}
