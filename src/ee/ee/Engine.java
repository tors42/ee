package ee;

import java.io.*;
import java.lang.System.Logger.Level;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import chariot.model.ExternalEngineWork;

public class Engine {

    record CmdAndParams(String command, String params) {}
    public record UciOption(String name, String value) {}
    public record Parameters(int maxHash, int maxThreads, int defaultDepth, int keepAlive, List<UciOption> options) {}

    String session_id;
    int threads;
    int hash;
    int multi_pv;
    int default_depth;
    String uci_variant;
    List<String> supportedVariants = new ArrayList<>();
    boolean alive;
    Instant last_used;
    Process process;
    Lock lock;
    BlockingQueue<CmdAndParams> engineOutput = new ArrayBlockingQueue<>(4096);
    System.Logger logger;

    private Engine() {}
    public static Engine init(String cmd, Parameters parameters, System.Logger logger) {
        try {
            var engine = new Engine();
            engine._init(cmd, parameters, logger);
            return engine;
        } catch(IOException ioe) { throw new RuntimeException(ioe); }
    }

    private void _init(String cmd, Parameters parameters, System.Logger logger) throws IOException {
        session_id = "";
        threads = parameters.maxThreads;
        hash = parameters.maxHash;
        default_depth = parameters.defaultDepth;
        multi_pv = 1;
        uci_variant = "chess";
        alive = true;
        last_used = Instant.now();
        lock = new ReentrantLock();
        this.logger = logger;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Thread.ofPlatform().start(() -> {
                try {
                    while (true) {
                        String line = process.inputReader().readLine();
                        if (line == null) {
                            terminate();
                            return;
                        }

                        line = line.stripTrailing();
                        if (line.isEmpty()) continue;

                        String[] arr = line.split(" ", 2);

                        var cmdAndParams = new CmdAndParams(arr[0], arr.length == 2 ? arr[1] : "");
                        if (! "info".equals(arr[0]))
                            logger.log(Level.DEBUG, () -> "%d >> %s".formatted(process.pid(), cmdAndParams));

                        if (! engineOutput.offer(cmdAndParams)) {
                            logger.log(Level.ERROR, "queue full!");
                            terminate();
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.ERROR, "Failed to start engine", e);
                }
            });
        } catch (Exception ioe) {
            logger.log(Level.ERROR, "Failed to read engine output", ioe);
            throw new RuntimeException(ioe);
        }

        uci();
        setoption("UCI_AnalyseMode", "true");
        setoption("UCI_Chess960", "true");
        setoption("Threads", String.valueOf(threads));
        setoption("Hash", String.valueOf(hash));
        setoption("multiPv", String.valueOf(multi_pv));

        for (var option : parameters.options)
            setoption(option.name(), option.value());
    }

    public List<String> supportedVariants() {
        return List.copyOf(supportedVariants);
    }

    Duration idle_time() {
        return Duration.between(last_used, Instant.now());
    }

    public void terminate() {
        logger.log(Level.DEBUG, "Terminating");
        process.destroy();
        alive = false;
    }

    void send(String command) throws IOException {
        logger.log(Level.DEBUG, () -> "%d << %s".formatted(process.pid(), command));
        process.outputWriter().write(command);
        process.outputWriter().newLine();
        process.outputWriter().flush();
    }

    CmdAndParams recv() throws IOException {
        try {
            return engineOutput.take();
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
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
            logger.log(Level.INFO, () -> "Supported variants: %s".formatted(supportedVariants));
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

        String position = "position fen %s moves %s".formatted(work.initialFen(), String.join(" ", work.moves()));
        logger.log(Level.DEBUG, "Analyzing position [%s]".formatted(position));
        send(position);

        if (work.infinite()) {
            send("go infinite");
        } else {
            send("go depth %d".formatted(default_depth));
        }

        job_started.release();

        var pipedOutputStream = new PipedOutputStream();
        var pipedInputStream = new PipedInputStream(pipedOutputStream, 8192);

        Thread.ofPlatform().name("engine-to-request-body").start(() -> {
            try {
                boolean responding = true;
                logger.log(Level.INFO, () -> "[%s] Analyzing [%s]".formatted(session_id, position));
                while(responding) {
                    var cmd = recv();
                    logger.log(Level.TRACE, () -> "[%s] - %s %s".formatted(session_id, cmd.command(), cmd.params()));

                    responding = switch(cmd) {
                        case CmdAndParams(var command, var params) when command.equals("bestmove") -> false;
                        case CmdAndParams(var command, var params) when command.equals("info") -> {
                            if (params.contains("score")) {
                                String line = command + " " + params + "\n";
                                logger.log(Level.DEBUG, () -> "[%s] Writing to request body: %s".formatted(session_id, line));
                                pipedOutputStream.write(line.getBytes());
                            }
                            yield true;
                        }
                        default -> true;
                    };
                    pipedOutputStream.flush();
                    last_used = Instant.now();
                }
                logger.log(Level.INFO, () -> "[%s] Finished analyzing".formatted(session_id));
                pipedOutputStream.close();
            } catch(IOException ioe) {
                logger.log(Level.ERROR, ioe);
            } finally {
                stop();
            }
        });
        return pipedInputStream;
    }

    void stop() {
        if (alive) {
            lock.lock();
            try {
                send("stop");
            } catch(IOException ioe) {
                logger.log(Level.ERROR, "Failed to stop", ioe);
            } finally {
                lock.unlock();
            }
        }
    }
}

