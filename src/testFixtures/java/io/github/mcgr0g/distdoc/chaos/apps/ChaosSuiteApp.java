package io.github.mcgr0g.distdoc.chaos.apps;

public class ChaosSuiteApp {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Укажите режим: gen или load");
            System.exit(1);
        }
        if ("gen".equalsIgnoreCase(args[0])) {
            ChaosGeneratorApp.main(new String[0]);
        } else if ("load".equalsIgnoreCase(args[0])) {
            JdbcChaosLoaderApp.main(new String[0]);
        }
    }
}
