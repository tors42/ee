package ee;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.Path;

import javax.swing.*;

import com.formdev.flatlaf.*;

import chariot.*;
import ee.Config.EngineConf;
import ee.Engine.*;

class GUI {

    final JFrame frame;
    final Config config;

    GUI(Config config) {
        this.config = config;
        if (config.light()) {
            FlatLightLaf.setup();
        } else {
            FlatDarkLaf.setup();
        }
        frame = new JFrame("External Engine");
        var mouseListener = new MouseAdapter() {
            Point pressed = null;
            public void mouseReleased(MouseEvent e) {
                pressed = null;
            }

            public void mousePressed(MouseEvent e) {
                pressed = e.getPoint();
            }

            public void mouseDragged(MouseEvent e) {
                var coords = e.getLocationOnScreen();
                frame.setLocation(coords.x - pressed.x, coords.y - pressed.y);
            }
        };
        frame.addMouseListener(mouseListener);
        frame.addMouseMotionListener(mouseListener);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setUndecorated(true);
        frame.setLocationByPlatform(true);
    }

    void run() {
        var tabbed = new JTabbedPane();
        tabbed.addTab("Engine", enginePanel());
        tabbed.addTab("Lichess", lichessPanel());
        tabbed.addTab("About", aboutPanel());
        tabbed.setSelectedIndex(config.client() instanceof ClientAuth auth ? 0 : 1);
        tabbed.setAlignmentX(Component.LEFT_ALIGNMENT);

        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        var title = styleClass(new JLabel(frame.getTitle()), "h00");
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(new JSeparator(JSeparator.HORIZONTAL));
        panel.add(tabbed);

        var bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var exit = new JButton("Exit");
        exit.addActionListener(__ -> frame.dispose() );
        bottomPanel.add(exit);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(panel, BorderLayout.CENTER);
        frame.getContentPane().add(bottomPanel, BorderLayout.SOUTH);

        var darker = panel.getBackground().darker().darker();
        frame.getRootPane().setBorder(BorderFactory.createLineBorder(darker, 4));

        frame.pack();

        SwingUtilities.invokeLater(() -> {
            frame.setVisible(true);
        });

        if (config.client()     instanceof ClientAuth chariot &&
            config.engineConf() instanceof EngineConf.Some conf) {

            Thread.ofPlatform().daemon().start(
                    new Main(
                        conf.engineExecutable(),
                        conf.name(),
                        conf.parameters(),
                        chariot.externalEngine(),
                        config.providerUuid(),
                        config.engineId()
                        ));
        }
    }

    JPanel enginePanel() {
        var panel = new JPanel();
        List<LabeledField<? extends Component>> pairs = new ArrayList<>();
        pairs.add(LabeledField.ofTextField("Name", config.engineConf().name()));
        pairs.addAll(switch(config.engineConf()) {
            case EngineConf.Custom custom -> {
                var pair = LabeledField.ofTextField("Engine", custom.engineExecutable().getFileName().toString());
                pair.field().setToolTipText(custom.engineExecutable().toString());
                yield List.of(pair);
            }
            case EngineConf.BuiltIn builtIn -> List.of(LabeledField.ofTextField("Engine", "Stockfish 15 [Built-In]"));
            case EngineConf.None n -> List.of();
        });

        var parameters = switch(config.engineConf()) {
            case EngineConf.Some p -> p.parameters();
            case EngineConf.None n -> EngineConf.loadParameters();
        };

        pairs.addAll(List.of(
                    LabeledField.ofTextField("Max hash (MB)", String.valueOf(parameters.maxHash())),
                    LabeledField.ofTextField("Max threads", String.valueOf(parameters.maxThreads())),
                    LabeledField.ofTextField("Default depth", String.valueOf(parameters.defaultDepth()))
                    ));

        var configure = new JButton("Configure...");
        configure.setFocusPainted(false);
        var buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(configure);

        configure.addActionListener(__ -> {
            var engineInput = enginePanelEdit();
            boolean done = false;
            while (!done) {
                int option = JOptionPane.showConfirmDialog(frame, engineInput.panel(), "Configure Engine", JOptionPane.OK_CANCEL_OPTION);
                if (option == JOptionPane.OK_OPTION) {
                    var engineConf = engineInput.engineConf().get();
                    if (engineConf != null) {
                        done = true;
                        EngineConf.store(engineConf);
                        SwingUtilities.invokeLater(() -> {
                            frame.setVisible(false);
                            frame.dispose();
                            Thread.ofPlatform().start(() -> GUI.init().run());
                        });
                    }
                } else {
                    done = true;
                }
            }
        });

        layoutComponents(panel, pairs, buttonPanel);
        return panel;
    }

    JPanel lichessPanel() {
        var lichessPanel = new JPanel();
        lichessPanel.setLayout(new BoxLayout(lichessPanel, BoxLayout.Y_AXIS));
        var buttonPanel = new JPanel();
        buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        var login = new JButton("Login");
        var logout = new JButton("Logout");

        login.setFocusPainted(false);
        logout.setFocusPainted(false);
        buttonPanel.add(login);
        buttonPanel.add(logout);

        if (config.client() instanceof ClientAuth auth) {
            logout.addActionListener(__ -> {
                SwingUtilities.invokeLater(() -> {
                    frame.setVisible(false);
                    auth.account().revokeToken();
                    auth.clearAuth(Config.clientPrefs());
                    frame.dispose();
                    Thread.ofPlatform().start(() -> GUI.init().run());
                });
            });
            login.setEnabled(false);
        } else {
            var info = """


                In order to run an external engine,
                you must connect with your Lichess Account.

                Click "Login" to continue.
                """;

            var oauthPanel = new JPanel(new BorderLayout());
            var textArea = styleClass(new JTextArea(info), "small");
            oauthPanel.add(textArea, BorderLayout.CENTER);
            textArea.setEditable(false);

            lichessPanel.add(oauthPanel);
            login.addActionListener(__ -> {
                var oauth = config.client().account().oauthPKCE(Client.Scope.engine_read, Client.Scope.engine_write);
                var url = oauth.url();
                SwingUtilities.invokeLater(() -> {
                    textArea.setText("""
                            Visit the following URL to authorize this application,

                            %s
                            """.formatted(url));
                    textArea.setLineWrap(true);
                    textArea.setEditable(false);
                    textArea.setRows(1);
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        var browse = new JButton("Open in Browser");
                        browse.addActionListener(e -> {
                            try { Desktop.getDesktop().browse(url); }
                            catch (IOException ioe) {throw new RuntimeException(ioe);}
                        });
                        oauthPanel.add(browse, BorderLayout.SOUTH);
                    }
                    frame.pack();
                });

                Thread.ofPlatform().start(() -> {
                    var clientAuth = Client.load(Config.clientPrefs(), a -> a.auth(oauth.token().get()));
                    clientAuth.store(Config.clientPrefs());
                    SwingUtilities.invokeLater(() -> {
                        frame.setVisible(false);
                        frame.dispose();
                        Thread.ofPlatform().start(() -> GUI.init().run());
                    });
                });
            });
            logout.setEnabled(false);
        }

        lichessPanel.add(buttonPanel);
        return lichessPanel;
    }

    JPanel aboutPanel() {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        var info = """

            Powered by Stockfish - https://stockfishchess.org

            %s - https://github.com/tors42/ee

            """.formatted(GUI.class.getModule().getDescriptor().toNameAndVersion());
        var textArea = styleClass(new JTextArea(info), "small");
        panel.add(textArea);
        textArea.setEditable(false);
        return panel;
    }

    record LabeledField<T>(JLabel label, T field) {
        static LabeledField<JTextField> ofTextField(String label, String field) {
            return ofTextField(label, field, false);
        }

        static LabeledField<JTextField> ofTextField(String label, String field, boolean editable) {
            var jlabel = styleClass(new JLabel(label + ":"), "h4");
            var jfield = styleClass(new JTextField(field), "semibold");
            jfield.setEditable(editable);
            jfield.setHorizontalAlignment(JTextField.TRAILING);
            jlabel.setLabelFor(jfield);
            return new LabeledField<>(jlabel, jfield);
        }

        static LabeledField<EngineSelection> ofFileChooser(Component parent, String label, boolean includeBuiltIn, boolean builtIn) {
            return ofFileChooser(parent, label, includeBuiltIn, builtIn, null);
        }

        static LabeledField<EngineSelection> ofFileChooser(Component parent, String label, boolean includeBuiltIn, boolean builtIn, Path path) {
            var jlabel = styleClass(new JLabel(label + ":"), "h4");
            var button = styleClass(new JButton(), "semibold");
            var fileChooser = new JFileChooser();
            if (path != null) {
                fileChooser.setCurrentDirectory(path.toFile());
                button.setText(path.getFileName().toString());
                button.setToolTipText(path.toString());
            } else {
                button.setText("Select Engine");
            }

            var checkBox = includeBuiltIn ? new JCheckBox("Built-In", builtIn) : null;
            var selectEngine = new EngineSelection(path, checkBox);

            if (checkBox != null) {
                if (builtIn) button.setEnabled(false);
                checkBox.addItemListener(e -> button.setEnabled(e.getStateChange() != 1));
            }

            button.addActionListener(__ -> {
                int returnVal = fileChooser.showOpenDialog(parent);
                if (returnVal == JFileChooser.APPROVE_OPTION) {
                    SwingUtilities.invokeLater(() -> {
                        var selectedPath = fileChooser.getSelectedFile().toPath();
                        selectEngine.setPath(selectedPath);
                        button.setText(selectedPath.getFileName().toString());
                        button.setToolTipText(selectedPath.toString());
                        var p = parent;
                        while (p != null) {
                            p = p.getParent();
                            if (p instanceof JDialog dialog) {
                                dialog.pack();
                                break;
                            }
                        }
                    });
                }
            });

            jlabel.setLabelFor(selectEngine);
            if (checkBox != null) selectEngine.add(checkBox);
            selectEngine.add(button);
            return new LabeledField<>(jlabel, selectEngine);
        }
    }

    static class EngineSelection extends JPanel {
        Path path;
        JCheckBox builtIn;

        EngineSelection(Path path, JCheckBox builtIn) { super(new FlowLayout()); this.path = path; this.builtIn = builtIn; }
        void setPath(Path path) { this.path = path; }
        Path getPath() { return path; }
        boolean builtIn() { return builtIn != null && builtIn.isSelected(); }
    }

    static <T extends JComponent> T styleClass(T component, String value) {
        component.putClientProperty("FlatLaf.styleClass", value);
        return component;
    }

    record EngineInput(JPanel panel, Supplier<EngineConf> engineConf) {}

    EngineInput enginePanelEdit() {
        var panel = new JPanel();
        boolean builtInAvailable = config.engineConf().builtInAvailable();

        var name = LabeledField.ofTextField("Name", config.engineConf().name(), true);
        var engine = switch(config.engineConf()) {
            case EngineConf.Custom custom -> LabeledField.ofFileChooser(panel, "Engine", builtInAvailable, false, custom.engineExecutable());
            case EngineConf.BuiltIn builtIn -> LabeledField.ofFileChooser(panel, "Engine", builtInAvailable, true, null);
            case EngineConf.None n -> LabeledField.ofFileChooser(panel, "Engine", builtInAvailable, false, null);
        };
        var parameters = switch(config.engineConf()) {
            case EngineConf.Some p -> p.parameters();
            case EngineConf.None n -> EngineConf.loadParameters();
        };
        var maxHash = LabeledField.ofTextField("Max hash (MB)", String.valueOf(parameters.maxHash()), true);
        var maxThreads = LabeledField.ofTextField("Max threads", String.valueOf(parameters.maxThreads()), true);
        var defaultDepth = LabeledField.ofTextField("Default depth", String.valueOf(parameters.defaultDepth()), true);
        var keepAlive = LabeledField.ofTextField("Idle Keep-Alive (s)", String.valueOf(parameters.keepAlive()), true);
        //todo, var options = LabeledField.ofTable("Extra UCI Options", parameters.options());

        List<LabeledField<? extends Component>> pairs = List.of(
                name,
                engine,
                maxHash,
                maxThreads,
                defaultDepth,
                keepAlive
                //todo, options
                );

        var buttonPanel = new JPanel(new FlowLayout());

        Supplier<EngineConf> supplier = () -> {
            List<UciOption> options = List.of();
            var params = new Parameters(
                    Integer.parseInt(maxHash.field().getText()),
                    Integer.parseInt(maxThreads.field().getText()),
                    Integer.parseInt(defaultDepth.field().getText()),
                    Integer.parseInt(keepAlive.field().getText()),
                    options
                    );

            List<String> variants = List.of();
            if (engine.field().builtIn()) {
                return new EngineConf.BuiltIn(
                        name.field().getText(),
                        Path.of(""),
                        params,
                        variants);
            } else {
                return new EngineConf.Custom(
                        name.field().getText(),
                        engine.field().getPath(),
                        params,
                        variants,
                        config.engineConf().builtInAvailable());
             }
        };

        layoutComponents(panel, pairs, buttonPanel);
        return new EngineInput(panel, supplier);
    }

    void layoutComponents(JPanel panel, List<LabeledField<? extends Component>> pairs, JPanel buttonPanel) {
        panel.setBackground(panel.getBackground().brighter());
        buttonPanel.setBackground(panel.getBackground());

        var layout = new GroupLayout(panel);
        panel.setLayout(layout);

        layout.setAutoCreateGaps(true);
        layout.setAutoCreateContainerGaps(true);

        var labelsGroup = layout.createParallelGroup();
        var fieldsGroup = layout.createParallelGroup();
        for (var pair : pairs) {
            labelsGroup.addComponent(pair.label());
            fieldsGroup.addComponent(pair.field());
        }

        fieldsGroup.addComponent(buttonPanel, GroupLayout.Alignment.TRAILING,
                GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE);

        layout.setHorizontalGroup(layout.createSequentialGroup()
                .addGroup(labelsGroup)
                .addGroup(fieldsGroup));

        var sequentialGroup = layout.createSequentialGroup();
        for (var pair : pairs) {
            sequentialGroup.addGroup(layout.createParallelGroup()
                    .addComponent(pair.label(), GroupLayout.Alignment.CENTER)
                    .addComponent(pair.field()));
        }

        sequentialGroup.addComponent(buttonPanel);
        layout.setVerticalGroup(sequentialGroup);
    }

    public static void main(String[] args) {
        var gui = GUI.init();
        gui.run();
    }

    static GUI init() {
        var config = Config.init();
        return new GUI(config);
    }
}
