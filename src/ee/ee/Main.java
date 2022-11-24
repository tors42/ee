package ee;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import chariot.api.ExternalEngineAuth;
import chariot.model.*;
import ee.Engine.Parameters;

class Main implements Runnable {
    Path engineCmd;
    String name;
    Parameters parameters;
    Engine engine;
    ExternalEngineAuth api;
    String secret;
    String engineId;

    static System.Logger logging = System.getLogger("Main");

    Main(Path engineCmd, String name, Parameters parameters, ExternalEngineAuth api, String secret, String engineId) {
        this.engineCmd = engineCmd;
        this.name = name;
        this.parameters = parameters;
        this.api = api;
        this.secret = secret;
        this.engineId = engineId;
    }

    <T> T ok(T res) {
        if (res instanceof Fail<?> f) {
            logging.log(Level.ERROR, () -> "Response: %s".formatted(f));
            throw new RuntimeException(f.toString());
        }
        return res;
    }

    public void register_engine(ExternalEngineAuth api, Engine engine, String secret) {
        var res = ok(api.list());

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
                parameters.maxThreads(),
                parameters.maxHash(),
                parameters.defaultDepth(),
                supportedVariants,
                secret);

        if (engineId == null) {
            res.stream().filter(e -> name.equals(e.name()))
                .map(ExternalEngineInfo::id)
                .findAny()
                .ifPresentOrElse(id -> {
                    logging.log(Level.INFO, () -> "Updating engine %s".formatted(id));
                    ok(api.update(id, registration));
                },
                () -> {
                    logging.log(Level.INFO, () -> "Registering new engine");
                    ok(api.create(registration));
                });
        } else {
            res.stream().filter(e -> engineId.equals(e.id()))
                .findAny()
                .ifPresentOrElse(eei -> {
                    logging.log(Level.INFO, () -> "Updating engine %s".formatted(eei.id()));
                    ok(api.update(eei.id(), registration));
                },
                () -> {
                    logging.log(Level.INFO, () -> "Registering new engine");
                    ok(api.create(registration));
                });
        }
    }

    public void run() {
        var engine = Engine.init(engineCmd.toString(), parameters, logging);
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        register_engine(api, engine, secret);

        while(true)
            switch(ok(api.acquire(secret))) {
                case Fail<ExternalEngineRequest> fail -> {
                    logging.log(Level.ERROR, () -> "Error while trying to acquire work: %s".formatted(fail));
                    try {Thread.sleep(5000);}catch(InterruptedException ie) {}
                }
                case None<ExternalEngineRequest> none -> {
                    if (engine.alive && engine.idle_time().toSeconds() > parameters.keepAlive()) {
                        logging.log(Level.INFO, "Terminating idle engine");
                        engine.terminate();
                    }
                }
                case Entry<ExternalEngineRequest> one -> {
                    engine.stop();

                    if (! engine.alive) engine = Engine.init(engineCmd.toString(), parameters, logging);

                    var job_started = new Semaphore(0);
                    final Engine engineRef = engine;
                    executor.submit(() -> {
                        logging.log(Level.INFO, () -> "Handling job %s".formatted(one.entry().id()));
                        try {
                            var inputStream = engineRef.analyse(one.entry().work(), job_started);
                            ok(api.answer(one.entry().id(), inputStream));
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
}
