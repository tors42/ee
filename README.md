# ee-cli

A Java port of `example-provider.py` found in [lichess-org/external-engine](https://github.com/lichess-org/external-engine)

## Download

TODO - _Pre-built archives can be found at [releases](https://github.com/tors42/ee/releases)._

## Run

Unpack the archive and execute `ee-cli` from the bin-directory.

    $ unzip ee-cli-0.0.1-linux-x64.zip
    ...

    $ ./ee-cli-0.0.1/bin/ee-cli --help
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

## Build

Builds with Maven and Java 19

    $ mvn clean verify

The built archive can be found at `target/ee-0.0.1-SNAPSHOT-build.zip`.  
An unpacked version can be found at `target/maven-jlink/classifiers/build/bin/ee-cli`.  

## Libraries

- [Chariot](https://github.com/tors42/chariot) for communication with Lichess.
- [Picocli](https://github.com/remkop/picocli) for CLI options.

