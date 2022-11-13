package ee;

import java.io.*;
import java.lang.System.Logger.Level;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.*;
import java.util.concurrent.*;

import chariot.api.ExternalEngineAuth;
import chariot.model.*;

import picocli.CommandLine;
import picocli.CommandLine.Option;

@CommandLine.Command(name = "ee-cli", sortOptions = false, usageHelpAutoWidth = true, showDefaultValues = true)
class CLI implements Runnable {

    static System.Logger logging = System.getLogger("Main");

    <T> T ok(T res) {
        if (res instanceof Fail<?> f) {
            logging.log(Level.ERROR, () -> "Response: %s".formatted(f));
            throw new RuntimeException(f.toString());
        }
        return res;
    }

    String register_engine(ExternalEngineAuth api, Engine engine) {
        var res = ok(api.list());

        String secret = providerSecret != null ? providerSecret : UUID.randomUUID().toString();

        var variants = List.of(
                "chess",
                "antichess",
                "atomic",
                "crazyhouse",
                "horde",
                "kingofthehill",
                "racingkings",
                "3check"
                );

        var supportedVariants = variants.stream().filter(engine.supportedVariants::contains).toList();
        if (supportedVariants.isEmpty()) supportedVariants = List.of("chess");

        var registration = new ExternalEngineRegistration(
                name,
                maxThreads,
                maxHash,
                defaultDepth,
                supportedVariants,
                secret);

        res.stream().filter(e -> name.equals(e.name()))
            .map(ExternalEngineInfo::id)
            .findAny()
            .ifPresentOrElse(
                    id -> {
                        logging.log(Level.INFO, () -> "Updating engine %s".formatted(id));
                        ok(api.update(id, registration));
                    },
                    () -> {
                        logging.log(Level.INFO, () -> "Registering new engine");
                        ok(api.create(registration));
                    });
        return secret;
    }

    public void run() {
        if (maxThreads == null) {
            maxThreads = Runtime.getRuntime().availableProcessors();
        }

        var executor = Executors.newSingleThreadExecutor();
        var engine = Engine.init(enginecmd, this);

        var client = chariot.Client.auth(c -> c
                //.logging(l -> l.response().all().request().all())
                .api(lichessUrl)
                .servers(s -> s.engine(brokerUrl))
                .auth(token));

        String secret = register_engine(client.externalEngine(), engine);

        while(true)
            switch(ok(client.externalEngine().acquire(secret))) {
                case Fail<ExternalEngineRequest> fail -> {
                    logging.log(Level.ERROR, () -> "Error while trying to acquire work: %s".formatted(fail));
                    try {Thread.sleep(5000);}catch(InterruptedException ie) {}
                }
                case None<ExternalEngineRequest> none -> {
                    if (engine.alive && engine.idle_time().toSeconds() > keepAlive) {
                        logging.log(Level.INFO, "Terminating idle engine");
                        engine.terminate();
                    }
                }
                case Entry<ExternalEngineRequest> one -> {
                    engine.stop();

                    if (! engine.alive) engine = Engine.init(enginecmd, this);

                    var job_started = new Semaphore(0);
                    final Engine engineRef = engine;
                    executor.submit(() -> {
                        logging.log(Level.INFO, () -> "Handling job %s".formatted(one.entry().id()));
                        try {
                            var inputStream = engineRef.analyse(one.entry().work(), job_started);
                            ok(client.externalEngine().answer(one.entry().id(), inputStream));
                        } catch (IOException ioe) {
                            logging.log(Level.ERROR, "Error while trying to answer", ioe);
                        } finally {
                            job_started.release();
                        }
                    });
                    try {
                        job_started.acquire();
                    } catch(InterruptedException ie) {
                        logging.log(Level.ERROR, "Interrupted", ie);
                        throw new RuntimeException(ie);
                    }
                }
            }
    }

    record CmdAndParams(String command, String params) {}
    record UciOption(String name, String value) {}

    static class Engine {
        String session_id;
        int threads;
        int hash;
        int multi_pv;
        int default_depth;
        String uci_variant;
        Set<String> supportedVariants;
        boolean alive;
        Instant last_used;
        Process process;
        Lock lock;

        private Engine() {}
        static Engine init(String cmd, CLI cli) {
            try {
                var engine = new Engine();
                engine._init(cmd, cli);
                return engine;
            } catch(IOException ioe) { throw new RuntimeException(ioe); }
        }

        private void _init(String cmd, CLI cli) throws IOException {
            session_id = "";
            threads = cli.maxThreads;
            hash = cli.maxHash;
            default_depth = cli.defaultDepth;
            multi_pv = 1;
            uci_variant = "chess";
            supportedVariants = HashSet.newHashSet(10);
            alive = true;
            last_used = Instant.now();
            lock = new ReentrantLock();
            try {
                process = new ProcessBuilder(cmd).start();
            } catch (IOException ioe) {
                logging.log(Level.ERROR, "Failed to start stockfish", ioe);
                throw new RuntimeException(ioe);
            }

            uci();
            setoption("UCI_AnalyseMode", "true");
            setoption("UCI_Chess960", "true");
            for (var option : cli.options)
                setoption(option.name(), option.value());
        }

        Duration idle_time() {
            return Duration.between(last_used, Instant.now());
        }

        void terminate() {
            process.destroy();
            alive = false;
        }

        void send(String command) throws IOException {
            logging.log(Level.DEBUG, () -> "%d << %s".formatted(process.pid(), command));
            process.outputWriter().write(command + "\n");
            process.outputWriter().flush();
        }

        CmdAndParams recv() throws IOException {
            while(true) {
                String line = process.inputReader().readLine();
                if (line == null) {
                    alive = false;
                    throw new EOFException();
                }

                line = line.stripTrailing();
                if (line.isEmpty()) continue;

                String[] arr = line.split(" ", 2);

                var cmdAndParams = new CmdAndParams(arr[0], arr.length == 2 ? arr[1] : "");
                if (! "info".equals(arr[0]))
                    logging.log(Level.DEBUG, () -> "%d >> %s".formatted(process.pid(), cmdAndParams));

                return cmdAndParams;
            }
        }

        void uci() throws IOException {
            send("uci");
            boolean done = false;
            while(!done)
                switch (recv()) {
                    case CmdAndParams(var command, var params) when command.equals("option") -> {
                        String name = "";
                        Iterator<String> iter = Arrays.stream(params.split(" ")).iterator();
                        while (iter.hasNext())
                            switch(iter.next()) {
                                case "name" -> name = iter.next();
                                case "var" -> { if (name.equals("UCI_Variant")) supportedVariants.add(iter.next()); }
                                default -> {}
                            }
                    }
                    case CmdAndParams(var command, var __) when command.equals("uciok") -> done = true;
                    default -> {}
                }

            if (! supportedVariants.isEmpty())
                logging.log(Level.INFO, () -> "Supported variants: %s".formatted(supportedVariants));
        }

        void isready() throws IOException {
            send("isready");
            while(switch(recv()) {
                case CmdAndParams(var command, var __) when command.equals("readyok") -> false;
                default -> true;
            }){}
        }

        void setoption(String name, String value) throws IOException {
            send("setoption name %s value %s".formatted(name, value));
        }

        InputStream analyse(ExternalEngineWork work, Semaphore job_started) throws IOException {

            if (! session_id.equals(work.sessionId())) {
                session_id = work.sessionId();
                send("ucinewgame");
                isready();
            }

            boolean options_changed = false;
            if (threads != work.threads()) {
                setoption("Threads", String.valueOf(work.threads()));
                threads = work.threads();
                options_changed = true;
            }
            if (hash != work.hash()) {
                setoption("Hash", String.valueOf(work.hash()));
                hash = work.hash();
                options_changed = true;
            }
            if (multi_pv != work.multiPv()) {
                setoption("multiPv", String.valueOf(work.multiPv()));
                multi_pv = work.multiPv();
                options_changed = true;
            }
            if (! uci_variant.equals(work.variant())) {
                setoption("UCI_Variant", work.variant());
                uci_variant = work.variant();
                options_changed = true;
            }

            if (options_changed) isready();

            send("position fen %s moves %s".formatted(work.initialFen(), String.join(" ", work.moves())));

            if (work.infinite()) {
                send("go infinite");
            } else {
                send("go depth %d".formatted(default_depth));
            }

            job_started.release();

            var pipedOutputStream = new PipedOutputStream();
            var pipedInputStream = new PipedInputStream(pipedOutputStream, 128_000);

            Thread.ofPlatform().name("engine-to-request-body").start(() -> {
                try {
                    while(switch(recv()) {
                        case CmdAndParams(var command, var __) when command.equals("bestmove") -> false;
                        case CmdAndParams(var command, var params) when command.equals("info") -> {
                            if (params.contains("score")) {
                                pipedOutputStream.write((command + " " + params + "\n").getBytes());
                                pipedOutputStream.flush();
                            }
                            yield true;
                        }
                        default -> true;
                    }) {}

                    pipedOutputStream.close();

                } catch(IOException ioe) {
                    logging.log(Level.ERROR, ioe);
                } finally {
                    stop();
                }
            });
            last_used = Instant.now();
            return pipedInputStream;
        }

        void stop() {
            if (alive) {
                lock.lock();
                try {
                    send("stop");
                } catch(IOException ioe) {
                    logging.log(Level.ERROR, "Failed to stop", ioe);
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    @Option(names = { "-h", "--help" }, usageHelp = true, description = "display a help message") boolean helpRequested = false;
    @Option(names = {"--token"},
            showDefaultValue = CommandLine.Help.Visibility.NEVER,
            defaultValue = "${env:LICHESS_API_TOKEN}",
            description="API token with engine:read and engine:write scopes. May be set by environment variable LICHESS_API_TOKEN.",
            required = true)
    String token;

    @Option(names = {"--provider-secret"},
            showDefaultValue = CommandLine.Help.Visibility.NEVER,
            defaultValue = "${env:PROVIDER_SECRET}",
            description="Optional fixed provider secret. May be set by environment variable PROVIDER_SECRET.")
    String providerSecret;

    @Option(names = {"--name"}, defaultValue = "Alpha 2", description = "Engine name to register") String name;
    @Option(names = {"--engine"}, defaultValue = "/usr/bin/stockfish", description="Shell command to launch UCI engine") String enginecmd;
    @Option(names = {"--default-depth"}, defaultValue = "25", description="Default engine seek depth") int defaultDepth;
    @Option(names = {"--max-threads"}, description="Maximum number of available threads") Integer maxThreads;
    @Option(names = {"--max-hash"}, defaultValue = "512", description="Maximum hash table size in MiB") int maxHash;
    @Option(names = {"--setoption"}, arity = "2", description="Set a custom UCI option", parameterConsumer = UCIOptionConsumer.class, paramLabel = "string") List<UciOption> options = new ArrayList<>();
    @Option(names = {"--keep-alive"}, defaultValue = "300", description="Number of seconds to keep an idle/unused engine process around") int keepAlive;
    @Option(names = {"--lichess"}, defaultValue = "https://lichess.org", description="Lichess endpoint", required = true) String lichessUrl;
    @Option(names = {"--broker"}, defaultValue = "https://engine.lichess.ovh", description="Broker endpoint", required = true) String brokerUrl;

    static class UCIOptionConsumer implements CommandLine.IParameterConsumer {
        public UCIOptionConsumer() {}
        public void consumeParameters(Stack<String> args, CommandLine.Model.ArgSpec argSpec, CommandLine.Model.CommandSpec commandSpec) {
            List<UciOption> list = argSpec.getValue();
            list.add(new UciOption(args.pop(), args.pop()));
        }
    }

    public static void main(String[] args) throws Exception {
        int exitCode = new picocli.CommandLine(new CLI()).execute(args);
        System.exit(exitCode);
    }
}
