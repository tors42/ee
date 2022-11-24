# ee-cli

A Java port of `example-provider.py` found in [lichess-org/external-engine](https://github.com/lichess-org/external-engine)

Builds with Java 19

    $ java build/Build.java


1. Create a token at https://lichess.org/account/oauth/token/create?scopes[]=engine:read&scopes[]=engine:write

2. Run:

    $ LICHESS_API_TOKEN=lip_*** out/runtime/bin/ee-cli --engine /usr/bin/stockfish

3. Visit https://lichess.org/analysis

4. Open the hamburger menu and select the *Alpha 2* provider



# Options

    $ out/bin/ee-cli --help
    Usage: ee-cli [-h] [--broker=<brokerUrl>] [--default-depth=<defaultDepth>] [--engine=<enginecmd>]
                  [--keep-alive=<keepAlive>] [--lichess=<lichessUrl>] [--max-hash=<maxHash>]
                  [--max-threads=<maxThreads>] [--name=<name>] [--provider-secret=<providerSecret>] --token=<token>
                  [--setoption=string string]...
      -h, --help                 display a help message
          --token=<token>        API token with engine:read and engine:write scopes. May be set by environment
                                   variable LICHESS_API_TOKEN.
          --provider-secret=<providerSecret>
                                 Optional fixed provider secret. May be set by environment variable PROVIDER_SECRET.
          --name=<name>          Engine name to register
                                   Default: Alpha 2
          --engine=<enginecmd>   Shell command to launch UCI engine
                                   Default: /usr/bin/stockfish
          --default-depth=<defaultDepth>
                                 Default engine seek depth
                                   Default: 25
          --max-threads=<maxThreads>
                                 Maximum number of available threads
          --max-hash=<maxHash>   Maximum hash table size in MiB
                                   Default: 512
          --setoption=string string
                                 Set a custom UCI option
          --keep-alive=<keepAlive>
                                 Number of seconds to keep an idle/unused engine process around
                                   Default: 300
          --lichess=<lichessUrl> Lichess endpoint
                                   Default: https://lichess.org
          --broker=<brokerUrl>   Broker endpoint
                                   Default: https://engine.lichess.ovh

## Libraries

- [Chariot](https://github.com/tors42/chariot) for communication with Lichess.
- [Picocli](https://github.com/remkop/picocli) for CLI options.

