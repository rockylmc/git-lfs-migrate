package git.lfs.migrate;

import com.beust.jcommander.internal.Nullable;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.codec.binary.Hex;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Converter for git objects.
 * Created by bozaro on 09.06.15.
 */
public class GitConverter {
  @NotNull
  private static final String GIT_ATTRIBUTES = ".gitattributes";
  @NotNull
  private final Repository srcRepo;
  @NotNull
  private final Repository dstRepo;
  @NotNull
  private final RevWalk revWalk;
  @Nullable
  private final URL lfs;
  @NotNull
  private final String[] suffixes;
  @NotNull
  private final File tmpDir;
  @NotNull
  private final ObjectInserter inserter;

  public GitConverter(@NotNull Repository srcRepo, @NotNull Repository dstRepo, @Nullable URL lfs, @NotNull String[] suffixes) {
    this.srcRepo = srcRepo;
    this.dstRepo = dstRepo;
    this.revWalk = new RevWalk(srcRepo);
    this.inserter = dstRepo.newObjectInserter();
    this.suffixes = suffixes.clone();
    this.lfs = lfs;

    tmpDir = new File(dstRepo.getDirectory(), "lfs/tmp");
    tmpDir.mkdirs();
  }

  @NotNull
  public ConvertTask convertTask(@NotNull TaskKey key) throws IOException {
    switch (key.getType()) {
      case Simple: {
        final RevObject revObject = revWalk.parseAny(key.getObjectId());
        if (revObject instanceof RevCommit) {
          return convertCommitTask((RevCommit) revObject);
        }
        if (revObject instanceof RevTree) {
          return convertTreeTask(revObject, false);
        }
        if (revObject instanceof RevBlob) {
          return copyTask(revObject);
        }
        if (revObject instanceof RevTag) {
          return convertTagTask((RevTag) revObject);
        }
        throw new IllegalStateException("Unsupported object type: " + key + " (" + revObject.getClass().getName() + ")");
      }
      case Root: {
        final RevObject revObject = revWalk.parseAny(key.getObjectId());
        if (revObject instanceof RevTree) {
          return convertTreeTask(revObject, true);
        }
        throw new IllegalStateException("Unsupported object type: " + key + " (" + revObject.getClass().getName() + ")");
      }
      case Attribute:
        return createAttributesTask(key.getObjectId());
      case UploadLfs:
        return convertLfsTask(key.getObjectId());
      default:
        throw new IllegalStateException("Unknwon task key type: " + key.getType());
    }
  }

  public void flush() throws IOException {
    inserter.flush();
  }

  @NotNull
  private ConvertTask convertTagTask(@NotNull RevTag revObject) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() {
        return Collections.singletonList(
            new TaskKey(TaskType.Simple, revObject.getObject())
        );
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ConvertResolver resolver) throws IOException {
        final ObjectId id = resolver.resolve(TaskType.Simple, revObject.getObject());
        final TagBuilder builder = new TagBuilder();
        builder.setMessage(revObject.getFullMessage());
        builder.setTag(revObject.getTagName());
        builder.setTagger(revObject.getTaggerIdent());
        builder.setObjectId(id, revObject.getObject().getType());
        return inserter.insert(builder);
      }
    };
  }

  @NotNull
  private ConvertTask convertCommitTask(@NotNull RevCommit revObject) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() {
        List<TaskKey> result = new ArrayList<>();
        for (RevCommit parent : revObject.getParents()) {
          result.add(new TaskKey(TaskType.Simple, parent));
        }
        result.add(new TaskKey(TaskType.Root, revObject.getTree()));
        return result;
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ConvertResolver resolver) throws IOException {
        final CommitBuilder builder = new CommitBuilder();
        builder.setAuthor(revObject.getAuthorIdent());
        builder.setCommitter(revObject.getCommitterIdent());
        builder.setEncoding(revObject.getEncoding());
        builder.setMessage(revObject.getFullMessage());
        // Set parents
        for (RevCommit oldParent : revObject.getParents()) {
          builder.addParentId(resolver.resolve(TaskType.Simple, oldParent));
        }
        // Set tree
        builder.setTreeId(resolver.resolve(TaskType.Root, revObject.getTree()));
        return inserter.insert(builder);
      }
    };
  }

  @NotNull
  private ConvertTask convertTreeTask(@NotNull ObjectId id, boolean rootTree) {
    return new ConvertTask() {
      @NotNull
      private List<GitTreeEntry> getEntries() throws IOException {
        final List<GitTreeEntry> entries = new ArrayList<>();
        final CanonicalTreeParser treeParser = new CanonicalTreeParser(null, srcRepo.newObjectReader(), id);
        boolean needAttributes = rootTree;
        while (!treeParser.eof()) {
          final FileMode fileMode = treeParser.getEntryFileMode();
          final TaskType blobTask;
          if (needAttributes && treeParser.getEntryPathString().equals(GIT_ATTRIBUTES)) {
            blobTask = TaskType.Attribute;
            needAttributes = false;
          } else if ((fileMode.getObjectType() == Constants.OBJ_BLOB) && (fileMode == FileMode.REGULAR_FILE) && matchFilename(treeParser.getEntryPathString())) {
            blobTask = TaskType.UploadLfs;
          } else {
            blobTask = TaskType.Simple;
          }
          entries.add(new GitTreeEntry(fileMode, new TaskKey(blobTask, treeParser.getEntryObjectId()), treeParser.getEntryPathString()));
          treeParser.next();
        }
        if (needAttributes && suffixes.length > 0) {
          entries.add(new GitTreeEntry(FileMode.REGULAR_FILE, new TaskKey(TaskType.Attribute, ObjectId.zeroId()), GIT_ATTRIBUTES));
        }
        return entries;
      }

      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        final List<TaskKey> result = new ArrayList<>();
        for (GitTreeEntry entry : getEntries()) {
          result.add(entry.getTaskKey());
        }
        return result;
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ConvertResolver resolver) throws IOException {
        final List<GitTreeEntry> entries = getEntries();
        // Create new tree.
        Collections.sort(entries);
        final TreeFormatter treeBuilder = new TreeFormatter();
        for (GitTreeEntry entry : entries) {
          treeBuilder.append(entry.getFileName(), entry.getFileMode(), resolver.resolve(entry.getTaskKey()));
        }
        new ObjectChecker().checkTree(treeBuilder.toByteArray());
        return inserter.insert(treeBuilder);
      }
    };
  }

  private boolean matchFilename(@NotNull String fileName) {
    for (String suffix : suffixes) {
      if (fileName.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  @NotNull
  private ConvertTask convertLfsTask(@Nullable ObjectId id) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        return Collections.emptyList();
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ConvertResolver resolver) throws IOException {
        final MessageDigest md;
        try {
          md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
          throw new IllegalStateException(e);
        }
        // Create LFS stream.
        final File tmpFile = new File(tmpDir, id.getName());
        final ObjectLoader loader = srcRepo.open(id, Constants.OBJ_BLOB);
        try (InputStream istream = loader.openStream();
             OutputStream ostream = new FileOutputStream(tmpFile)) {
          byte[] buffer = new byte[0x10000];
          while (true) {
            int size = istream.read(buffer);
            if (size <= 0) break;
            ostream.write(buffer, 0, size);
            md.update(buffer, 0, size);
          }
        }
        String hash = new String(Hex.encodeHex(md.digest(), true));
        // Upload file.
        upload(hash, loader.getSize(), tmpFile);
        // Rename file.
        final File lfsFile = new File(dstRepo.getDirectory(), "lfs/objects/" + hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash);
        lfsFile.getParentFile().mkdirs();
        if (lfsFile.exists()) {
          tmpFile.delete();
        } else if (!tmpFile.renameTo(lfsFile)) {
          throw new IOException("Can't rename file: " + tmpFile + " -> " + lfsFile);
        }
        // Create pointer.
        StringWriter pointer = new StringWriter();
        pointer.write("version https://git-lfs.github.com/spec/v1\n");
        pointer.write("oid sha256:" + hash + "\n");
        pointer.write("size " + loader.getSize() + "\n");

        return inserter.insert(Constants.OBJ_BLOB, pointer.toString().getBytes(StandardCharsets.UTF_8));
      }
    };
  }

  private void upload(@NotNull String hash, long size, @NotNull File file) throws IOException {
    if (lfs == null) {
      return;
    }

    HttpURLConnection conn = (HttpURLConnection) new URL(lfs, "objects").openConnection();
    conn.setRequestMethod("POST");
    conn.addRequestProperty("Accept", "application/vnd.git-lfs+json");
    if (lfs.getUserInfo() != null) {
      conn.addRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(lfs.getUserInfo().getBytes(StandardCharsets.UTF_8)));
    }
    conn.addRequestProperty("Content-Type", "application/vnd.git-lfs+json");

    conn.setDoOutput(true);
    try (Writer writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
      JsonWriter json = new JsonWriter(writer);
      json.beginObject();
      json.name("oid").value(hash);
      json.name("size").value(size);
      json.endObject();
    }
    if (conn.getResponseCode() == 200) {
      // Already uploaded.
      return;
    }

    final JsonObject upload;
    try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
      JsonElement json = new JsonParser().parse(reader);
      upload = json.getAsJsonObject().get("_links").getAsJsonObject().get("upload").getAsJsonObject();
    }

    // Upload data.
    conn = (HttpURLConnection) new URL(upload.get("href").getAsString()).openConnection();
    conn.setRequestMethod("PUT");
    for (Map.Entry<String, JsonElement> header : upload.get("header").getAsJsonObject().entrySet()) {
      conn.addRequestProperty(header.getKey(), header.getValue().getAsString());
    }
    conn.setDoOutput(true);
    conn.setFixedLengthStreamingMode(size);
    try (OutputStream ostream = conn.getOutputStream();
         InputStream istream = new FileInputStream(file)) {
      byte[] buffer = new byte[0x10000];
      while (true) {
        int len = istream.read(buffer);
        if (len <= 0) break;
        ostream.write(buffer, 0, len);
      }
    }
    conn.getInputStream().close();
  }

  @NotNull
  private ConvertTask createAttributesTask(@Nullable ObjectId id) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        return Collections.emptyList();
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ConvertResolver resolver) throws IOException {
        final Set<String> attributes = new TreeSet<>();
        for (String suffix : suffixes) {
          attributes.add("*" + suffix + "\tfilter=lfs diff=lfs merge=lfs -crlf");
        }
        final ByteArrayOutputStream blob = new ByteArrayOutputStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(openAttributes(id), StandardCharsets.UTF_8))) {
          while (true) {
            String line = reader.readLine();
            if (line == null) break;
            if (!attributes.remove(line)) {
              blob.write(line.getBytes(StandardCharsets.UTF_8));
              blob.write('\n');
            }
          }
        }
        for (String line : attributes) {
          blob.write(line.getBytes(StandardCharsets.UTF_8));
          blob.write('\n');
        }
        return inserter.insert(Constants.OBJ_BLOB, blob.toByteArray());
      }
    };
  }

  private ConvertTask copyTask(@NotNull ObjectId id) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        return Collections.emptyList();
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ConvertResolver resolver) throws IOException {
        if (!dstRepo.hasObject(id)) {
          ObjectLoader loader = srcRepo.open(id);
          try (ObjectStream stream = loader.openStream()) {
            inserter.insert(loader.getType(), loader.getSize(), stream);
          }
        }
        return id;
      }
    };
  }

  @NotNull
  private InputStream openAttributes(@Nullable ObjectId id) throws IOException {
    if (ObjectId.zeroId().equals(id)) {
      return new ByteArrayInputStream(new byte[0]);
    }
    return srcRepo.open(id, Constants.OBJ_BLOB).openStream();
  }

  public enum TaskType {
    Simple, Root, Attribute, UploadLfs,
  }

  public interface ConvertResolver {
    @NotNull
    ObjectId resolve(@NotNull TaskKey key);

    @NotNull
    default ObjectId resolve(@NotNull TaskType type, @NotNull ObjectId objectId) {
      return resolve(new TaskKey(type, objectId));
    }
  }

  public interface ConvertTask {
    @NotNull
    Iterable<TaskKey> depends() throws IOException;

    @NotNull
    ObjectId convert(@NotNull ConvertResolver resolver) throws IOException;
  }

}
