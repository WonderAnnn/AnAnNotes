package com.anan.anannotes.data.files;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Debug-only, per-test DocumentsProvider used to exercise the production SAF adapter. */
public final class TestSafDocumentsProvider extends DocumentsProvider {
  public static final String AUTHORITY = "com.anan.anannotes.test.saf.documents";

  private static final String ROOT_PREFIX = "root:";
  private static final String ROOT_TITLE = "Markdown SAF Test";
  private static final String SESSIONS_DIRECTORY = "test-only-saf-export";
  private static final Pattern SESSION_ID = Pattern.compile("[0-9a-fA-F-]{36}");
  private static volatile boolean failNextWriteOpen;

  private static final String[] ROOT_COLUMNS = {
      DocumentsContract.Root.COLUMN_ROOT_ID,
      DocumentsContract.Root.COLUMN_FLAGS,
      DocumentsContract.Root.COLUMN_TITLE,
      DocumentsContract.Root.COLUMN_DOCUMENT_ID,
      DocumentsContract.Root.COLUMN_MIME_TYPES,
      DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
  };

  private static final String[] DOCUMENT_COLUMNS = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_FLAGS,
      DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED,
  };

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    String[] columns = projection == null ? ROOT_COLUMNS : projection;
    MatrixCursor cursor = new MatrixCursor(columns);
    File[] roots = sessionsDirectory().listFiles(File::isDirectory);
    if (roots == null) return cursor;

    for (File directory : roots) {
      String rootId = rootDocumentId(directory.getName());
      MatrixCursor.RowBuilder row = cursor.newRow();
      for (String column : columns) {
        Object value;
        if (DocumentsContract.Root.COLUMN_ROOT_ID.equals(column)) value = rootId;
        else if (DocumentsContract.Root.COLUMN_FLAGS.equals(column)) value = DocumentsContract.Root.FLAG_SUPPORTS_CREATE;
        else if (DocumentsContract.Root.COLUMN_TITLE.equals(column)) value = ROOT_TITLE;
        else if (DocumentsContract.Root.COLUMN_DOCUMENT_ID.equals(column)) value = rootId;
        else if (DocumentsContract.Root.COLUMN_MIME_TYPES.equals(column)) value = "*/*";
        else if (DocumentsContract.Root.COLUMN_AVAILABLE_BYTES.equals(column)) value = directory.getUsableSpace();
        else value = null;
        row.add(column, value);
      }
    }
    return cursor;
  }

  @Override
  public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
    File file = resolve(documentId, true);
    String[] columns = projection == null ? DOCUMENT_COLUMNS : projection;
    MatrixCursor cursor = new MatrixCursor(columns);
    MatrixCursor.RowBuilder row = cursor.newRow();
    for (String column : columns) row.add(column, documentValue(column, documentId, file));
    return cursor;
  }

  @Override
  public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
      throws FileNotFoundException {
    File parent = resolve(parentDocumentId, true);
    if (!parent.isDirectory()) throw new FileNotFoundException("Not a directory");

    String[] columns = projection == null ? DOCUMENT_COLUMNS : projection;
    MatrixCursor cursor = new MatrixCursor(columns);
    File[] children = parent.listFiles();
    if (children == null) return cursor;
    java.util.Arrays.sort(children, java.util.Comparator.comparing(File::getName));
    for (File file : children) {
      String documentId = parentDocumentId + "/" + file.getName();
      MatrixCursor.RowBuilder row = cursor.newRow();
      for (String column : columns) row.add(column, documentValue(column, documentId, file));
    }
    return cursor;
  }

  @Override
  public String createDocument(String parentDocumentId, String mimeType, String displayName)
      throws FileNotFoundException {
    File parent = resolve(parentDocumentId, true);
    if (!parent.isDirectory()) throw new FileNotFoundException("Parent is not a directory");
    if (displayName == null || displayName.trim().isEmpty() || ".".equals(displayName) || "..".equals(displayName)
        || displayName.contains("/") || displayName.contains("\\")) {
      throw new FileNotFoundException("Invalid document name");
    }

    File file = new File(parent, displayName);
    try {
      if (!file.createNewFile()) throw new FileNotFoundException("Document already exists");
    } catch (IOException error) {
      FileNotFoundException wrapped = new FileNotFoundException("Unable to create test document");
      wrapped.initCause(error);
      throw wrapped;
    }
    if (getContext() != null) getContext().getContentResolver().notifyChange(documentUri(parentDocumentId), null);
    return parentDocumentId + "/" + displayName;
  }

  @Override
  public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    if (signal != null) signal.throwIfCanceled();
    if (mode != null && mode.contains("w") && failNextWriteOpen) {
      failNextWriteOpen = false;
      throw new FileNotFoundException("Injected test-only SAF write-open failure");
    }
    File file = resolve(documentId, false);
    if (file.isDirectory()) throw new FileNotFoundException("Cannot open a directory");
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
  }

  @Override
  public void deleteDocument(String documentId) throws FileNotFoundException {
    File file = resolve(documentId, false);
    if (!deleteRecursively(file)) throw new FileNotFoundException("Unable to remove test document");
  }

  @Override
  public boolean isChildDocument(String parentDocumentId, String documentId) {
    return documentId.equals(parentDocumentId) || documentId.startsWith(parentDocumentId + "/");
  }

  private File resolve(String documentId, boolean createRoot) throws FileNotFoundException {
    int rootEnd = documentId.indexOf('/');
    String rootId = rootEnd < 0 ? documentId : documentId.substring(0, rootEnd);
    if (!rootId.startsWith(ROOT_PREFIX)) throw new FileNotFoundException("Unknown test root");

    String sessionId = rootId.substring(ROOT_PREFIX.length());
    if (!SESSION_ID.matcher(sessionId).matches()) throw new FileNotFoundException("Invalid test root");
    File root;
    try {
      root = new File(sessionsDirectory(), sessionId).getCanonicalFile();
    } catch (IOException error) {
      FileNotFoundException wrapped = new FileNotFoundException("Unable to resolve test root");
      wrapped.initCause(error);
      throw wrapped;
    }
    File file;
    if (rootEnd < 0) {
      file = root;
    } else {
      String childName = documentId.substring(rootEnd + 1);
      if (childName.isEmpty() || childName.contains("/") || childName.contains("\\")
          || ".".equals(childName) || "..".equals(childName)) {
        throw new FileNotFoundException("Invalid test document");
      }
      try {
        file = new File(root, childName).getCanonicalFile();
      } catch (IOException error) {
        FileNotFoundException wrapped = new FileNotFoundException("Unable to resolve test document");
        wrapped.initCause(error);
        throw wrapped;
      }
    }

    if (!file.equals(root) && !root.equals(file.getParentFile())) {
      throw new FileNotFoundException("Invalid test document path");
    }
    if (createRoot && !root.exists() && !root.mkdirs()) {
      throw new FileNotFoundException("Unable to create test root");
    }
    return file;
  }

  private Object documentValue(String column, String documentId, File file) {
    if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) return documentId;
    if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) return file.isDirectory() ? ROOT_TITLE : file.getName();
    if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) {
      return file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : mimeTypeFor(file);
    }
    if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) {
      return file.isDirectory()
          ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE
          : DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
    }
    if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) return file.isFile() ? file.length() : 0L;
    if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) return file.lastModified();
    return null;
  }

  private String mimeTypeFor(File file) {
    String name = file.getName().toLowerCase(java.util.Locale.ROOT);
    if (name.endsWith(".zip")) return "application/zip";
    if (name.endsWith(".md")) return "text/markdown";
    if (name.endsWith(".txt")) return "text/plain";
    return "application/octet-stream";
  }

  private File sessionsDirectory() {
    return new File(getContext().getFilesDir(), SESSIONS_DIRECTORY);
  }

  private Uri documentUri(String documentId) {
    return DocumentsContract.buildDocumentUri(AUTHORITY, documentId);
  }

  private boolean deleteRecursively(File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) if (!deleteRecursively(child)) return false;
      }
    }
    return file.delete();
  }

  private static String rootDocumentId(String sessionId) {
    return ROOT_PREFIX + sessionId;
  }

  public static Uri treeUri() {
    return DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootDocumentId(UUID.randomUUID().toString()));
  }

  public static void failNextOpenForTest() {
    failNextWriteOpen = true;
  }
}
