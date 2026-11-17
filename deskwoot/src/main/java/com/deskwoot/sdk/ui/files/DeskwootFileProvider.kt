package com.deskwoot.sdk.ui.files

import androidx.core.content.FileProvider
import com.deskwoot.sdk.Deskwoot

/**
 * The SDK's FileProvider, under its own authority (`<applicationId>.deskwoot.files`):
 * a subclass, so it never collides with an app's own `androidx.core.content.FileProvider`.
 *
 * Android creates it in the app's default process before
 * `Application.onCreate`. It keeps the application context there and does
 * nothing else (no disk, no network): a `Deskwoot.logout` before `setup`
 * needs it to delete what an earlier launch stored.
 */
public class DeskwootFileProvider : FileProvider() {
    override fun onCreate(): Boolean {
        context?.let(Deskwoot::attach)
        return super.onCreate()
    }
}
