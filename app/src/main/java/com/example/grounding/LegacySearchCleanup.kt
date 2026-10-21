package com.example.grounding

import android.content.Context
import java.security.KeyStore

/** Old encrypted Brave credentials are retired; the replacement needs no key. */
object LegacySearchCleanup {
    fun clear(context: Context) {
        context.getSharedPreferences("search_credentials", Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { store ->
                if (store.containsAlias("bi.brave.search.v1")) store.deleteEntry("bi.brave.search.v1")
            }
        }
    }
}
