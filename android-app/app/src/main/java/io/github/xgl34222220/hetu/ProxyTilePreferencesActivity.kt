package io.github.xgl34222220.hetu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Long-press destination for the Quick Settings proxy tile.
 *
 * Android resolves ACTION_QS_TILE_PREFERENCES to this activity; it then routes
 * into Hetu's real Settings tab instead of falling back to generic App Info.
 */
class ProxyTilePreferencesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, ReferenceProxyActivity::class.java)
                .putExtra(ReferenceProxyActivity.EXTRA_START_PAGE, "settings")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
