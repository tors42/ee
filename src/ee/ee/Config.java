package ee;

import java.io.*;
import java.lang.System.Logger.Level;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

import chariot.Client;
import ee.Engine.Parameters;

record Config(String providerUuid, String engineId, EngineConf engineConf, Client client, boolean light) {

    static System.Logger logger = System.getLogger(Config.class.getModule().getName());
    static Path builtInExecutable = null;

    static Config init() {
        var prefs = prefs();

        String providerUuid = prefs.get("providerUuid", null);
        if (providerUuid == null) {
            providerUuid = UUID.randomUUID().toString();
            prefs.put("providerUuid", providerUuid);
            try { prefs.flush(); } catch (Exception e) {throw new RuntimeException(e);}
        }
        String engineId = prefs.get("engineId", null);
        boolean light = prefs.getBoolean("light", false);

        var config = new Config(providerUuid, engineId, EngineConf.load(), Client.load(clientPrefs()), light);

        return config;
    }

    static void storeEngineId(String id) {
        var prefs = prefs();
        prefs.put("engineId", id);
        try { prefs.flush(); } catch (Exception e) {throw new RuntimeException(e);}
    }

    static String prefsName() {
        return System.getProperty("prefs", Config.class.getModule().getName());
    }

    static Preferences prefs() {
        return Preferences.userRoot().node(prefsName());
    }

    static Preferences clientPrefs() {
        return Preferences.userRoot().node(prefsName() + "client");
    }

    sealed interface EngineConf permits EngineConf.Some, EngineConf.None {
        sealed interface Some extends EngineConf permits EngineConf.Custom, EngineConf.BuiltIn {
            Parameters parameters();
            Path engineExecutable();
        }
        default String name() { return "External Engine"; }
        default boolean builtInAvailable() { return false; }


        record None() implements EngineConf {}

        record Custom(
                String name,
                Path engineExecutable,
                Parameters parameters,
                List<String> variants,
                boolean builtInAvailable
                ) implements Some {}

        record BuiltIn(
                String name,
                Path engineExecutable,
                Parameters parameters,
                List<String> variants
                ) implements Some {
            public boolean builtInAvailable() { return true; }
        }

        static Parameters loadParameters() {
            var prefs = prefs();
            var options = Arrays.stream(prefs.get("options", "").split(","))
                .filter(s -> s.contains("="))
                .map(s -> s.split("="))
                .map(arr -> new Engine.UciOption(arr[0], arr[1]))
                .toList();

            return new Parameters(
                    prefs.getInt("maxHash", 512),
                    prefs.getInt("maxThreads", Runtime.getRuntime().availableProcessors()),
                    prefs.getInt("defaultDepth", 25),
                    prefs.getInt("keepAlive", 300),
                    options
                    );
        }

        static void store(EngineConf engineConf) {
            var prefs = prefs();
            prefs.put("name", engineConf.name());
            switch(engineConf) {
                case Custom custom -> {
                    prefs.put("customExecutable", custom.engineExecutable.toString());
                    storeParameters(custom.parameters());
                }
                case BuiltIn builtIn -> {
                    prefs.remove("customExecutable");
                    storeParameters(builtIn.parameters());
                }
                case None n -> {}
            }
            try { prefs.flush(); } catch (Exception e) {}
        }

        private static void storeParameters(Parameters parameters) {
            var prefs = prefs();
            prefs.putInt("maxHash", parameters.maxHash());
            prefs.putInt("maxThreads", parameters.maxThreads());
            prefs.putInt("defaultDepth", parameters.defaultDepth());
            prefs.putInt("keepAlive", parameters.keepAlive());
            prefs.put("options", parameters.options().stream()
                    .map(option -> option.name() + "=" + option.value())
                    .collect(Collectors.joining(",")));
        }

        static EngineConf load() {
            var prefs = prefs();
            String name = prefs.get("name", "External Engine");
            var parameters = EngineConf.loadParameters();

            InputStream builtInInputStream = null;

            // Check if built-in exists
            if (builtInExecutable == null) {
                try {
                    builtInInputStream = Config.class.getModule().getResourceAsStream("stockfish.zip");
                } catch (IOException ioe) {}
            }

            // Check custom
            String customExecutable = prefs.get("customExecutable", null);
            if (customExecutable != null) {
                var engineExecutable = Path.of(customExecutable);
                if (engineExecutable.toFile().canExecute()) {
                    var engine = Engine.init(engineExecutable.toString(), parameters, logger);
                    var supportedVariants = engine.supportedVariants();
                    engine.terminate();
                    return new Custom(name, engineExecutable, parameters, supportedVariants, builtInExecutable != null || builtInInputStream != null);
                }
            }

            if (builtInExecutable == null) {
                try {
                    if (builtInInputStream != null) {
                        Path tempZip = Files.createTempFile("ee-stockfish-", ".zip");
                        Path engineExecutable = Files.createTempFile("ee-stockfish-", ".bin");
                        var os = java.nio.file.Files.newOutputStream(tempZip);
                        builtInInputStream.transferTo(os);
                        tempZip.toFile().deleteOnExit();
                        engineExecutable.toFile().deleteOnExit();
                        try (var fs = FileSystems.newFileSystem(tempZip)) {
                            var rootIter = fs.getRootDirectories().iterator();
                            var path = rootIter.next();
                            try (var stream = Files.walk(path, 2)) {
                                var list = stream.filter(p -> p.getFileName() != null)
                                    .filter(p -> p.getFileName().toString().startsWith("stockfish"))
                                    .filter(p -> ! p.getFileName().toString().endsWith("_src"))
                                    .toList();
                                if (! list.isEmpty()) {
                                    Files.copy(list.get(list.size()-1), engineExecutable, StandardCopyOption.REPLACE_EXISTING);
                                    engineExecutable.toFile().setExecutable(true);
                                    builtInExecutable = engineExecutable;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    builtInExecutable = null;
                    logger.log(Level.ERROR, "Failed to extract built-in engine", e);
                }
            }

            if (builtInExecutable != null) {
                var engine = Engine.init(builtInExecutable.toString(), parameters, logger);
                var supportedVariants = engine.supportedVariants();
                engine.terminate();
                return new BuiltIn(name, builtInExecutable, parameters, supportedVariants);
            }

            // Check PATH -> custom
            String PATH = System.getenv("PATH");
            if (PATH != null) {
                var pathList = Arrays.stream(PATH.split(File.pathSeparator))
                    .map(Path::of)
                    .toList();

                List<Path> foundStockfishes = new ArrayList<>();
                for (var path : pathList) {
                    try (var stream = Files.list(path)) {
                        foundStockfishes.addAll(
                                stream.filter(p -> p.getFileName().toString().toLowerCase().startsWith("stockfish")
                                    && p.toFile().canExecute())
                                .toList());
                    } catch (IOException ioe) {}
                }

                foundStockfishes = foundStockfishes.stream().distinct().toList();

                for (Path engineExecutable : foundStockfishes) {
                    try {
                        var engine = Engine.init(engineExecutable.toString(), parameters, logger);
                        var supportedVariants = engine.supportedVariants();
                        engine.terminate();
                        var custom = new Custom(name, engineExecutable, parameters, supportedVariants, builtInExecutable != null || builtInInputStream != null);
                        store(custom);
                        return custom;
                    } catch (Exception e) { }
                }
            }

            return new None();
        }
    }
}
