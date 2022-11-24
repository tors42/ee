package ee;

import java.nio.file.Path;
import java.util.*;

import ee.Engine.Parameters;
import picocli.CommandLine;
import picocli.CommandLine.Option;

@CommandLine.Command(name = "ee-cli", sortOptions = false, usageHelpAutoWidth = true, showDefaultValues = true)
class CLI implements Runnable {

    public void run() {
        if (maxThreads == null) {
            maxThreads = Runtime.getRuntime().availableProcessors();
        }

        var parameters = new Parameters(maxHash, maxThreads, defaultDepth, keepAlive, options);

        var client = chariot.Client.auth(c -> c
                .api(lichessUrl)
                .servers(s -> s.engine(brokerUrl))
                .auth(token));

        String secret = providerSecret != null ? providerSecret : UUID.randomUUID().toString();

        var main = new Main(Path.of(enginecmd), name, parameters, client.externalEngine(), secret, null);
        main.run();
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
    @Option(names = {"--setoption"}, arity = "2", description="Set a custom UCI option", parameterConsumer = UCIOptionConsumer.class, paramLabel = "string") List<Engine.UciOption> options = new ArrayList<>();
    @Option(names = {"--keep-alive"}, defaultValue = "300", description="Number of seconds to keep an idle/unused engine process around") int keepAlive;
    @Option(names = {"--lichess"}, defaultValue = "https://lichess.org", description="Lichess endpoint", required = true) String lichessUrl;
    @Option(names = {"--broker"}, defaultValue = "https://engine.lichess.ovh", description="Broker endpoint", required = true) String brokerUrl;

    static class UCIOptionConsumer implements CommandLine.IParameterConsumer {
        public UCIOptionConsumer() {}
        public void consumeParameters(Stack<String> args, CommandLine.Model.ArgSpec argSpec, CommandLine.Model.CommandSpec commandSpec) {
            List<Engine.UciOption> list = argSpec.getValue();
            list.add(new Engine.UciOption(args.pop(), args.pop()));
        }
    }

    public static void main(String[] args) throws Exception {
        int exitCode = new picocli.CommandLine(new CLI()).execute(args);
        System.exit(exitCode);
    }
}
