# ee

An External Engine provider for Lichess Analysis

## Download

Pre-built archives can be found at [releases](https://github.com/tors42/ee/releases)  
If you don't have a chess engine on your computer, you can download an archive with
the suffix `-embed.zip`. They are slightly bigger and contain the Stockfish engine.  
If you already have a chess engine on your computer, you can download the
archive without the `-embed.zip` suffix, and configure the application to use
your existing binary.

The archive contains a directory `ee-0.0.1` which you can unpack on your computer.

To start the application, run the `ee` or `ee.bat` file in the `ee-0.0.1/bin` directory.

## Run

1. Click the `Login` button to authorize with Lichess.  
![1-login](https://user-images.githubusercontent.com/4084220/204158510-b455402a-7fe1-4873-b993-8ae28e608cce.png)
![2-authorize](https://user-images.githubusercontent.com/4084220/204158509-8ba09c01-e0f5-47e3-b97c-3dc0846e31f0.png)

2. Optionally click the `Configure...` button in the `Engine` tab to update engine settings.  

3. Open web page [Lichess Analysis](https://lichess.org/analysis) and click the hamburger menu in the lower right, to find the `Engine Manager` selection and change from `Lichess` to `External Engine`. _Hint, you can change the name `External Engine` to something more personal in the application in the `Engine` tab_  
![3-select](https://user-images.githubusercontent.com/4084220/204158508-fb588f74-a6c6-42c0-81f0-8ff99d852715.png)

Analysing moves will now use the external engine instead of the Lichess web version.
![4-analyze](https://user-images.githubusercontent.com/4084220/204158505-da191ece-d1d7-4f57-90c9-d668cff19599.png)

## Libraries

- [Stockfish](https://github.com/official-stockfish/Stockfish) for Stockfish engine.
- [FlatLaf](https://github.com/JFormDesigner/FlatLaf) for GUI Look&Feel.
- [Picocli](https://github.com/remkop/picocli) for CLI options.
- [Chariot](https://github.com/tors42/chariot) for communication with Lichess.


## Develop

Build requires Java 19+

    $ java build/Build.java

