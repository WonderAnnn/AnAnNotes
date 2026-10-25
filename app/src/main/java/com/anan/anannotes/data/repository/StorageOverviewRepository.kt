package com.anan.anannotes.data.repository

import android.content.Context
import com.anan.anannotes.core.model.ExportHistoryModel
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.files.PreviewCacheStore
import com.anan.anannotes.data.local.AnAnDatabase
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads only named ordinary paths and successful recorded export URIs. */
class StorageOverviewRepository(
  private val databaseFile: File,
  private val filesDir: File,
  private val cacheDir: File,
  private val historyProvider: suspend () -> List<ExportHistoryModel>,
  private val exportFileSizeReader: ExportFileSizeReader,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  constructor(
    context: Context,
    historyRepository: ExportHistoryRepository = ExportHistoryRepository(AnAnDatabase.getInstance(context)),
  ) : this(
    databaseFile = context.getDatabasePath("anan-notes.db"),
    filesDir = context.filesDir,
    cacheDir = context.cacheDir,
    historyProvider = historyRepository::latest,
    exportFileSizeReader = ContentResolverExportFileSizeReader(context.contentResolver),
  )

  suspend fun refresh(): StorageOverview = withContext(ioDispatcher) {
    val coroutineContext = currentCoroutineContext()
    val database = listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"))
      .map { measureFile(it.toPath()) }
    val assets = measureDirectory(filesDir, OriginalImageStore.ASSET_DIRECTORY) { coroutineContext.ensureActive() }
    val previews = measureDirectory(cacheDir, PreviewCacheStore.PREVIEW_DIRECTORY) { coroutineContext.ensureActive() }
    val uris = historyProvider().filter { it.succeeded }.map { it.destinationUri }.distinct()
    var knownBytes = 0L
    var knownSizeCount = 0
    var unknownSizeCount = 0
    var unavailableCount = 0
    uris.forEach { uri ->
      coroutineContext.ensureActive()
      val size = try {
        exportFileSizeReader.read(uri)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        ExportFileSize.Unavailable
      }
      when (size) {
        is ExportFileSize.Known -> { knownBytes += size.bytes; knownSizeCount++ }
        ExportFileSize.Unknown -> unknownSizeCount++
        ExportFileSize.Unavailable -> unavailableCount++
      }
    }
    StorageOverview(
      databaseBytes = database.sumOf { it.bytes },
      assetBytes = assets.bytes,
      previewBytes = previews.bytes,
      internalUnreadableEntries = database.sumOf { it.unreadableEntries } + assets.unreadableEntries + previews.unreadableEntries,
      exportedFiles = ExportStorageSummary(uris.size, knownBytes, knownSizeCount, unknownSizeCount, unavailableCount),
    )
  }

  suspend fun clearPreviewCache(): Int = withContext(ioDispatcher) {
    // Reject redirected paths before passing the ordinary cache root to its existing store.
    scopedDirectory(cacheDir, PreviewCacheStore.PREVIEW_DIRECTORY)
    PreviewCacheStore(cacheDir).clear()
  }

  private data class Measurement(val bytes: Long = 0L, val unreadableEntries: Int = 0)

  private fun measureFile(path: Path): Measurement = try {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (attributes.isRegularFile) Measurement(attributes.size()) else Measurement(unreadableEntries = 1)
  } catch (_: NoSuchFileException) {
    Measurement()
  } catch (_: IOException) {
    Measurement(unreadableEntries = 1)
  } catch (_: SecurityException) {
    Measurement(unreadableEntries = 1)
  }

  private fun scopedDirectory(root: File, relativePath: String): Path {
    var directory = root.toPath().toAbsolutePath().normalize()
    for (segment in relativePath.split('/')) {
      directory = directory.resolve(segment)
      if (Files.isSymbolicLink(directory)) throw IOException("存储目录不可访问")
    }
    return directory
  }

  private fun measureDirectory(root: File, relativePath: String, checkCancellation: () -> Unit): Measurement {
    val directory = try {
      scopedDirectory(root, relativePath).also {
        if (!Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory) {
          return Measurement(unreadableEntries = 1)
        }
      }
    } catch (_: NoSuchFileException) {
      return Measurement()
    } catch (_: IOException) {
      return Measurement(unreadableEntries = 1)
    } catch (_: SecurityException) {
      return Measurement(unreadableEntries = 1)
    }

    var bytes = 0L
    var unreadable = 0
    try {
      // No FOLLOW_LINKS: a child link is never followed into a different data scope.
      Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attributes: BasicFileAttributes): FileVisitResult {
          checkCancellation()
          return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
          checkCancellation()
          if (attributes.isRegularFile) bytes += attributes.size() else unreadable++
          return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
          checkCancellation()
          unreadable++
          return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
          checkCancellation()
          if (error != null) unreadable++
          return FileVisitResult.CONTINUE
        }
      })
    } catch (_: IOException) {
      unreadable++
    } catch (_: SecurityException) {
      unreadable++
    }
    return Measurement(bytes, unreadable)
  }
}
