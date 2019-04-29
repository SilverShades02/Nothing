package eu.chainfire.opendelta

import android.app.Activity
import android.os.Bundle
import android.content.Intent

class Shortcut : Activity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent()
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent(this, MainActivity::class.java))
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, this.resources.getString(R.string.title))
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher_settings))
        setResult(Activity.RESULT_OK, intent)

        finish()
    }

}
