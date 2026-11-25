package org.beachsync.proto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Decides where the library lives. By default it is Documents/Brocast, made by path with no permission (kept after an uninstall).
 * The person can instead pick another folder (kept too, and the app sees only that folder), use All files access with Music/Brocast
 * (the same, but the app sees every file), or keep it in the app's own folder (removed with the app). The folder is resolved once per start, so a change in Settings takes effect on the next start.
 * The engine needs ordinary file paths, so a picked folder is turned into a path and used like any other.
 */
object SharedLibrary {
    const val DOCS = "docs"
    const val FOLDER = "folder"
    const val ALL = "all"
    const val APP = "app"
    private const val ALL_PATH = "/storage/emulated/0/Music/Brocast"
    private const val DOCS_PATH = "/storage/emulated/0/Documents/Brocast"
    private const val STORAGE = "/storage/emulated/0/"
    private const val FOLDER_NAME = "Brocast"
    private const val KEY_CHOICE = "musicChoice"
    private const val KEY_TREE = "musicTree"
    private const val KEY_PATH = "musicPath"
    private const val KEY_LAST = "libDir"
    private const val KEY_DOCS_OURS = "docsOurs"

    @Volatile private var resolved: File? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("proto", Context.MODE_PRIVATE)

    /** The stored choice: [DOCS], [FOLDER], [ALL] or [APP], or "" on a fresh install that hasn't been answered yet. */
    fun choice(ctx: Context) = prefs(ctx).getString(KEY_CHOICE, "") ?: ""
    fun chosen(ctx: Context) = choice(ctx).isNotEmpty()
    fun treeUri(ctx: Context): Uri? = prefs(ctx).getString(KEY_TREE, null)?.let { Uri.parse(it) }

    fun setChoice(ctx: Context, choice: String, tree: Uri? = null, path: String? = null) {
        prefs(ctx).edit().putString(KEY_CHOICE, choice).apply { if (tree != null) putString(KEY_TREE, tree.toString()); if (path != null) putString(KEY_PATH, path) }.commit()   // commit: the app may restart straight after
    }

    /** The app's own library folder: where the sets were kept before, and where they stay if the chosen folder can't be used. */
    fun appDir(ctx: Context): File = File(ctx.applicationContext.let { it.getExternalFilesDir(null) ?: it.filesDir }, "library")

    /** The library folder for this start, made if need be. Not remembered until the person has chosen. */
    @Synchronized
    fun dir(ctx: Context): File {
        resolved?.let { return it }
        val d = (when (choice(ctx)) { DOCS -> docsDir(); ALL -> allFilesDir(); FOLDER -> pickedDir(ctx); else -> null } ?: appDir(ctx)).apply { mkdirs() }
        if (chosen(ctx)) resolved = d
        return d
    }

    /** Drops this start's remembered folder, so the next [dir] follows a choice just made. */
    @Synchronized fun forget() { resolved = null }

    /** Whether this start keeps the library outside the app's own folder. */
    fun inUse(ctx: Context) = dir(ctx).path != appDir(ctx).path

    /** Whether Android has given All files access, whether or not this start is using it yet. */
    fun permitted() = Environment.isExternalStorageManager()

    /** Whether the person's choice is being followed this start (it isn't when the folder or permission has gone). */
    fun following(ctx: Context) = when (choice(ctx)) { DOCS, ALL, FOLDER -> inUse(ctx); else -> true }

    /** Writes and deletes a small non-audio file by path, and leaves a .nomedia so the sets stay out of music players. */
    private fun usable(d: File): Boolean = try {
        (d.isDirectory || d.mkdirs()) && File(d, "probe.json").let { it.writeText("{}"); it.delete() }.also { if (it) File(d, ".nomedia").createNewFile() }
    } catch (e: Exception) { Log.w("BeachSync", "Library folder ${d.path} not usable: $e"); false }

    private fun docsDir(): File? = File(DOCS_PATH).takeIf { usable(it) }

    /** Whether an earlier install left a Documents/Brocast folder (it is visible even when its files aren't): a fresh install asks before using it. */
    fun earlierInstallFolder(): Boolean = File(DOCS_PATH).isDirectory

    private fun allFilesDir(): File? = if (permitted()) File(ALL_PATH).takeIf { usable(it) } else null

    private fun pickedDir(ctx: Context): File? {
        val tree = treeUri(ctx) ?: return null
        val granted = ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
        val path = pathFromTree(tree) ?: return null
        // A reinstall has no grant: the folder is only used again once the person picks it again.
        return if (granted) File(path).takeIf { usable(it) } else null
    }

    /**
     * The library folder for a picked folder on the main storage, or null for anything else (an SD card, a cloud provider, the storage
     * root). The sets always go in a folder named Brocast: picking Documents means Documents/Brocast, so they never spill into it.
     */
    fun pathFromTree(tree: Uri): String? = try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val rel = if (tree.authority == "com.android.externalstorage.documents" && id.startsWith("primary:")) id.removePrefix("primary:").trim('/') else ""
        if (rel.isEmpty() || rel.contains("..")) null else STORAGE + rel + (if (rel.substringAfterLast('/') == FOLDER_NAME) "" else "/$FOLDER_NAME")
    } catch (e: Exception) { null }

    /** Only the app's own files are ever moved or taken back: the list, the artists' photos, a set's files (named by its id) and half-built sets. */
    private val ID_FILE = Regex("[0-9a-f]{32}\\..+")
    private fun ours(name: String) = name == "library.json" || name == "artists" || name == ".building" || ID_FILE.matches(name)

    /** Whether a picked folder can be used by path: it maps to a path, and a small non-audio file can be made in it and deleted. */
    fun pickable(tree: Uri): Boolean = pathFromTree(tree)?.let { usable(File(it)) } == true

    /** Old folders that still hold files and are not this start's folder: the app folder, and the folder the last start used. */
    fun leftBehind(ctx: Context): List<File> {
        val here = dir(ctx).path
        val last = prefs(ctx).getString(KEY_LAST, null)?.let { File(it) }
        return listOfNotNull(appDir(ctx), last).distinctBy { it.path }.filter { it.path != here && it.listFiles()?.isNotEmpty() == true }
    }

    /** Remembers this start's folder, so a change of choice moves the files from it next time. */
    fun noteDir(ctx: Context) {
        val d = dir(ctx).path
        prefs(ctx).edit().putString(KEY_LAST, d).apply { if (d == DOCS_PATH) putBoolean(KEY_DOCS_OURS, true) }.apply()
    }

    /**
     * Whether moving to Documents/Brocast needs the person's OK first: the folder exists, this install has never kept the library there
     * (so its files may be an earlier install's, which this one can't open by path), and no picked folder grant covers it. Then the
     * picker opens on it and the take-back runs, as for "Use it" on the found-music screen.
     */
    fun docsNeedsGrant(ctx: Context): Boolean =
        File(DOCS_PATH).isDirectory && !prefs(ctx).getBoolean(KEY_DOCS_OURS, false) &&
            ctx.contentResolver.persistedUriPermissions.none { it.isWritePermission && pathFromTree(it.uri) == DOCS_PATH }

    /** The settings screen where the person gives All files access: this app's page, or the general list if the phone has no such page. */
    fun settingsIntent(ctx: Context): Intent {
        val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        return if (own.resolveActivity(ctx.packageManager) != null) own else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    /** The folder picker, opening at Documents, or at Documents/Brocast to take back an earlier install's music. */
    fun pickerIntent(brocast: Boolean = false): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments" + if (brocast) "%2FBrocast" else ""))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

    /** A short line for Settings: where the sets are this start, and what the choice is. */
    fun describe(ctx: Context): String = when (choice(ctx)) {
        DOCS -> if (inUse(ctx)) "Music folder: Documents/Brocast" else "Music folder: app storage (Documents/Brocast could not be used)"
        FOLDER -> if (inUse(ctx)) "Music folder: ${dir(ctx).path.removePrefix(STORAGE)}" else "Music folder: app storage (pick your folder again)"
        ALL -> if (inUse(ctx)) "Music folder: Music/Brocast" else "Music folder: app storage (All files access is off)"
        else -> "Music folder: app storage (removed with the app)"
    }

    /**
     * Moves what the old folders hold into [to], once. Never on the audio thread. A half-built set or download is thrown away: it would
     * start again anyway. A file the destination already has with the same length is not copied again.
     */
    fun migrate(froms: List<File>, to: File) {
        val t0 = System.currentTimeMillis()
        val n = longArrayOf(0, 0, 0)   // moved, copied, bytes
        for (from in froms) {
            walk(from, to, n, top = true)
            // An old shared folder gives its media back to other apps once the sets have left it.
            if (from.path != to.path) File(from, ".nomedia").takeIf { from.listFiles()?.none { ours(it.name) } == true }?.delete()
        }
        Log.i("BeachSync", "Library move to ${to.path}: ${n[0]} moved, ${n[1]} copied, ${n[2]} bytes, ${System.currentTimeMillis() - t0} ms")
    }

    private fun walk(from: File, to: File, n: LongArray, top: Boolean = false) {
        to.mkdirs()
        for (f in from.listFiles() ?: return) {
            if (top && !ours(f.name)) continue   // someone else's file, or the destination itself
            if (f.name == ".building" || f.name.endsWith(".part") || f.name.endsWith(".taking")) { f.deleteRecursively(); continue }
            val d = File(to, f.name)
            if (f.isDirectory) { walk(f, d, n); f.delete(); continue }
            try {
                val len = f.length()
                if (d.exists() && d.length() == len) { f.delete(); continue }
                if (f.renameTo(d)) { n[0]++; n[2] += len; continue }
                val temp = File(to, "${f.name}.moving")
                f.inputStream().use { i -> temp.outputStream().use { o -> i.copyTo(o) } }
                if (temp.length() == len && temp.renameTo(d)) { f.delete(); n[1]++; n[2] += len } else temp.delete()
            } catch (e: Exception) { Log.w("BeachSync", "Library move ${f.name}: $e") }
        }
    }

    /**
     * After a reinstall the files an earlier install left in the picked folder belong to no one the new install owns, so path access
     * fails. Each is copied through the picked folder's grant to a ".taking" file made by path, checked by length, its old document
     * deleted, and the copy renamed into place: one file at a time, so only one file's worth of extra space is used. Files that read
     * fine (ownership kept) are left alone. [progress] gets (done, total) for the status line.
     */
    fun takeBack(ctx: Context, dir: File, progress: (Int, Int) -> Unit) {
        val tree = treeUri(ctx) ?: return
        if (choice(ctx) != FOLDER || ctx.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) return
        val t0 = System.currentTimeMillis()
        val todo = ArrayList<Triple<Uri, File, Long>>()
        // The library is the picked folder itself, or the Brocast folder inside it (pathFromTree).
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootPath = STORAGE + rootId.removePrefix("primary:").trim('/')
        val libId = if (dir.path == rootPath) rootId else rootId.trimEnd('/') + "/" + dir.path.removePrefix(rootPath).trim('/')
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, libId)
        try { collect(ctx, tree, libId, dir, todo, top = true) } catch (e: Exception) { Log.w("BeachSync", "Take back: $e"); return }
        if (todo.isEmpty()) return
        var done = 0; var bytes = 0L; var failed = 0
        for ((uri, file, size) in todo) {
            progress(done, todo.size)
            try {
                val temp = File(file.parentFile, "${file.name}.taking")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> temp.outputStream().use { o -> i.copyTo(o) } }
                if (temp.length() != size) { temp.delete(); failed++; continue }
                DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                if (!temp.renameTo(file)) { failed++; continue }
                bytes += size; done++
            } catch (e: Exception) { failed++; Log.w("BeachSync", "Take back ${file.name}: $e") }
        }
        Log.i("BeachSync", "Taken back from ${doc.lastPathSegment}: $done of ${todo.size} files, $failed failed, $bytes bytes, ${System.currentTimeMillis() - t0} ms")
    }

    /** The files under a picked folder that can't be read by path (or are missing there): the old install's. */
    private fun collect(ctx: Context, tree: Uri, docId: String, dir: File, out: MutableList<Triple<Uri, File, Long>>, top: Boolean = false) {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        val rows = ArrayList<Array<Any>>()
        ctx.contentResolver.query(kids, cols, null, null, null)?.use { c -> while (c.moveToNext()) rows.add(arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
        for (r in rows) {
            val name = r[1] as String
            val f = File(dir, name)
            if (top && (!ours(name) || name == ".building")) continue
            if (r[2] == DocumentsContract.Document.MIME_TYPE_DIR) { collect(ctx, tree, r[0] as String, f, out); continue }
            if (name.endsWith(".taking") || name.endsWith(".part")) continue
            val readable = f.canRead() && try { f.inputStream().use { it.read() }; true } catch (e: Exception) { false }
            if (!readable) out.add(Triple(DocumentsContract.buildDocumentUriUsingTree(tree, r[0] as String), f, r[3] as Long))
        }
    }
}
