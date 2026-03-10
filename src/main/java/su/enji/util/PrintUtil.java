package su.enji.util;

public final class PrintUtil {

    private PrintUtil() {
    }

    private static void printStatus(String status, String color, Object message) {
        System.out.printf("%s[%s]: %s\u001B[0m\n", color, status, message);
    }

    public static void printFatal(Object message) {
        printStatus("fatal", "\u001B[31m", message);
    }

    public static void printInfo(Object message) {
        printStatus("info", "", message);
    }

    public static void printWarning(Object message) {
        printStatus("warning", "\u001B[33m", message);
    }

}
