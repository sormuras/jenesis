import module java.base;

record Installer() {
  void install() {
    new Initializer().initialize();
  }

  interface Internal {
    boolean DEBUG = Boolean.getBoolean("-Debug".substring(2));

    static void debug(String message) {
      if (DEBUG) IO.println(message);
    }

    static void copy(String source, Path target, CopyOption... options) throws Exception {
      debug("<< %s".formatted(source));
      Files.createDirectories(target.getParent());
      try (var stream =
          source.startsWith("http")
              ? URI.create(source).toURL().openStream()
              : Files.newInputStream(Path.of(source))) {
        var size = Files.copy(stream, target, options);
        debug(">> %,7d %s".formatted(size, target.getFileName()));
      }
    }

    static void delete(Path path) throws Exception {
      var start = path.normalize().toAbsolutePath();
      if (Files.notExists(start)) return;
      for (var root : start.getFileSystem().getRootDirectories()) {
        if (start.equals(root)) {
          debug("deletion of root directory?! " + path);
          return;
        }
      }
      debug("delete directory tree " + start);
      try (var stream = Files.walk(start)) {
        var files = stream.sorted((p, q) -> -p.compareTo(q));
        for (var file : files.toArray(Path[]::new)) Files.deleteIfExists(file);
      }
    }

    static boolean head(String source) throws Exception {
      var url = URI.create(source).toURL();
      var con = (HttpURLConnection) url.openConnection();
      try {
        con.setRequestMethod("HEAD");
        var status = con.getResponseCode();
        debug("%d <- HEAD %s".formatted(status, source));
        if (status < 299) return true;
      } finally {
        con.disconnect();
      }
      return false;
    }

    static void java(String... args) {
      var java = Path.of(System.getProperty("java.home"), "bin", "java" /*.exe*/);
      var code = run(List.of(java.toString()), args);
      if (code == 0) return;
      throw new RuntimeException("Non-zero error code: " + code);
    }

    static int run(List<String> command, String... arguments) {
      debug("| " + String.join(" ", command));
      var out = System.out;
      var err = System.err;
      record LinePrinter(InputStream stream, PrintStream writer) implements Runnable {
        @Override
        public void run() {
          new BufferedReader(new InputStreamReader(stream)).lines().forEach(writer::println);
        }
      }
      var processBuilder = new ProcessBuilder(new ArrayList<>(command));
      processBuilder.command().addAll(List.of(arguments));
      try (var process = processBuilder.start()) {
        var threadBuilder = Thread.ofVirtual();
        threadBuilder.name("-out").start(new LinePrinter(process.getInputStream(), out));
        threadBuilder.name("-err").start(new LinePrinter(process.getErrorStream(), err));
        return process.isAlive() ? process.waitFor() : process.exitValue();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return -1;
      } catch (Exception exception) {
        exception.printStackTrace(err);
        return 1;
      }
    }

    static void unzip(Path zip, Path dir, int sub) throws Exception {
      debug("<< %s".formatted(zip.toUri()));
      debug(">> %s".formatted(dir.toUri()));
      var files = new ArrayList<Path>();
      try (var fs = FileSystems.newFileSystem(zip)) {
        for (var root : fs.getRootDirectories()) {
          try (var stream = Files.walk(root)) {
            var list = stream.filter(Files::isRegularFile).toList();
            for (var file : list) {
              var relative = root.relativize(file);
              var source = sub == 0 ? relative : relative.subpath(sub, relative.getNameCount());
              var target = dir.resolve(source.toString());
              // debug(target.toUri().toString());
              Files.createDirectories(target.getParent());
              Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
              files.add(target);
            }
          }
        }
      }
      debug(">> %d files copied".formatted(files.size()));
    }
  }

  record Initializer(String repo, String version, Path home, Path path) {
    // source repository, owner/name
    static String REPO = "jenesis/jenesis";
    // defaults to git head reference of the `main` branch
    static String VERSION = System.getProperty("-Dversion".substring(2), "main");
    // defaults to the current working directory
    static Path HOME = Path.of(System.getProperty("-Dhome".substring(2), ""));
    // defaults to "build/jenesis"
    static Path PATH = Path.of(System.getProperty("-Dpath".substring(2), "build/jenesis"));

    Initializer() {
      this(VERSION);
    }

    Initializer(String version) {
      this(REPO, version, HOME, PATH);
    }

    void initialize() {
      try {
        initializeSources();
        initializeArgumentFiles();
      } catch (Exception exception) {
        System.err.println("Initialization failed: " + exception.getMessage());
      }
    }

    void initializeSources() throws Exception {
      var uris =
          List.of(
              "https://github.com/" + repo + "/archive/refs/tags/" + version + ".zip",
              "https://github.com/" + repo + "/archive/refs/heads/" + version + ".zip");
      for (var uri : uris) {
        if (Internal.head(uri)) {
          installSourcesFromUri(uri);
          return;
        }
      }
    }

    void installSourcesFromUri(String uri) throws Exception {
      var tmp = Files.createTempDirectory("jenesis-" + version + "-");
      var dir = Files.createDirectories(home.resolve(path));
      var zip = tmp.resolve("jenesis-" + version + ".zip");
      IO.println("Installing Jenesis [" + version + "] into " + path.toUri() + "...");
      // download and unzip
      Internal.copy(uri, zip, StandardCopyOption.REPLACE_EXISTING);
      Internal.unzip(zip, dir, 1);
      // clean up
      Internal.delete(tmp);
    }

    void initializeArgumentFiles() throws Exception {
      var jenesis = home.resolve("jenesis");
      if (!Files.exists(jenesis)) {
        var program = home.resolve(path).resolve("Main.java");
        var command = home.relativize(program).toString().replace('\\', '/');
        var lines = List.of("# Argument file for launching Jensis", command);
        Files.write(jenesis, lines);
      }
    }
  }
}
