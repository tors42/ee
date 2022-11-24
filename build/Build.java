package build;

import java.io.*;
import java.lang.Runtime.Version;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.spi.ToolProvider;
import java.util.stream.*;

public class Build {

    static List<Artifact> deps = Stream.of(
            "io.github.tors42:chariot:0.0.57",
            "info.picocli:picocli:4.7.0",
            "com.formdev:flatlaf:2.6")
        .map(Artifact::of)
        .toList();

    static List<Stockfish> stockfishList = List.of(
            new Stockfish(Platform.of("linux", "x64"), "stockfish_15_linux_x64_bmi2.zip", true),
            new Stockfish(Platform.of("windows", "x64"), "stockfish_15_win_x64_bmi2.zip", true),
            new Stockfish(Platform.of("macos", "x64"), "stockfish_15_mac_x64_modern.zip", false),
            new Stockfish(Platform.of("macos", "aarch64"), "stockfish_15_mac_apple-silicon.zip", false));

    public static void main(String... args) throws Exception {
        var props = Arrays.stream(args)
            .filter(s -> s.contains("="))
            .map(s -> s.split("="))
            .collect(Collectors.toMap(kv -> kv[0], kv -> kv[1]));

        var module = props.getOrDefault("module", "ee");
        var version = props.getOrDefault("version", "0.0.1-SNAPSHOT");
        var timestamp = props.getOrDefault("timestamp", ZonedDateTime.now()
            .withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        var cross = Arrays.stream(args).anyMatch("cross"::equals);

        var javac = ToolProvider.findFirst("javac").orElseThrow();
        var jar   = ToolProvider.findFirst("jar").orElseThrow();
        var jlink = ToolProvider.findFirst("jlink").orElseThrow();

        String prefix = module + "-" + version;

        Path out = Path.of("out");
        Path lib = Path.of("lib");

        del(out);
        del(lib);

        Path cache = Path.of("cache");
        Path moduleSrc = Path.of("src");
        Path classes = out.resolve("classes");
        Path moduleOut = out.resolve("modules");
        Path metaInf = out.resolve("META-INF");
        Path manifest = out.resolve("MANIFEST.MF");

        Files.createDirectories(cache);
        Files.createDirectories(lib);
        Files.createDirectories(moduleOut);
        Files.createDirectories(metaInf);

        var executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        var cacheDependenciesTasks = deps.stream()
            .map(dep -> (Callable<Void>) () -> {
                Path cachedDep = cache.resolve(dep.filename());
                Path cachedDepSources = cache.resolve(dep.filenameSources());
                if (! cachedDep.toFile().exists()) {
                    System.out.println("Downloading " + dep.filename());
                    try (var source = dep.uri().toURL().openStream();
                         var target = Files.newOutputStream(cachedDep)) {
                        source.transferTo(target);
                    }
                    try (var source = dep.uriSources().toURL().openStream();
                         var target = Files.newOutputStream(cachedDepSources)) {
                        source.transferTo(target);
                    }
                } else {
                    System.out.println("Using " + cachedDep);
                }
                Files.copy(cachedDep, lib.resolve(dep.artifactId() + ".jar"));
                Files.copy(cachedDepSources, lib.resolve(dep.artifactId() + "-sources.jar"));
                return null;
            }).toList();

        executor.invokeAll(cacheDependenciesTasks);

        var stockfishFutures = stockfishList.stream()
            .filter(sf -> cross || sf.osArch().equals(Platform.current()))
            .map(sf -> (Runnable) () -> {
                Path cachedStockfish = cache.resolve(sf.filename());
                if (! cachedStockfish.toFile().exists()) {
                    if (sf.downloadable()) {
                        System.out.println("Downloading " + sf.filename());
                        try (var source = sf.uri().toURL().openStream();
                             var target = Files.newOutputStream(cachedStockfish)) {
                            source.transferTo(target);
                        } catch (IOException ioe) { throw new RuntimeException(ioe); }
                    } else {
                        System.out.println("Missing " + cachedStockfish);
                    }
                } else {
                    System.out.println("Using " + cachedStockfish);
                }
            })
            .map(runnable -> executor.submit(runnable))
            .toList();

        Files.copy(Path.of("LICENSE"), metaInf.resolve("LICENSE"));

        Files.writeString(manifest, """
                Implementation-Title: %s
                Implementation-Version: %s
                Created-By: %s
                """.formatted(module, version, Runtime.version()));

        run(javac,
                "--enable-preview",
                "--release", "19",
                "--module-source-path", moduleSrc.toString(),
                "--module", module,
                "--module-path", lib.toString(),
                "-d", classes.toString()
           );

        var launchers = List.of(
                "--launcher", "ee=ee/ee.GUI",
                "--launcher", "ee-cli=ee/ee.CLI"
                );

        var moduleJar = moduleOut.resolve(prefix + ".jar");

        run(jar,
                "--create",
                "--date", timestamp,
                "--manifest", manifest.toString(),
                "--module-version", version,
                "--file", moduleJar.toString(),
                "-C", out.toString(), "META-INF",
                "-C", classes.resolve(module).toString(), "."
           );

        if (! cross) {
            try (var stream = Files.walk(Path.of(System.getProperty("java.home")))) {
                Path nativeJmods = stream.filter(p -> p.getFileName().toString().equals("jmods"))
                    .findFirst()
                    .orElseThrow();

                run(jlink, Stream.concat(Stream.of(
                            "--add-options", " --enable-preview",
                            "--compress", "2",
                            "--no-man-pages",
                            "--no-header-files",
                            "--strip-debug",
                            "--module-path", String.join(File.pathSeparator, nativeJmods.toString(), moduleJar.toString(), lib.toString()),
                            "--add-modules", module,
                            "--output", out.resolve("runtime").toString()),
                            launchers.stream())
                        .toArray(String[]::new)
                   );
            }
        }

        for (var future : stockfishFutures) future.get();

        var stockfish = stockfishList.stream()
            .filter(sf -> sf.osArch().equals(Platform.current()))
            .map(sf -> cache.resolve(sf.filename()))
            .findFirst().orElse(Path.of(""));

        if (stockfish.toFile().exists()) {
            var stockDir = Files.createDirectory(out.resolve(Path.of("stockfish-" + Platform.current())));
            Files.copy(stockfish, stockDir.resolve("stockfish.zip"));
            var moduleEmbedJar = moduleOut.resolve(prefix + "-embed.jar");
            run(jar,
                    "--create",
                    "--date", timestamp,
                    "--manifest", manifest.toString(),
                    "--module-version", version,
                    "--file", moduleEmbedJar.toString(),
                    "-C", out.toString(), "META-INF",
                    "-C", classes.resolve(module).toString(), ".",
                    "-C", stockDir.toString(),"stockfish.zip"
               );
            del(stockDir);

            if (! cross) {

                try (var stream = Files.walk(Path.of(System.getProperty("java.home")))) {
                    Path nativeJmods = stream.filter(p -> p.getFileName().toString().equals("jmods"))
                        .findFirst()
                        .orElseThrow();

                    run(jlink, Stream.concat(Stream.of(
                                    "--add-options", " --enable-preview",
                                    "--compress", "2",
                                    "--no-man-pages",
                                    "--no-header-files",
                                    "--module-path", String.join(File.pathSeparator, nativeJmods.toString(), moduleEmbedJar.toString(), lib.toString()),
                                    "--add-modules", module,
                                    "--output", out.resolve("runtime-embed").toString()),
                                launchers.stream())
                            .toArray(String[]::new)
                       );
                }
            }
        }

        if (! cross) {
            try {
                new ProcessBuilder("zip", "-r", "../" + prefix + "-" + Platform.current() + ".zip", ".")
                    .directory(out.resolve("runtime").toFile())
                    .start().waitFor();

                if (out.resolve("runtime-embed").toFile().exists()) {
                    new ProcessBuilder("zip", "-r", "../" + prefix + "-" + Platform.current() + "-embed.zip", ".")
                        .directory(out.resolve("runtime-embed").toFile())
                        .start().waitFor();
                }
            } catch (Exception e){}

            executor.shutdown();
            return;
        }

        if (cross) {
            record Jdk(Platform osArch, String ext) {
                String osAndArch() { return String.join("-", osArch.os(), osArch.arch()); }
            }
            record VersionedJdk(Jdk jdk, Version version) {
                String toVersionString() { return version.version().stream().map(String::valueOf).collect(Collectors.joining(".")); }
                String toBuildString() { return version.build().map(String::valueOf).orElseThrow(); }
            }
            record DownloadableVersionedJdk(VersionedJdk versionedJdk, URI uri) {}
            record JmodsPath(DownloadableVersionedJdk downloadableVersionedJdk, Path jmods) {}

            //https://download.java.net/java/GA/jdk19.0.1/afdd2e245b014143b62ccb916125e3ce/10/GPL/openjdk-19.0.1_linux-x64_bin.tar.gz
            Version javaVersion = Version.parse("19.0.1+10");

            var jdks = List.of(
                    new Jdk(Platform.of("linux", "x64"), "tar.gz"),
                    new Jdk(Platform.of("macos", "x64"), "tar.gz"),
                    new Jdk(Platform.of("macos", "aarch64"), "tar.gz"),
                    new Jdk(Platform.of("windows", "x64"), "zip")
                    );
            Function<VersionedJdk, URI> toOpenJdkUri = vjdk -> {
                //https://jdk.java.net/19
                String javaVersionString = vjdk.toVersionString();
                String buildString       = vjdk.toBuildString();

                String id = "afdd2e245b014143b62ccb916125e3ce";
                String baseUrl = "https://download.java.net/java/GA/jdk%s/%s/%s/GPL/".formatted(javaVersionString, id, buildString);
                String filenameTemplate = "openjdk-%s".formatted(javaVersionString).concat("_%s-%s_bin.%s");

                var jdk = vjdk.jdk();
                URI uri = URI.create(baseUrl + filenameTemplate.formatted(jdk.osArch().os(), jdk.osArch().arch(), jdk.ext()));
                return uri;
            };

            BiFunction<DownloadableVersionedJdk, Path, Path> toJmodsPath = (jdk, cacheDir) ->
                cacheDir.resolve("jmods-%s-%s".formatted(
                            jdk.versionedJdk().jdk().osAndArch(),
                            jdk.versionedJdk().toVersionString()));

            BiConsumer<Path, JmodsPath> unpack = (archive, target) -> {

                String javaVersionString = target.downloadableVersionedJdk().versionedJdk().toVersionString();
                String cacheDir = target.jmods().getParent().toString();

                ProcessBuilder pb = switch(target.downloadableVersionedJdk().versionedJdk().jdk().osArch().os()) {
                    case "windows" -> new ProcessBuilder(
                            "unzip",
                            "-j",
                            archive.toString(),
                            "jdk-"+javaVersionString+"/jmods/*",
                            "-d",
                            target.jmods().toString());

                    case "linux" -> new ProcessBuilder(
                            "tar",
                            "xzf",
                            archive.toString(),
                            "-C",
                            cacheDir,
                            "jdk-"+javaVersionString+"/jmods/",
                            "--transform=s/jdk-"+javaVersionString+".jmods/"+target.jmods().getFileName()+"/g");

                    case "macos" -> new ProcessBuilder(
                            "tar",
                            "xzf",
                            archive.toString(),
                            "-C",
                            cacheDir,
                            "./jdk-"+javaVersionString+".jdk/Contents/Home/jmods/",
                            "--transform=s/..jdk-"+javaVersionString+".jdk.Contents.Home.jmods/"+target.jmods().getFileName()+"/g");

                   default -> throw new IllegalArgumentException(target.downloadableVersionedJdk().versionedJdk().jdk().osArch().os());
                };

                try {
                    int exitValue = pb.start().waitFor();
                    if (exitValue != 0) {
                        System.out.println("Failure executing " + cacheDir + " - " + pb.toString());
                    }
                } catch(Exception e) {
                    throw new RuntimeException(e);
                }
            };

            var jmodsPaths = jdks.stream()
                .map(jdk -> new VersionedJdk(jdk, javaVersion))
                .map(jdk -> new DownloadableVersionedJdk(jdk, toOpenJdkUri.apply(jdk)))
                .map(jdk -> new JmodsPath(jdk, toJmodsPath.apply(jdk, cache)))
                .toList();

            var downloadAndUnpackTasks = jmodsPaths.stream()
                .map(jdk -> (Callable<Void>) () -> {
                    Path jmods = jdk.jmods();
                    if (! jmods.toFile().exists()) {
                        Path archive = Files.createTempFile(jdk.downloadableVersionedJdk().versionedJdk().jdk().osAndArch()+"-", "");
                        archive.toFile().deleteOnExit();

                        System.out.println("Downloading " + jdk.downloadableVersionedJdk());
                        try (var source = jdk.downloadableVersionedJdk().uri().toURL().openStream();
                             var target = Files.newOutputStream(archive))
                        {
                            source.transferTo(target);
                        }

                        unpack.accept(archive, jdk);
                    } else {
                        System.out.println("Using " + jmods);
                    }
                    return null;
                }
                ).toList();

            executor.invokeAll(downloadAndUnpackTasks);
            executor.shutdown();

            jmodsPaths.stream()
                .forEach(jdk -> {
                    run(jlink, Stream.concat( Stream.of(
                                "--add-options", " --enable-preview",
                                "--compress", "2",
                                "--no-man-pages",
                                "--no-header-files",
                                "--module-path", String.join(File.pathSeparator, jdk.jmods().toString(), moduleJar.toString(), lib.toString()),
                                "--add-modules", module,
                                "--output", out.resolve(jdk.downloadableVersionedJdk().versionedJdk().jdk().osAndArch()).resolve(prefix).toString()),
                                launchers.stream())
                            .toArray(String[]::new)
                       );

                    var cachedStockfish = stockfishList.stream()
                        .filter(sf -> sf.osArch().equals(jdk.downloadableVersionedJdk().versionedJdk().jdk().osArch()))
                        .map(sf -> cache.resolve(sf.filename()))
                        .findFirst().orElse(Path.of(""));

                    if (cachedStockfish.toFile().exists()) {
                        try {
                            var stockDir = Files.createDirectory(out.resolve("stockfish-" + jdk.downloadableVersionedJdk().versionedJdk().jdk().osArch()));
                            Files.copy(cachedStockfish, stockDir.resolve("stockfish.zip"));

                            var moduleEmbedJar = moduleOut.resolve(prefix + "-embed.jar");
                            run(jar,
                                    "--create",
                                    "--date", timestamp,
                                    "--manifest", manifest.toString(),
                                    "--module-version", version,
                                    "--file", moduleEmbedJar.toString(),
                                    "-C", out.toString(), "META-INF",
                                    "-C", classes.resolve(module).toString(), ".",
                                    "-C", stockDir.toString(),"stockfish.zip"
                               );

                            run(jlink, Stream.concat( Stream.of(
                                            "--add-options", " --enable-preview",
                                            "--compress", "2",
                                            "--no-man-pages",
                                            "--no-header-files",
                                            "--module-path", String.join(File.pathSeparator, jdk.jmods().toString(), moduleEmbedJar.toString(), lib.toString()),
                                            "--add-modules", module,
                                            "--output", out.resolve(jdk.downloadableVersionedJdk().versionedJdk().jdk().osAndArch() + "-embed").resolve(prefix).toString()),
                                        launchers.stream())
                                    .toArray(String[]::new)
                               );
                        } catch(IOException ioe) {
                            throw new RuntimeException(ioe);
                        }
                    }

                });

            jmodsPaths.stream()
                .forEach(jdk -> {
                    var osArch = jdk.downloadableVersionedJdk().versionedJdk().jdk().osAndArch();
                        try {
                            new ProcessBuilder("zip", "-r", "../" + prefix + "-" + osArch + ".zip", ".")
                                .directory(out.resolve(osArch).toFile())
                                .start().waitFor();

                            if (out.resolve(osArch + "-embed").toFile().exists()) {
                                new ProcessBuilder("zip", "-r", "../" + prefix + "-" + osArch + "-embed.zip", ".")
                                    .directory(out.resolve(osArch + "-embed").toFile())
                                    .start().waitFor();
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                });
        }
    }

    static void del(Path dir) {
        if (dir.toFile().exists()) {
            try (var files = Files.walk(dir)) {
                files.sorted(Collections.reverseOrder()).map(Path::toFile).forEach(f -> f.delete());
            } catch (Exception e) {}
        }
    }

    static void run(ToolProvider tool, String... args) {
        var out = new StringWriter();
        var err = new StringWriter();

        int exitCode = tool.run(new PrintWriter(out), new PrintWriter(err), args);

        if (exitCode != 0) {
            out.flush();
            err.flush();
            System.err.format("""
                    %s exited with code %d
                    args:   %s
                    stdout: %s
                    stderr: %s%n""",
                    tool, exitCode, Arrays.stream(args).collect(Collectors.joining(" ")),
                    out.toString(), err.toString());
            System.exit(exitCode);
        }
    }

    record Artifact(String groupId, String artifactId, String version) {
        static Artifact of(String gav) { String[] arr = gav.split(":"); return new Artifact(arr[0], arr[1], arr[2]); }
        URI uri()        { return forName(filename()); }
        URI uriSources() { return forName(filenameSources()); }
        URI forName(String filename) { return URI.create("https://repo1.maven.org/maven2/%s/%s/%s/%s"
                .formatted(groupId.replaceAll("\\.", "/"), artifactId, version, filename)); }
        String filename() { return "%s-%s.jar".formatted(artifactId, version); }
        String filenameSources() { return "%s-%s-sources.jar".formatted(artifactId, version); }
    }

    record Stockfish(Platform osArch, String filename, boolean downloadable) {
        URI uri() { return URI.create("https://stockfishchess.org/files/" + filename); }
    }

    record Platform(String os, String arch) {
        static Platform of(String os, String arch) { return new Platform(os, arch); }
        static Platform current() {
            String name = System.getProperty("os.name", "linux").toLowerCase();
            String arch = System.getProperty("os.arch", "x64").toLowerCase();
            if (name.contains("win")) {
                name = "windows";
            } else if (name.contains("mac")) {
                name = "macos";
            } else if (name.contains("linux")) {
                name = "linux";
            } else {
                System.err.println("Unrecognized os.name: " + name);
                name = "linux";
            }
            if (arch.contains("aarch64")) {
                arch = "aarch64";
            } else if (arch.contains("x64")) {
                arch = "x64";
            } else if (arch.contains("amd64")) {
                arch = "x64";
            } else {
                System.err.println("Unrecognized os.arch: " + arch);
                arch = "x64";
            }
            return Platform.of(name, arch);
        }
        @Override public String toString() { return os() + "-" + arch(); }
    }
}
