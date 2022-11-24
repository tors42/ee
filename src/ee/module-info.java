module ee {
    requires chariot;
    requires info.picocli;
    requires com.formdev.flatlaf;

    requires java.desktop;
    requires jdk.zipfs;

    opens ee to info.picocli;
}
