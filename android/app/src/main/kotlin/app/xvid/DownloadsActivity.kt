package app.xvid

import android.app.Activity
import android.os.Bundle

/** The phone downloads (see [RecentDownloadsSection]), opened from the main screen like Settings. */
class DownloadsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(screen { addView(RecentDownloadsSection(this@DownloadsActivity)) })
    }
}
