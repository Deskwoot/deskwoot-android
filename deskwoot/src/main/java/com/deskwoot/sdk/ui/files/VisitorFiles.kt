package com.deskwoot.sdk.ui.files

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The files the SDK writes for the visitor (pictures, copies of files,
 * copies for an upload) against the end of that visitor. A load takes a
 * [Stamp] when it starts. What it downloaded or copied goes into a part file
 * first and is moved into place only under one lock, after checking that no
 * purge ran since the stamp; the purge deletes under the same lock. So a
 * picture or file still loading when the visitor ends is either deleted by
 * the purge or never lands at all. What lands in memory or on screen checks
 * [sameVisitor] on the main thread, where the end of the visitor on screen
 * is told at once ([visitorEnded]).
 */
internal object VisitorFiles {
    /** Ends of the visitor on screen: what still loads for them is dropped. */
    private val visitorEnds = AtomicInteger()

    /** Purges of the files (every end of a visitor, also one never shown): what still loads writes nothing to disk. */
    private val purges = AtomicInteger()

    private val lock = Any()

    /** When a load started, by both counts. */
    class Stamp internal constructor(internal val visitor: Int, internal val disk: Int)

    fun stamp(): Stamp = Stamp(visitorEnds.get(), purges.get())

    /** The visitor on screen ended: nothing that loads for them is kept, in memory or on disk. Any thread. */
    fun visitorEnded() {
        visitorEnds.incrementAndGet()
        purges.incrementAndGet()
    }

    /** No visitor on screen ended since [stamp]: what the load got may be shown and kept in memory. */
    fun sameVisitor(stamp: Stamp): Boolean = visitorEnds.get() == stamp.visitor

    /**
     * Moves [part] to [target] when no purge ran since [stamp]; otherwise
     * deletes [part]. False when nothing was placed. Blocking.
     */
    fun place(part: File, target: File, stamp: Stamp): Boolean = synchronized(lock) {
        if (purges.get() != stamp.disk) {
            part.delete()
            false
        } else {
            part.renameTo(target)
        }
    }

    /** Deletes [folder] with everything in it; a load that started before writes nothing afterwards. Blocking. */
    fun purge(folder: File) {
        synchronized(lock) {
            purges.incrementAndGet()
            folder.deleteRecursively()
        }
    }
}
